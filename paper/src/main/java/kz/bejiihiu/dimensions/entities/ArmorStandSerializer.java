package kz.bejiihiu.dimensions.entities;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Armor stands — the whole stand travels: equipment (same slots as mobs), pose angles
 * (body/head/arms/legs as EulerAngles in radians), and the marker/baseplate/arms/small/
 * visible/canMove/canTick flags plus the disabled slots set.
 *
 * <p>Poses are stored as three separate radian triples; the {@code Rotations} view of the
 * same data is not serialized (it is the same angles with another accessor).
 */
public final class ArmorStandSerializer extends BaseSerializer {

    @Override
    public String kindToken() {
        return "armour_stand";
    }

    @Override
    public boolean supports(Entity entity) {
        return entity instanceof ArmorStand;
    }

    @Override
    public JsonObject read(Entity entity, EntityTransferMetrics metrics) {
        ArmorStand stand = (ArmorStand) entity;
        JsonObject obj = readLivingNode(stand, metrics);
        FieldCodec.putBoolean(obj, "basePlate", stand.hasBasePlate());
        FieldCodec.putBoolean(obj, "visible", stand.isVisible());
        FieldCodec.putBoolean(obj, "arms", stand.hasArms());
        FieldCodec.putBoolean(obj, "small", stand.isSmall());
        FieldCodec.putBoolean(obj, "marker", stand.isMarker());
        FieldCodec.putBoolean(obj, "canMove", stand.canMove());
        FieldCodec.putBoolean(obj, "canTick", stand.canTick());
        obj.add("bodyPose", FieldCodec.angle(stand.getBodyPose()));
        obj.add("headPose", FieldCodec.angle(stand.getHeadPose()));
        obj.add("leftArmPose", FieldCodec.angle(stand.getLeftArmPose()));
        obj.add("rightArmPose", FieldCodec.angle(stand.getRightArmPose()));
        obj.add("leftLegPose", FieldCodec.angle(stand.getLeftLegPose()));
        obj.add("rightLegPose", FieldCodec.angle(stand.getRightLegPose()));
        JsonArray disabled = new JsonArray();
        for (EquipmentSlot slot : stand.getDisabledSlots()) {
            disabled.add(slot.name());
        }
        obj.add("disabledSlots", disabled);
        return obj;
    }

    @Override
    public void write(Entity entity, JsonObject fields, EntityTransferMetrics metrics) {
        ArmorStand stand = (ArmorStand) entity;
        writeLivingNode(stand, fields, metrics);
        stand.setBasePlate(FieldCodec.getBoolean(fields, "basePlate", true));
        stand.setVisible(FieldCodec.getBoolean(fields, "visible", true));
        stand.setArms(FieldCodec.getBoolean(fields, "arms", false));
        stand.setSmall(FieldCodec.getBoolean(fields, "small", false));
        stand.setMarker(FieldCodec.getBoolean(fields, "marker", false));
        stand.setCanMove(FieldCodec.getBoolean(fields, "canMove", true));
        stand.setCanTick(FieldCodec.getBoolean(fields, "canTick", true));
        stand.setBodyPose(FieldCodec.angle(fields.getAsJsonObject("bodyPose")));
        stand.setHeadPose(FieldCodec.angle(fields.getAsJsonObject("headPose")));
        stand.setLeftArmPose(FieldCodec.angle(fields.getAsJsonObject("leftArmPose")));
        stand.setRightArmPose(FieldCodec.angle(fields.getAsJsonObject("rightArmPose")));
        stand.setLeftLegPose(FieldCodec.angle(fields.getAsJsonObject("leftLegPose")));
        stand.setRightLegPose(FieldCodec.angle(fields.getAsJsonObject("rightLegPose")));
        if (fields.has("disabledSlots") && fields.get("disabledSlots").isJsonArray()) {
            java.util.List<EquipmentSlot> disabled = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement element : fields.getAsJsonArray("disabledSlots")) {
                try {
                    disabled.add(EquipmentSlot.valueOf(element.getAsString()));
                } catch (IllegalArgumentException e) {
                    metrics.increment(EntityTransferMetrics.DEGRADED);
                }
            }
            if (!disabled.isEmpty()) {
                stand.setDisabledSlots(disabled.toArray(EquipmentSlot[]::new));
            }
        }
    }
}
