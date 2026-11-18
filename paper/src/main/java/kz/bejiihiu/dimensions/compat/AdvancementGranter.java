package kz.bejiihiu.dimensions.compat;

import kz.bejiihiu.dimensions.api.TransferCause;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Idempotent advancement grants for dimension entry, per compat A:
 *
 * <table>
 *   <caption>Cause → advancement criterion</caption>
 *   <tr><th>Cause</th><th>Advancement</th><th>Criterion</th></tr>
 *   <tr><td>{@code NETHER_PORTAL}</td><td>{@code minecraft:story/enter_the_nether}</td><td>{@code entered_nether}</td></tr>
 *   <tr><td>{@code END_PORTAL}</td><td>{@code minecraft:story/enter_the_end}</td><td>{@code entered_end}</td></tr>
 * </table>
 *
 * <p>Criteria verified against the vanilla advancement data (both advancements are a single
 * {@code minecraft:changed_dimension} criterion). All other causes ({@code RESPAWN},
 * {@code PROGRAMMATIC}, {@code MIRAGE_TELEPORT}) grant nothing, matching vanilla semantics —
 * vanilla only advances these advancements on an actual dimension change. {@code END_GATEWAY}
 * does not exist as a cause; gateway crossings inside the End grant nothing either.
 *
 * <p>{@code awardCriteria} is idempotent by contract (returns false when the criterion was
 * already awarded) — safe to call on every arrival, including retries. A missing advancement
 * (datapack replaced it) or a backend mismatch is a warning-level condition, never an error:
 * third-party datapacks legitimately own their own trees.
 */
public final class AdvancementGranter {

    public enum Outcome {
        /** Criterion requested and newly awarded. */
        GRANTED,
        /** Criterion requested and already awarded (no-op, idempotent). */
        ALREADY_HAD,
        /** The cause maps to no advancement; nothing attempted. */
        NOT_APPLICABLE,
        /** The advancement was absent or awarding failed — warning logged. */
        FAILED
    }

    /** Rule mapping for tests and diagnostics. */
    public record AdvancementRule(@NotNull String advancementKey, @NotNull String criterion) {
    }

    private final Logger logger;

    public AdvancementGranter(@NotNull Logger logger) {
        this.logger = logger;
    }

    /** The rule for a cause, or null when the cause grants nothing. */
    public static @Nullable AdvancementRule ruleFor(@NotNull TransferCause cause) {
        return switch (cause) {
            case NETHER_PORTAL -> new AdvancementRule("minecraft:story/enter_the_nether", "entered_nether");
            case END_PORTAL -> new AdvancementRule("minecraft:story/enter_the_end", "entered_end");
            default -> null;
        };
    }

    /**
     * Awards the criterion for {@code cause} to {@code player}, idempotently.
     *
     * @return what happened; never null
     */
    public @NotNull Outcome grant(@NotNull Player player, @NotNull TransferCause cause) {
        AdvancementRule rule = ruleFor(cause);
        if (rule == null) {
            return Outcome.NOT_APPLICABLE;
        }
        NamespacedKey key;
        try {
            int colon = rule.advancementKey().indexOf(':');
            key = new NamespacedKey(rule.advancementKey().substring(0, colon), rule.advancementKey().substring(colon + 1));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            logger.warn("advancement grant for {} skipped: malformed bundled key {}: {}",
                    cause, rule.advancementKey(), e.getMessage());
            return Outcome.FAILED;
        }
        Advancement advancement = Bukkit.getAdvancement(key);
        if (advancement == null) {
            logger.warn("advancement '{}' not found on this backend (datapack replaced it?) — skipping grant for {}",
                    rule.advancementKey(), cause);
            return Outcome.FAILED;
        }
        try {
            boolean awardedNow = player.getAdvancementProgress(advancement).awardCriteria(rule.criterion());
            return awardedNow ? Outcome.GRANTED : Outcome.ALREADY_HAD;
        } catch (RuntimeException e) {
            logger.warn("could not award criterion '{}' of '{}' to {}: {}",
                    rule.criterion(), rule.advancementKey(), player.getUniqueId(), e.toString());
            return Outcome.FAILED;
        }
    }
}
