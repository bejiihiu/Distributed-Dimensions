package kz.bejiihiu.dimensions.mirage;

import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Single-biome provider for mirage worlds: everything is {@code minecraft:the_void}.
 *
 * <p>The constant is resolved per call, not cached in a static field — touching the biome
 * registry before the server finishes bootstrapping (or in a bare JVM, e.g. unit tests)
 * fails with {@code IllegalStateException: No RegistryAccess implementation found}.
 * Thread-safe; returns the same constant from every method.
 */
public final class VoidBiomeProvider extends BiomeProvider {

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        return Biome.THE_VOID;
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo worldInfo) {
        return List.of(Biome.THE_VOID);
    }
}
