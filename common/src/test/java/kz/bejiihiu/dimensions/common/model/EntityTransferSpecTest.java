package kz.bejiihiu.dimensions.common.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTransferSpecTest {

    private static EntityTransferSpec leaf() {
        return new EntityTransferSpec(
                UUID.randomUUID(), "minecraft:pig",
                new Position(1, 64, 2), new Velocity(0.1, 0.0, -0.2),
                List.of(), EntityPortalCooldownHint.fresh(EntityPortalCooldownType.COMMON));
    }

    private static EntityTransferSpec assembly(int depth) {
        EntityTransferSpec node = leaf();
        for (int i = 0; i < depth; i++) {
            node = new EntityTransferSpec(
                    UUID.randomUUID(), "minecraft:boat",
                    new Position(1, 64, 2), Velocity.ZERO,
                    List.of(node), null);
        }
        return node;
    }

    @Test
    void buildsNestedPassengerGraph() {
        EntityTransferSpec root = assembly(3);
        assertEquals(1, root.passengers().size());
        assertEquals(1, root.passengers().getFirst().passengers().size());
        assertEquals("minecraft:pig",
                root.passengers().getFirst().passengers().getFirst().passengers().getFirst().entityTypeKey());
    }

    @Test
    void depthCapIsEnforced() {
        // building a tree one level below the cap works, beyond it throws
        assertThrows(IllegalArgumentException.class, () -> {
            EntityTransferSpec node = leaf();
            for (int i = 0; i <= EntityTransferSpec.MAX_PASSENGER_DEPTH; i++) {
                node = new EntityTransferSpec(
                        UUID.randomUUID(), "minecraft:boat",
                        new Position(0, 0, 0), Velocity.ZERO,
                        List.of(node), null);
            }
        });
    }

    @Test
    void entityTypeKeyIsValidatedAndNormalized() {
        EntityTransferSpec spec = new EntityTransferSpec(
                UUID.randomUUID(), "  MINECRAFT:Minecart ", new Position(0, 0, 0), Velocity.ZERO, List.of(), null);
        assertEquals("minecraft:minecart", spec.entityTypeKey());
        assertThrows(IllegalArgumentException.class, () -> new EntityTransferSpec(
                UUID.randomUUID(), "not a key", new Position(0, 0, 0), Velocity.ZERO, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new EntityTransferSpec(
                UUID.randomUUID(), "minecraft:", new Position(0, 0, 0), Velocity.ZERO, List.of(), null));
    }

    @Test
    void nonFiniteVelocityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Velocity(Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Velocity(0, Double.POSITIVE_INFINITY, 0));
    }

    @Test
    void cooldownHintValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new EntityPortalCooldownHint(EntityPortalCooldownType.COMMON, -1));
        assertEquals(300, EntityPortalCooldownHint.fresh(EntityPortalCooldownType.COMMON).ticksRemaining());
        assertEquals(10, EntityPortalCooldownHint.fresh(EntityPortalCooldownType.VEHICLE).ticksRemaining());
        assertEquals(2, EntityPortalCooldownHint.fresh(EntityPortalCooldownType.PROJECTILE).ticksRemaining());
        assertEquals(0, EntityPortalCooldownHint.fresh(EntityPortalCooldownType.PLAYER_ASSEMBLY).ticksRemaining());
    }

    @Test
    void cooldownTypeTokensRoundTrip() {
        for (EntityPortalCooldownType type : EntityPortalCooldownType.values()) {
            assertEquals(type, EntityPortalCooldownType.fromWireName(type.wireName()));
        }
        assertEquals("common", EntityPortalCooldownType.COMMON.wireName());
        assertEquals("player_assembly", EntityPortalCooldownType.PLAYER_ASSEMBLY.wireName());
    }

    @Test
    void passengersAreImmutable() {
        List<EntityTransferSpec> children = new java.util.ArrayList<>(List.of(leaf()));
        EntityTransferSpec root = new EntityTransferSpec(
                UUID.randomUUID(), "minecraft:boat", new Position(0, 0, 0), Velocity.ZERO, children, null);
        assertThrows(UnsupportedOperationException.class, () -> root.passengers().add(leaf()));
        // and the caller's list is detached too
        children.clear();
        assertEquals(1, root.passengers().size());
    }

    @Test
    void chunkTicketDefaults() {
        ChunkTicketSpec ticket = ChunkTicketSpec.destinationPortal(DimensionId.NETHER, 12, -34, true);
        assertEquals(300, ticket.ttlTicks());
        assertEquals(ChunkTicketSpec.DEFAULT_TTL_TICKS, ticket.ttlTicks());
        assertEquals(30, ChunkTicketSpec.ENTITY_TICKING_LOAD_LEVEL);
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkTicketSpec(DimensionId.NETHER, 0, 0, false, 0));
    }
}
