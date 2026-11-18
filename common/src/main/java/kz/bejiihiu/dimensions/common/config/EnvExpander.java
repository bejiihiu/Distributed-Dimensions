package kz.bejiihiu.dimensions.common.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code ${VAR_NAME}} references against an {@link EnvLookup}. Applied to the
 * database connection strings only — everywhere else a literal {@code $} is left alone.
 *
 * <p>An unset or blank variable is a hard failure naming the variable, the config key,
 * and the remediation. Resolved values never appear in exceptions or logs.
 */
final class EnvExpander {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private EnvExpander() {
    }

    static String expand(String raw, String keyPath, EnvLookup env) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = REFERENCE.matcher(raw);
        if (!matcher.find()) {
            return raw;
        }
        matcher.reset();
        StringBuilder out = new StringBuilder(raw.length() + 32);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = env.get(name).orElseThrow(() -> new ConfigurationException(
                    keyPath + " references environment variable '" + name + "' which is not set or is blank — "
                            + "set it in the service environment before starting, "
                            + "or replace ${" + name + "} with a literal value (empty string = no credentials)"));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
