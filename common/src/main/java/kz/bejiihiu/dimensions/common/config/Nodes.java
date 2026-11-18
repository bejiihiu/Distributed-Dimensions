package kz.bejiihiu.dimensions.common.config;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Strict accessors and the heal/analyze machinery for the configuration tree.
 * Package-private: nothing here belongs in the public API.
 *
 * <p>All lookups are strict about node kinds — a map where a scalar is expected
 * (or vice versa) is a {@link ConfigurationException} with the dotted path, never a
 * silent coercion. Unknown keys are <em>not</em> errors: they are collected and
 * pruned before merging with the defaults.
 */
final class Nodes {

    private Nodes() {
    }

    /** What the analysis found. Unknown paths get pruned and reported as warnings. */
    record Plan(List<List<String>> unknownPaths, List<String> structuralErrors) {
    }

    // ------------------------------------------------------------------
    // basic navigation
    // ------------------------------------------------------------------

    static CommentedConfigurationNode at(CommentedConfigurationNode root, String... path) {
        return root.node((Object[]) path);
    }

    static String dotted(List<String> path) {
        return String.join(".", path);
    }

    static String kind(ConfigurationNode node) {
        if (node == null || node.virtual() || node.raw() == null) {
            return "no value";
        }
        if (node.isMap()) {
            return "a section";
        }
        if (node.isList()) {
            return "a list";
        }
        Object raw = node.raw();
        if (raw instanceof Boolean) {
            return "a boolean";
        }
        if (raw instanceof Number) {
            return "a number";
        }
        return "a string";
    }

    // ------------------------------------------------------------------
    // strict scalar accessors (post-heal: every key exists; raw null is still defended)
    // ------------------------------------------------------------------

    static String string(CommentedConfigurationNode root, String... path) {
        List<String> full = List.of(path);
        ConfigurationNode node = at(root, path);
        Object raw = scalar(node, full);
        if (!(raw instanceof String value)) {
            throw new ConfigurationException(dotted(full) + ": expected a string but found " + kind(node));
        }
        return value;
    }

    static boolean bool(CommentedConfigurationNode root, String... path) {
        List<String> full = List.of(path);
        ConfigurationNode node = at(root, path);
        Object raw = scalar(node, full);
        if (!(raw instanceof Boolean value)) {
            throw new ConfigurationException(dotted(full) + ": expected a boolean but found " + kind(node));
        }
        return value;
    }

    static int intValue(CommentedConfigurationNode root, String... path) {
        long value = number(at(root, path), List.of(path)).longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ConfigurationException(dotted(List.of(path)) + ": value " + value + " is out of int range");
        }
        return (int) value;
    }

    static long longValue(CommentedConfigurationNode root, String... path) {
        return number(at(root, path), List.of(path)).longValue();
    }

    static CommentedConfigurationNode requireSection(CommentedConfigurationNode root, String... path) {
        ConfigurationNode node = at(root, path);
        if (!node.isMap()) {
            throw new ConfigurationException(dotted(List.of(path)) + ": expected a section but found " + kind(node));
        }
        return (CommentedConfigurationNode) node;
    }

    private static Object scalar(ConfigurationNode node, List<String> path) {
        if (node == null || node.virtual() || node.raw() == null) {
            throw new ConfigurationException(dotted(path) + ": missing value");
        }
        if (node.isMap() || node.isList()) {
            throw new ConfigurationException(dotted(path) + ": expected a scalar but found " + kind(node));
        }
        return node.raw();
    }

    private static Number number(ConfigurationNode node, List<String> path) {
        Object raw = scalar(node, path);
        if (raw instanceof Boolean) {
            throw new ConfigurationException(dotted(path) + ": expected a number but found a boolean");
        }
        if (raw instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) {
                throw new ConfigurationException(dotted(path) + ": expected a whole number but found " + raw);
            }
            if (d > 9.2233720368547758E18 || d < -9.2233720368547758E18) {
                throw new ConfigurationException(dotted(path) + ": number out of long range: " + raw);
            }
            return number;
        }
        throw new ConfigurationException(dotted(path) + ": expected a number but found " + kind(node));
    }

    // ------------------------------------------------------------------
    // heal + unknown-key analysis
    // ------------------------------------------------------------------

    /**
     * Walks {@code user} against {@code reference} (the bundled defaults) in lockstep.
     * Returns the structural problems (wrong container at a known key — fatal) and the
     * unknown paths (fine — pruned before merging).
     */
    static Plan analyze(CommentedConfigurationNode reference, CommentedConfigurationNode user) {
        List<List<String>> unknownPaths = new ArrayList<>();
        List<String> structuralErrors = new ArrayList<>();
        analyze(reference, user, new ArrayList<>(), unknownPaths, structuralErrors);
        return new Plan(unknownPaths, structuralErrors);
    }

    private static void analyze(CommentedConfigurationNode reference, CommentedConfigurationNode user,
                                List<String> path, List<List<String>> unknownPaths,
                                List<String> structuralErrors) {
        if (user == null || user.virtual() || user.raw() == null) {
            return; // absent — the heal step fills it from defaults
        }
        if (reference.isMap()) {
            if (!user.isMap()) {
                structuralErrors.add(dotted(path) + ": expected a section but found " + kind(user));
                return;
            }
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : user.childrenMap().entrySet()) {
                String key = String.valueOf(entry.getKey());
                List<String> childPath = new ArrayList<>(path);
                childPath.add(key);
                ConfigurationNode refChild = reference.node(key);
                ConfigurationNode userChild = entry.getValue();
                if (refChild == null || refChild.virtual()) {
                    unknownPaths.add(childPath);
                    continue;
                }
                analyze((CommentedConfigurationNode) refChild, (CommentedConfigurationNode) userChild,
                        childPath, unknownPaths, structuralErrors);
            }
            return;
        }
        // reference is a scalar or a list: containers must match exactly
        boolean userMap = user.isMap();
        boolean userList = user.isList();
        if (userMap || userList != reference.isList()) {
            structuralErrors.add(dotted(path) + ": expected " + kind(reference) + " but found " + kind(user));
        }
    }

    /**
     * Produces the healed tree: user values on top, defaults filling the gaps.
     * Configurate's {@code mergeFrom} fills the receiver's missing/null values from the
     * argument — so the (pruned) user tree is the receiver and the defaults are the source.
     * Throws when {@link Plan#structuralErrors()} is non-empty.
     */
    static CommentedConfigurationNode heal(CommentedConfigurationNode defaults, CommentedConfigurationNode user,
                                           Plan plan) {
        if (!plan.structuralErrors().isEmpty()) {
            throw new ConfigurationException(String.join("; ", plan.structuralErrors()));
        }
        CommentedConfigurationNode healed = user.copy();
        for (List<String> unknownPath : plan.unknownPaths()) {
            remove(healed, unknownPath);
        }
        healed.mergeFrom(defaults);
        return healed;
    }

    private static void remove(CommentedConfigurationNode root, List<String> path) {
        List<String> parentPath = path.subList(0, path.size() - 1);
        String key = path.get(path.size() - 1);
        ConfigurationNode parent = parentPath.isEmpty()
                ? root
                : root.node(parentPath.toArray(new Object[0]));
        parent.removeChild(key);
    }
}
