package kz.bejiihiu.dimensions.interop;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One cross-server transfer request, as accepted by {@link CrossServerTransfer}.
 *
 * @param player          the player to move; must be online and valid at call time
 * @param targetDimension the canonical dimension of the destination backend
 * @param arrival         the coordinates the player should land at (already position-rule
 *                        clamped by the caller — see the mirage guards)
 * @param cause           what triggered the transfer (provenance only; never trusted for routing)
 * @param portalId        reserved for portal-driven flows (Nether/End waves); the mirage guards
 *                        and the programmatic API pass {@code null}
 */
public record TransferCommand(@NotNull Player player, @NotNull DimensionId targetDimension,
                              @NotNull Position arrival, @NotNull TransferCause cause,
                              @Nullable String portalId) {

    public TransferCommand {
        java.util.Objects.requireNonNull(player, "player");
        java.util.Objects.requireNonNull(targetDimension, "targetDimension");
        java.util.Objects.requireNonNull(arrival, "arrival");
        java.util.Objects.requireNonNull(cause, "cause");
        if (portalId != null) {
            String trimmed = portalId.strip();
            if (trimmed.isEmpty() || trimmed.length() > 128 || trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("portalId must be 1-128 chars without newlines, got '" + portalId + "'");
            }
            portalId = trimmed;
        }
    }

    public TransferCommand(@NotNull Player player, @NotNull DimensionId targetDimension,
                           @NotNull Position arrival, @NotNull TransferCause cause) {
        this(player, targetDimension, arrival, cause, null);
    }
}
