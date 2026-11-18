package kz.bejiihiu.dimensions.common.util;

import java.util.UUID;

/**
 * UUID minting in one place. UUID v4 (random) is the only id we issue for operations, portals,
 * and respawn queries — it needs no coordination between backends and its collision
 * probability is negligible for our volume.
 */
public final class IdGenerator {

    private IdGenerator() {
    }

    public static UUID newId() {
        return UUID.randomUUID();
    }

    /** A v4 UUID's variant/version bits must hold — sanity check for foreign ids. */
    public static boolean looksLikeV4(UUID id) {
        if (id == null) {
            return false;
        }
        return id.version() == 4 && id.variant() == 2;
    }
}
