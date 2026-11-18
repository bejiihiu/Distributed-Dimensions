package kz.bejiihiu.dimensions.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerRespawnAnswerTest {

    @Test
    void unknownAnswerHasNoPoint() {
        var answer = PlayerRespawnAnswer.unknown(java.util.UUID.randomUUID());
        assertFalse(answer.isKnown());
    }

    @Test
    void knownAnswerCarriesPoint() {
        var point = new RespawnPoint(DimensionId.OVERWORLD, new Position(10, 70, -20, 90, 0), RespawnKind.BED);
        var answer = PlayerRespawnAnswer.known(java.util.UUID.randomUUID(), point);
        assertEquals(point, answer.respawnPoint());
        assertEquals(RespawnKind.BED, answer.respawnPoint().kind());
    }

    @Test
    void respawnKindTokensRoundTrip() {
        for (RespawnKind kind : RespawnKind.values()) {
            assertEquals(kind, RespawnKind.fromWireName(kind.wireName()));
        }
        assertThrows(IllegalArgumentException.class, () -> RespawnKind.fromWireName("pillow"));
    }

    @Test
    void dimensionTransitionRejectsSelfTransitions() {
        assertThrows(IllegalArgumentException.class,
                () -> new DimensionTransition(DimensionId.NETHER, DimensionId.NETHER, TransferCause.NETHER_PORTAL));
        DimensionTransition transition = new DimensionTransition(
                DimensionId.OVERWORLD, DimensionId.NETHER, TransferCause.NETHER_PORTAL);
        assertEquals(DimensionId.OVERWORLD, transition.from());
        assertEquals(TransferCause.NETHER_PORTAL, transition.cause());
    }
}
