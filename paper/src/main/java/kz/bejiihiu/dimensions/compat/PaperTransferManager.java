package kz.bejiihiu.dimensions.compat;

import kz.bejiihiu.dimensions.api.DimensionId;
import kz.bejiihiu.dimensions.api.OperationState;
import kz.bejiihiu.dimensions.api.TransferError;
import kz.bejiihiu.dimensions.api.TransferManager;
import kz.bejiihiu.dimensions.api.TransferResult;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferOperation;
import kz.bejiihiu.dimensions.interop.CrossServerTransfer;
import kz.bejiihiu.dimensions.interop.InteropMapping;
import kz.bejiihiu.dimensions.interop.TransferCommand;
import kz.bejiihiu.dimensions.mirage.ArrivalClamp;
import kz.bejiihiu.dimensions.paper.PaperServices;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The published {@link TransferManager} implementation.
 *
 * <p>{@link #transfer(Player, DimensionId)} reuses the same cross-server machinery as a
 * portal-initiated transfer (same journal, same proxy request, same timeout) with cause
 * {@code PROGRAMMATIC} and the player's current coordinates as the arrival point, clamped
 * to the target dimension's rules (see {@link ArrivalClamp}).
 *
 * <p>Calling against an unmapped target is a programming error: the api contract requires
 * {@link IllegalArgumentException} there, before anything happens.
 *
 * <p>{@link #transfer(Entity, DimensionId)} additionally fails fast with {@code REJECTED} —
 * entity transfers ship with Phase 5. A {@link Player} passed as {@code Entity} is not a
 * shortcut around that: it is simply the player flow.
 *
 * <p>{@link #status(UUID)} maps the journal row to the api state machine; an unavailable
 * (fail-closed) persistence reports {@code Optional.empty()} rather than throwing, because
 * "unknown" and "unavailable" are indistinguishable to callers and empty is honest.
 */
public final class PaperTransferManager implements TransferManager {

    private final PaperServices services;
    private final CrossServerTransfer executor;

    public PaperTransferManager(@NotNull PaperServices services, @NotNull CrossServerTransfer executor) {
        this.services = Objects.requireNonNull(services, "services");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public @NotNull TransferResult transfer(@NotNull Player player, @NotNull DimensionId target) {
        kz.bejiihiu.dimensions.common.model.DimensionId common = InteropMapping.commonDimension(target);
        requireMapping(common);
        return executor.transfer(new TransferCommand(player, common,
                clampedArrival(player, common), TransferCause.PROGRAMMATIC));
    }

    @Override
    public @NotNull TransferResult transfer(@NotNull Entity entity, @NotNull DimensionId target) {
        kz.bejiihiu.dimensions.common.model.DimensionId common = InteropMapping.commonDimension(target);
        requireMapping(common);
        if (entity instanceof Player player) {
            return executor.transfer(new TransferCommand(player, common,
                    clampedArrival(player, common), TransferCause.PROGRAMMATIC));
        }
        return TransferResult.failure(UUID.randomUUID(), TransferError.REJECTED,
                "entity transfers are not supported yet (phase 5); nothing was moved");
    }

    @Override
    public @NotNull Optional<OperationState> status(@NotNull UUID operationId) {
        Objects.requireNonNull(operationId, "operationId");
        Optional<TransferOperation> row;
        try {
            row = services.journal().findById(operationId);
        } catch (RuntimeException e) {
            // fail-closed persistence or a vanished row — indistinguishable to the caller
            return Optional.empty();
        }
        return row.map(operation -> InteropMapping.apiOperationState(operation.state()));
    }

    private void requireMapping(@NotNull kz.bejiihiu.dimensions.common.model.DimensionId target) {
        if (services.dimensions().serverFor(target).isEmpty()) {
            throw new IllegalArgumentException(
                    "target dimension '" + target.key() + "' does not map to any configured backend");
        }
    }

    private static @NotNull Position clampedArrival(@NotNull Player player,
                                                    @NotNull kz.bejiihiu.dimensions.common.model.DimensionId target) {
        Location here = player.getLocation();
        return ArrivalClamp.arrival(target, new Position(here.getX(), here.getY(), here.getZ(),
                here.getYaw(), here.getPitch()));
    }
}
