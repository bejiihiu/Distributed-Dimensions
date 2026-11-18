package kz.bejiihiu.dimensions.common.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityTransferPayloadTest {

    private static final Instant NOW = Instant.ofEpochMilli(1_800_000_000_000L);

    private static EntityTransferPayload valid(String opId, String payloadJson) {
        return new EntityTransferPayload(opId, 0, EntityPayloadKind.ITEM,
                "nether", "minecraft:nether", null,
                payloadJson, EntityPayloadState.PENDING, NOW, NOW);
    }

    @Test
    void wireNamesRoundTrip() {
        for (EntityPayloadKind kind : EntityPayloadKind.values()) {
            assertEquals(kind, EntityPayloadKind.fromWireName(kind.wireName()));
        }
        for (EntityPayloadState state : EntityPayloadState.values()) {
            assertEquals(state, EntityPayloadState.fromWireName(state.wireName()));
        }
    }

    @Test
    void unknownWireTokensAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> EntityPayloadKind.fromWireName("nope"));
        assertThrows(IllegalArgumentException.class, () -> EntityPayloadState.fromWireName("nope"));
    }

    @Test
    void opIdMustBeAUuidString() {
        assertThrows(IllegalArgumentException.class, () -> valid("not-a-uuid", "{}"));
        assertThrows(IllegalArgumentException.class, () -> valid("AAAAAAAA-0000-0000-0000-000000000000", "{}"));
    }

    @Test
    void seqBoundsAreEnforced() {
        String opId = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> new EntityTransferPayload(
                opId, -1, EntityPayloadKind.ITEM, "nether", "minecraft:nether", null,
                "{}", EntityPayloadState.PENDING, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new EntityTransferPayload(
                opId, 1024, EntityPayloadKind.ITEM, "nether", "minecraft:nether", null,
                "{}", EntityPayloadState.PENDING, NOW, NOW));
    }

    @Test
    void oversizedPayloadIsRejected() {
        String opId = UUID.randomUUID().toString();
        String big = "x".repeat(48 * 1024 + 1);
        assertThrows(IllegalArgumentException.class, () -> valid(opId, big));
    }

    @Test
    void blankTargetServerIsRejected() {
        String opId = UUID.randomUUID().toString();
        assertThrows(IllegalArgumentException.class, () -> new EntityTransferPayload(
                opId, 0, EntityPayloadKind.ITEM, "  ", "minecraft:nether", null,
                "{}", EntityPayloadState.PENDING, NOW, NOW));
    }

    @Test
    void movedToFlipsStateAndOnlyUpdatedAt() {
        String opId = UUID.randomUUID().toString();
        EntityTransferPayload pending = valid(opId, "{}");
        Instant later = NOW.plusSeconds(10);
        EntityTransferPayload claimed = pending.movedTo(EntityPayloadState.CLAIMED, later);
        assertEquals(EntityPayloadState.CLAIMED, claimed.state());
        assertEquals(NOW, claimed.createdAt());
        assertEquals(later, claimed.updatedAt());
        assertEquals("{}", claimed.payloadJson());
    }
}
