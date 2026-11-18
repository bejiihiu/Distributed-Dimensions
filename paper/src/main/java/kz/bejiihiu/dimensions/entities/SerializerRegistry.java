package kz.bejiihiu.dimensions.entities;

import kz.bejiihiu.dimensions.common.model.EntityPayloadKind;
import org.bukkit.entity.Entity;

import java.util.List;

/**
 * Classification order for the serializer whitelist. First match wins on read:
 * item → arrow/trident → boat → minecart (variant by class) → horse family → armor stand →
 * living → degraded floor. The write side is keyed off the payload kind, not the entity
 * (entities do not exist yet at that point).
 *
 * <p>Wither, ender dragon and creaking are never classified here — the listener rejects
 * them before any serialization work happens.
 */
public final class SerializerRegistry {

    private static final ItemSerializer ITEM = new ItemSerializer();
    private static final ArrowSerializer ARROW = new ArrowSerializer();
    private static final BoatSerializer BOAT = new BoatSerializer();
    private static final MinecartSerializer MINECART = new MinecartSerializer();
    private static final HorseSerializer HORSE = new HorseSerializer();
    private static final ArmorStandSerializer ARMOR_STAND = new ArmorStandSerializer();
    private static final MobSerializer MOB = new MobSerializer();
    private static final DegradedSerializer DEGRADED = new DegradedSerializer();

    private static final List<EntitySerializer> BY_ORDER = List.of(
            ITEM, ARROW, BOAT, MINECART, HORSE, ARMOR_STAND, MOB);

    private SerializerRegistry() {
    }

    /** The serializer for the entity, never null (falls back to the degraded floor). */
    public static EntitySerializer forEntity(Entity entity) {
        for (EntitySerializer serializer : BY_ORDER) {
            if (serializer.supports(entity)) {
                return serializer;
            }
        }
        return DEGRADED;
    }

    /** The serializer for a payload kind on the write side, never null. */
    public static EntitySerializer forKind(EntityPayloadKind kind) {
        return switch (kind) {
            case ITEM -> ITEM;
            case ARROW -> ARROW;
            case BOAT -> BOAT;
            case MINECART, MINECART_CHEST, MINECART_FURNACE, MINECART_HOPPER,
                    MINECART_COMMAND, MINECART_TNT, MINECART_SPAWNER -> MINECART;
            case HORSE, DONKEY, MULE, LLAMA, ZOMBIE_HORSE, SKELETON_HORSE -> HORSE;
            case ARMOR_STAND -> ARMOR_STAND;
            case MOB -> MOB;
            case UNKNOWN_DEGRADED -> DEGRADED;
        };
    }

    /** The payload kind for the entity — the serializer's own choice, never null. */
    public static EntityPayloadKind kindFor(Entity entity) {
        return forEntity(entity).kindOf(entity);
    }
}
