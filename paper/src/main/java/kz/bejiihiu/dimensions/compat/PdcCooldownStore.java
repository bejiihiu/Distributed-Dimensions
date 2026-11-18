package kz.bejiihiu.dimensions.compat;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * {@link CooldownStore} backed by the entity's own {@link PersistentDataContainer}.
 *
 * <p>Two keys are written on a portal pass:
 *
 * <ul>
 *   <li>{@code distributeddimensions:portal_cooldown_at} — last-pass wall-clock millis (LONG)</li>
 *   <li>{@code distributeddimensions:portal_cooldown_op} — the operation that stamped it (STRING, UUID)</li>
 * </ul>
 *
 * <p>Data lives on the entity itself, which is exactly right for cooldown semantics: it travels
 * with the entity object and dies with it, needs no database, and is visible to any feature wave
 * that reads the same container. The PDC is namespaced with the plugin's own key so we never
 * collide with third-party data.
 */
public final class PdcCooldownStore implements CooldownStore {

    /** The key holding the last portal-pass wall-clock millis (long). */
    public static final String LAST_PASS_KEY = "portal_cooldown_at";
    /** The key holding the operation id of the last portal pass (string UUID). */
    public static final String LAST_OPERATION_KEY = "portal_cooldown_op";

    private final NamespacedKey lastPassKey;
    private final NamespacedKey lastOperationKey;

    public PdcCooldownStore(@NotNull Plugin plugin) {
        this.lastPassKey = new NamespacedKey(plugin, LAST_PASS_KEY);
        this.lastOperationKey = new NamespacedKey(plugin, LAST_OPERATION_KEY);
    }

    @Override
    public void stamp(@NotNull Entity entity, @NotNull UUID operationId, long atMillis) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        pdc.set(lastPassKey, org.bukkit.persistence.PersistentDataType.LONG, atMillis);
        pdc.set(lastOperationKey, org.bukkit.persistence.PersistentDataType.STRING, operationId.toString());
    }

    @Override
    public @Nullable Long lastPassMillis(@NotNull Entity entity) {
        Long value = entity.getPersistentDataContainer().get(lastPassKey, org.bukkit.persistence.PersistentDataType.LONG);
        return value;
    }

    @Override
    public @Nullable UUID lastOperationId(@NotNull Entity entity) {
        String value = entity.getPersistentDataContainer().get(lastOperationKey, org.bukkit.persistence.PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
