package kz.bejiihiu.dimensions.mirage;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidChunkGeneratorTest {

    /** Minimal ChunkData that tracks explicit writes and defaults to AIR everywhere. */
    private static final class FakeChunkData implements ChunkGenerator.ChunkData {
        private final int minHeight;
        private final int maxHeight;
        private final Map<Long, Material> setBlocks = new HashMap<>();
        private boolean regionMarkedAir = false;

        private FakeChunkData(int minHeight, int maxHeight) {
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
        }

        @Override
        public int getMinHeight() {
            return minHeight;
        }

        @Override
        public int getMaxHeight() {
            return maxHeight;
        }

        @Override
        public Material getType(int x, int y, int z) {
            Long key = key(x, y, z);
            Material explicit = setBlocks.get(key);
            if (explicit != null) {
                return explicit;
            }
            return Material.AIR;
        }

        @Override
        public void setBlock(int x, int y, int z, Material material) {
            setBlocks.put(key(x, y, z), material);
        }

        @Override
        public void setBlock(int x, int y, int z, org.bukkit.material.MaterialData material) {
            setBlock(x, y, z, material.getItemType());
        }

        @Override
        public void setBlock(int x, int y, int z, BlockData blockData) {
            setBlock(x, y, z, blockData.getMaterial());
        }

        @Override
        public void setRegion(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax, Material material) {
            if (material != Material.AIR) {
                throw new IllegalArgumentException("void generator must only write AIR");
            }
            regionMarkedAir = true;
        }

        @Override
        public void setRegion(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax,
                              org.bukkit.material.MaterialData material) {
            setRegion(xMin, yMin, zMin, xMax, yMax, zMax, material.getItemType());
        }

        @Override
        public void setRegion(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax, BlockData blockData) {
            setRegion(xMin, yMin, zMin, xMax, yMax, zMax, blockData.getMaterial());
        }

        @Override
        public org.bukkit.block.Biome getBiome(int x, int y, int z) {
            throw new UnsupportedOperationException(
                    "FakeChunkData refuses biome lookups — biome registries need a running server");
        }

        @Override
        public org.bukkit.material.MaterialData getTypeAndData(int x, int y, int z) {
            return null;
        }

        @Override
        public BlockData getBlockData(int x, int y, int z) {
            return null;
        }

        @Override
        public byte getData(int x, int y, int z) {
            return 0;
        }

        @Override
        public int getHeight(org.bukkit.HeightMap heightMap, int x, int z) {
            return getMinHeight();
        }
    }

    private static Long key(int x, int y, int z) {
        return ((long) x & 0xfffffL) << 44 | ((long) (y + 2048) & 0xffffffL) << 20 | ((long) z & 0xfffffL);
    }

    private static WorldInfo worldInfo() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> "TestWorldInfo";
            default -> defaultValue(method.getReturnType());
        };
        return (WorldInfo) Proxy.newProxyInstance(
                WorldInfo.class.getClassLoader(), new Class<?>[] { WorldInfo.class }, handler);
    }

    private static World world() {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> "TestWorld";
            default -> defaultValue(method.getReturnType());
        };
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class }, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            if (java.util.Collection.class.isAssignableFrom(type) || java.util.List.class.isAssignableFrom(type)) {
                return java.util.List.of();
            }
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }

    @Test
    void generateNoiseLeavesEveryBlockAir() {
        VoidChunkGenerator generator = new VoidChunkGenerator();
        FakeChunkData data = new FakeChunkData(-64, 320);
        generator.generateNoise(worldInfo(), new Random(42), 7, -3, data);
        assertTrue(data.regionMarkedAir, "generateNoise must bulk-fill air");
        for (int y = -64; y < 320; y += 17) {
            for (int x = 0; x < 16; x += 15) {
                assertEquals(Material.AIR, data.getType(x, y, 0), "block at y=" + y + " must be AIR");
            }
        }
    }

    @Test
    void allVanillaGenerationStagesAreDisabled() {
        VoidChunkGenerator generator = new VoidChunkGenerator();
        WorldInfo info = worldInfo();
        Random random = new Random(1);
        assertFalse(generator.shouldGenerateNoise());
        assertFalse(generator.shouldGenerateNoise(info, random, 0, 0));
        assertFalse(generator.shouldGenerateSurface());
        assertFalse(generator.shouldGenerateSurface(info, random, 0, 0));
        assertFalse(generator.shouldGenerateCaves());
        assertFalse(generator.shouldGenerateCaves(info, random, 0, 0));
        assertFalse(generator.shouldGenerateDecorations());
        assertFalse(generator.shouldGenerateDecorations(info, random, 0, 0));
        assertFalse(generator.shouldGenerateMobs());
        assertFalse(generator.shouldGenerateMobs(info, random, 0, 0));
        assertFalse(generator.shouldGenerateStructures());
        assertFalse(generator.shouldGenerateStructures(info, random, 0, 0));
    }

    @Test
    void noPopulatorsAndFixedVoidSpawn() {
        VoidChunkGenerator generator = new VoidChunkGenerator();
        assertTrue(generator.getDefaultPopulators(world()).isEmpty());
        Location spawn = generator.getFixedSpawnLocation(world(), new Random(1));
        assertNotNull(spawn);
        assertEquals(100.0, spawn.getY(), 0.0);
        assertEquals(0.5, spawn.getX(), 0.0);
        assertEquals(0.5, spawn.getZ(), 0.0);
    }

    @Test
    void canSpawnIsAlwaysFalse() {
        assertFalse(new VoidChunkGenerator().canSpawn(world(), 0, 0));
    }
}

