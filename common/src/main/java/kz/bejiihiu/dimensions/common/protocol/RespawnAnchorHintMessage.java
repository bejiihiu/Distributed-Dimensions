package kz.bejiihiu.dimensions.common.protocol;

import org.jetbrains.annotations.Nullable;
import kz.bejiihiu.dimensions.common.model.DimensionId;

import java.util.Objects;
import java.util.UUID;

/**
 * Backend → proxy: a player set (or cleared) a respawn anchor inside this server's nether.
 * Lets the exit-portal flow route straight to the nether backend instead of asking the
 * overworld backend to discover the anchor by round-trip.
 *
 * <pre>{@code
 * {"v":1,"type":"respawn_anchor_hint",
 *  "player_uuid":"…","anchor_active":true,"anchor_dimension":"minecraft:the_nether"}
 * }</pre>
 *
 * Invariants (enforced by the record): {@code anchor_active = true} requires a dimension;
 * {@code anchor_active = false} requires none.
 */
public record RespawnAnchorHintMessage(UUID playerUuid, boolean anchorActive,
                                       @Nullable DimensionId anchorDimension)
        implements ProtocolMessage {

    public static final String TYPE = "respawn_anchor_hint";

    public RespawnAnchorHintMessage {
        Objects.requireNonNull(playerUuid, "playerUuid");
        if (anchorActive) {
            Objects.requireNonNull(anchorDimension, "anchorDimension is required when anchorActive is true");
        } else if (anchorDimension != null) {
            throw new IllegalArgumentException("anchorDimension must be absent when anchorActive is false");
        }
    }

    @Override
    public String type() {
        return TYPE;
    }
}
