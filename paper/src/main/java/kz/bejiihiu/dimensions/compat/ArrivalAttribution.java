package kz.bejiihiu.dimensions.compat;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.OperationState;
import kz.bejiihiu.dimensions.common.model.TransferOperation;

import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Target-side arrival attribution: given the journal rows the backend can see, which
 * one just delivered this player to this world?
 *
 * <p>The transfer pipeline never tells the arrival backend which operation just landed —
 * the only shared, durable artifact is the operation journal (same database on every
 * backend). The rows carry player, cause, and the target dimension the source sent the
 * player toward; the join we are handling just told us the joined world's environment,
 * which maps back to the same canonical dimension. Attribution matches them, newest
 * fresh row wins, rows in {@code FAILED} are evidence of a delivery that did <em>not</em>
 * happen and are ignored.
 *
 * <p>"Fresh" is a wall-clock window, not a tight one — the external sync plugin may take
 * a moment between join and completion, and the proxy-side switch may resolve long after
 * the request was sent. The window exists to avoid resurrecting week-old rows; double
 * attribution inside it is harmless because the effects are idempotent.
 */
public final class ArrivalAttribution {

    /** How recently a journal row must have been updated to count as in-flight for this join. */
    public static final long WINDOW_MILLIS = 120_000L;

    /** Tolerated clock skew between backends for the {@code updatedAt} bound. */
    private static final long CLOCK_SKEW_SECONDS = 30L;

    private ArrivalAttribution() {
    }

    /** Maps a world environment to its canonical dimension; empty for custom dimensions. */
    public static @NotNull Optional<DimensionId> dimensionFor(@Nullable World.Environment environment) {
        if (environment == null) {
            return Optional.empty();
        }
        return switch (environment) {
            case NORMAL -> Optional.of(DimensionId.OVERWORLD);
            case NETHER -> Optional.of(DimensionId.NETHER);
            case THE_END -> Optional.of(DimensionId.THE_END);
            default -> Optional.empty();
        };
    }

    /**
     * Selects the freshest usable journal row for this arrival, or empty.
     *
     * @param rows            candidate rows (e.g. {@code journal.listRecent(...)}) — may be unsorted
     * @param playerUuid      the joining player
     * @param joinedDimension the canonical dimension of the joined world
     * @param now             current time
     * @param windowMillis    freshness window
     */
    public static @NotNull Optional<TransferOperation> select(@NotNull List<TransferOperation> rows,
                                                              @NotNull UUID playerUuid,
                                                              @NotNull DimensionId joinedDimension,
                                                              @NotNull Instant now,
                                                              long windowMillis) {
        Instant earliest = now.minusMillis(windowMillis);
        Instant latest = now.plusSeconds(CLOCK_SKEW_SECONDS);
        return rows.stream()
                .filter(row -> row.playerUuid().equals(playerUuid))
                .filter(row -> row.targetDimension().equals(joinedDimension))
                .filter(row -> row.state() != OperationState.FAILED)
                .filter(row -> !row.updatedAt().isBefore(earliest) && !row.updatedAt().isAfter(latest))
                .max(Comparator.comparing(TransferOperation::updatedAt));
    }

    /** Convenience overload for the default window. */
    public static @NotNull Optional<TransferOperation> select(@NotNull List<TransferOperation> rows,
                                                              @NotNull UUID playerUuid,
                                                              @NotNull DimensionId joinedDimension,
                                                              @NotNull Instant now) {
        return select(rows, playerUuid, joinedDimension, now, WINDOW_MILLIS);
    }

    /** Short human description for debug logs. */
    public static @NotNull String describe(@NotNull TransferOperation row) {
        long age = Duration.between(row.updatedAt(), Instant.now()).toSeconds();
        return "(op=" + row.operationId() + ", cause=" + row.cause() + ", target=" + row.targetDimension()
                + ", state=" + row.state() + ", age=" + age + "s)";
    }
}
