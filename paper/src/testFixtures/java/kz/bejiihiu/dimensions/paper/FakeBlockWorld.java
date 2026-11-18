package kz.bejiihiu.dimensions.paper;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A world proxy with a block map, for tests that validate bed/anchor positions.
 * The block map is authoritative: unset coordinates read as {@link Material#AIR}.
 * Like the other fixtures, any accessor not explicitly modelled throws
 * {@link UnsupportedOperationException}.
 */
public final class FakeBlockWorld {

    private final Map<String, Material> blocks = new HashMap<>();
    private final Map<String, Integer> charges = new HashMap<>();
    private final World.Environment environment;
    private final String name;
    private final World world;

    private FakeBlockWorld(String name, World.Environment environment) {
        this.name = name;
        this.environment = environment;
        this.world = buildWorld();
    }

    /** A NORMAL-environment world named {@code name}. */
    public static FakeBlockWorld create(String name) {
        return create(name, World.Environment.NORMAL);
    }

    public static FakeBlockWorld create(String name, World.Environment environment) {
        return new FakeBlockWorld(name, environment);
    }

    /** The proxy world itself — pass it wherever a {@link World} is expected. */
    public World world() {
        return world;
    }

    public FakeBlockWorld setBlock(int x, int y, int z, Material material) {
        blocks.put(key(x, y, z), material);
        return this;
    }

    /** Anchor charge for the block at the coordinates (defaults to 0). */
    public FakeBlockWorld setCharges(int x, int y, int z, int amount) {
        charges.put(key(x, y, z), amount);
        return this;
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private World buildWorld() {
        UUID uid = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getUID":
                    return uid;
                case "getName":
                case "getWorldName":
                    return name;
                case "getEnvironment":
                    return environment;
                case "getSpawnLocation":
                    return new Location(world(), 0.0, 64.0, 0.0, 0.0f, 0.0f);
                case "getBlockAt": {
                    int x = (Integer) args[0];
                    int y = (Integer) args[1];
                    int z = (Integer) args[2];
                    return block(x, y, z);
                }
                case "hashCode":
                    return uid.hashCode();
                case "equals":
                    return proxy == (args == null ? null : args[0]);
                case "toString":
                    return "FakeBlockWorld[" + name + "]";
                default:
                    throw new UnsupportedOperationException(
                            "FakeBlockWorld refuses " + method.getName() + " - extend the fake deliberately");
            }
        };
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[] { World.class }, handler);
    }

    private Block block(int x, int y, int z) {
        Material material = blocks.getOrDefault(key(x, y, z), Material.AIR);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getType" -> material;
            case "getBlockData" -> respawnAnchorData(material, charges.getOrDefault(key(x, y, z), 0));
            case "getX" -> x;
            case "getY" -> y;
            case "getZ" -> z;
            case "getWorld" -> world();
            case "hashCode" -> (x * 31L + z) * 31 + y;
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> "FakeBlock[" + material + " @ " + key(x, y, z) + "]";
            default -> throw new UnsupportedOperationException(
                    "FakeBlockWorld block refuses " + method.getName());
        };
        return (Block) Proxy.newProxyInstance(
                Block.class.getClassLoader(), new Class<?>[] { Block.class }, handler);
    }

    private RespawnAnchor respawnAnchorData(Material material, int charge) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getCharges" -> charge;
            case "getMaximumCharges" -> 4;
            case "setCharges" -> {
                throw new UnsupportedOperationException("read-only in tests");
            }
            case "getAsString" -> material.name().toLowerCase(java.util.Locale.ROOT);
            case "clone" -> proxy;
            case "hashCode" -> charge * 31 + material.hashCode();
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> "FakeRespawnAnchor[charges=" + charge + "]";
            default -> throw new UnsupportedOperationException(
                    "FakeBlockWorld anchor blockdata refuses " + method.getName());
        };
        // only meaningful for RESPAWN_ANCHOR blocks; other materials still get a data proxy
        // so block-data checks do not blow up — the validator reads type first anyway
        return (RespawnAnchor) Proxy.newProxyInstance(
                RespawnAnchor.class.getClassLoader(), new Class<?>[] { RespawnAnchor.class }, handler);
    }
}
