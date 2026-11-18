package kz.bejiihiu.dimensions.admin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two descriptor invariants that break the backend at boot time and
 * are otherwise only caught on a live server: the plugin name (26.x forbids
 * spaces) and the root-level {@code defaultPerm}/{@code permissions} shape that
 * Paper's plugin-meta deserializer reads (flattened at the YAML root).
 *
 * <p>The permissions map is replayed through {@code Permission.loadPermission} —
 * the same call Paper's deserializer makes — so a malformed entry fails here,
 * in a unit test, instead of at plugin load.
 */
class PaperPluginYmlTest {

    private static YamlConfiguration yaml() throws Exception {
        try (var in = PaperPluginYmlTest.class.getClassLoader().getResourceAsStream("paper-plugin.yml")) {
            assertNotNull(in, "paper-plugin.yml must be on the classpath");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return YamlConfiguration.loadConfiguration(new StringReader(content));
        }
    }

    @Test
    void pluginNameHasNoForbiddenCharacters() throws Exception {
        YamlConfiguration yml = yaml();
        String name = yml.getString("name");
        assertNotNull(name);
        assertFalse(name.contains(" "), "Paper 26.x rejects plugin names containing 0x20");
        // the platform rule: [a-zA-Z][a-zA-Z0-9_\-]* (roughly) — ours is DistributedDimensions
        assertEquals("DistributedDimensions", name);
    }

    @Test
    void permissionsBlockParsesLikeThePaperDeserializerParsesIt() throws Exception {
        YamlConfiguration yml = yaml();

        assertEquals("op", yml.getString("defaultPerm"));

        ConfigurationSection node = yml.getConfigurationSection("permissions.distributeddimensions.admin");
        assertNotNull(node, "distributeddimensions.admin must be declared");
        String description = node.getString("description");
        assertNotNull(description);
        assertFalse(description.isBlank());
        assertEquals("op", node.getString("default"));

        // replay the exact shape Paper hands to Permission.loadPermission: a raw map of the entry
        Map<String, Object> data = new LinkedHashMap<>();
        for (String key : node.getKeys(false)) {
            data.put(key, node.get(key));
        }
        Permission permission = Permission.loadPermission("distributeddimensions.admin", data,
                PermissionDefault.OP, null);
        assertEquals("distributeddimensions.admin", permission.getName());
        assertEquals(PermissionDefault.OP, permission.getDefault());
        assertEquals(description, permission.getDescription());
    }

    @Test
    void noCommandsSectionIsDeclared() throws Exception {
        YamlConfiguration yml = yaml();
        // paper-plugin.yml has no commands field at all; a commands key here would be dead data
        assertFalse(yml.contains("commands"),
                "paper-plugin.yml must not declare commands — they are registered from code (BasicCommand)");
    }

    @Test
    void permissionNodeRegisteredIsTheOneTheCommandUses() {
        assertEquals("distributeddimensions.admin", DdCommand.PERMISSION);
        assertTrue(DdCommand.PERMISSION.startsWith("distributeddimensions."));
    }
}
