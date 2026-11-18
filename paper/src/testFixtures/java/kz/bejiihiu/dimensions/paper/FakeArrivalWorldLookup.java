package kz.bejiihiu.dimensions.paper;

import org.bukkit.World;

import kz.bejiihiu.dimensions.paper.transfer.ArrivalWorldLookup;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Deterministic world resolution for tests: an explicit level-name -> world map plus a
 * respawn world. Unknown names resolve to null, exactly like a missing world.
 */
public final class FakeArrivalWorldLookup implements ArrivalWorldLookup {

    private final Map<String, World> byName;
    private final World respawnWorld;

    public FakeArrivalWorldLookup(Map<String, World> byName, World respawnWorld) {
        this.byName = java.util.Objects.requireNonNull(byName, "byName");
        this.respawnWorld = respawnWorld;
    }

    @Override
    @Nullable
    public World byName(String levelName) {
        return byName.get(levelName);
    }

    @Override
    @Nullable
    public World respawnWorld() {
        return respawnWorld;
    }
}
