package kz.bejiihiu.dimensions.common.config;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Classpath-resource and filesystem plumbing for the loader. Package-private.
 */
final class FilesSupport {

    private FilesSupport() {
    }

    /** Parses the bundled defaults resource into a fresh tree. */
    static CommentedConfigurationNode readDefaults(String resource) {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .source(() -> openResource(resource))
                .indent(2)
                .nodeStyle(NodeStyle.BLOCK)
                .build();
        try {
            return loader.load();
        } catch (org.spongepowered.configurate.ConfigurateException e) {
            // bundled defaults are shipped by us; a failure here means a broken jar
            throw new ConfigurationException("bundled defaults resource '" + resource + "' cannot be parsed: "
                    + e.getMessage(), e);
        }
    }

    /** Parses a YAML file from disk. Absent file yields an empty tree (the heal step fills it). */
    static CommentedConfigurationNode readFile(Path path) {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(path)
                .indent(2)
                .nodeStyle(NodeStyle.BLOCK)
                .build();
        try {
            return loader.load();
        } catch (org.spongepowered.configurate.ConfigurateException e) {
            throw new ConfigurationException(
                    "cannot parse " + path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** Saves {@code node} to {@code path}; Configurate's path-sink is atomic (sibling temp + atomic move). */
    static void saveFile(Path path, CommentedConfigurationNode node) {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                .path(path)
                .indent(2)
                .nodeStyle(NodeStyle.BLOCK)
                .build();
        try {
            loader.save(node);
        } catch (org.spongepowered.configurate.ConfigurateException e) {
            throw new ConfigurationException("cannot write " + path.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Copies the bundled defaults bytes to {@code target} atomically (sibling temp file +
     * atomic move), creating parent directories. Never overwrites an existing file: a
     * concurrent creator wins and the caller loads whatever is on disk by then.
     */
    static void writeDefaultsFile(String resource, Path target) {
        Path parent = target.getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
            try (InputStream in = openResourceStream(resource)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                try {
                    Files.move(temp, target);
                } catch (java.nio.file.FileAlreadyExistsException raced) {
                    Files.deleteIfExists(temp); // keep the concurrent creator's file
                }
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                Files.deleteIfExists(temp); // keep the concurrent creator's file
            }
        } catch (IOException e) {
            throw new ConfigurationException("cannot write default configuration to " + target + ": " + e.getMessage(), e);
        }
    }

    static boolean exists(Path path) {
        return Files.isRegularFile(path);
    }

    private static BufferedReader openResource(String resource) {
        return new BufferedReader(new InputStreamReader(openResourceStream(resource), StandardCharsets.UTF_8));
    }

    private static InputStream openResourceStream(String resource) {
        InputStream in = FilesSupport.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new ConfigurationException(
                    "bundled defaults resource '" + resource + "' is missing from the classpath — rebuild the plugin");
        }
        return in;
    }
}
