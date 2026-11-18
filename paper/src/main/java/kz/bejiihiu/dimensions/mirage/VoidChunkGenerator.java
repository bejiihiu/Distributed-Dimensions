package kz.bejiihiu.dimensions.mirage;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Random;

/**
 * Void chunk generator for mirage (stub) worlds.
 *
 * <p>Mirage worlds are registered so foreign-dimension plugins keep working on this
 * backend ({@code Bukkit.getWorld("world_nether") != null} and friends). They must
 * therefore never contain anything: every chunk this generator produces is air, and
 * none of the vanilla generation steps ever run ({@code shouldGenerate* == false}).
 *
 * <p>{@link #generateNoise} leaves the provided {@link ChunkData} fully air. When the
 * vanilla noise step is skipped (because {@link #shouldGenerateNoise()} is false) the
 * data starts out as air already; the explicit fill below is belt-and-braces so the
 * contract "chunks from this generator contain nothing but air" holds even if the
 * server changes what the initial ChunkData contains.
 *
 * <p>Never returns a non-air block, never populates entities, never creates structures.
 */
public final class VoidChunkGenerator extends ChunkGenerator {

    private final VoidBiomeProvider biomeProvider = new VoidBiomeProvider();

    @Override
    public void generateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random,
                              int chunkX, int chunkZ, @NotNull ChunkData chunkData) {
        // One bulk write instead of height*256 individual setBlock calls. All three axes are
        // exclusive on the max side (xMax=16 covers 0..15). Blocks not covered remain air
        // regardless — the data starts out as air when the vanilla noise step is skipped.
        int min = chunkData.getMinHeight();
        int max = chunkData.getMaxHeight();
        chunkData.setRegion(0, min, 0, 16, max, 16, Material.AIR);
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateNoise(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public @NotNull List<org.bukkit.generator.BlockPopulator> getDefaultPopulators(@NotNull World world) {
        return List.of();
    }

    @Override
    public @Nullable Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        // The mirage worlds advertise a fixed void spawn at y=100 so nothing else ever
        // picks a "random" spawn point and lands players/mobs inside ungenerated air.
        return new Location(world, 0.5, 100, 0.5);
    }

    @Override
    public boolean canSpawn(@NotNull World world, int x, int z) {
        // Nothing spawns naturally in a void; callers that consult this get the honest answer.
        return false;
    }

    @Override
    public @NotNull BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        return biomeProvider;
    }
}
