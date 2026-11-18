package kz.bejiihiu.dimensions.common.util;

import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.Velocity;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilTest {

    // SquaredDistance

    @Test
    void squaredDistance3DIsSquared() {
        Position a = new Position(0, 0, 0);
        Position b = new Position(3, 4, 12);
        assertEquals(169.0, SquaredDistance.between3D(a, b));
        assertEquals(169.0, SquaredDistance.between3D(0, 0, 0, 3, 4, 12));
    }

    @Test
    void squaredDistanceHorizontalIgnoresY() {
        assertEquals(25.0, SquaredDistance.betweenHorizontal(new Position(0, 999, 0), new Position(3, -64, 4)));
        assertEquals(25.0, SquaredDistance.betweenHorizontal(0, 0, 3, 4));
    }

    // PortalMathPrimitives

    @Test
    void yawNormalizationWrapsIntoPositiveRange() {
        assertEquals(0.0, PortalMathPrimitives.normalizeYaw(0));
        assertEquals(90.0, PortalMathPrimitives.normalizeYaw(450));
        assertEquals(270.0, PortalMathPrimitives.normalizeYaw(-90));
        assertEquals(0.0, PortalMathPrimitives.normalizeYaw(720));
        assertThrows(IllegalArgumentException.class, () -> PortalMathPrimitives.normalizeYaw(Double.NaN));
    }

    @Test
    void quarterTurnRotationMatchesMinecraftYawConvention() {
        // yaw 0 faces +Z; one turn CW takes +Z to -X (yaw 90)
        Velocity south = new Velocity(0, 0, 1);
        assertEquals(new Velocity(-1, 0, 0), PortalMathPrimitives.rotateQuarterTurns(south, 1));
        assertEquals(new Velocity(0, 0, -1), PortalMathPrimitives.rotateQuarterTurns(south, 2));
        assertEquals(new Velocity(1, 0, 0), PortalMathPrimitives.rotateQuarterTurns(south, 3));
        assertEquals(south, PortalMathPrimitives.rotateQuarterTurns(south, 4));
        assertEquals(new Velocity(-1, 0, 0), PortalMathPrimitives.rotateQuarterTurns(south, -3));
        // vertical velocity is preserved verbatim
        Velocity falling = new Velocity(0, -0.5, 0);
        assertEquals(falling, PortalMathPrimitives.rotateQuarterTurns(falling, 2));
    }

    @Test
    void yawWithQuarterTurnsStaysInRange() {
        assertEquals(90.0, PortalMathPrimitives.yawWithQuarterTurns(0.0, 1));
        assertEquals(0.0, PortalMathPrimitives.yawWithQuarterTurns(270.0, 1));
        assertEquals(270.0, PortalMathPrimitives.yawWithQuarterTurns(0.0, -1));
    }

    @Test
    void quarterTurnsClockwiseSnapsToNearestQuarter() {
        assertEquals(0, PortalMathPrimitives.quarterTurnsClockwise(44.0, 0.0));
        assertEquals(1, PortalMathPrimitives.quarterTurnsClockwise(46.0, 0.0));
        assertEquals(1, PortalMathPrimitives.quarterTurnsClockwise(90.0, 0.0));
        assertEquals(3, PortalMathPrimitives.quarterTurnsClockwise(-90.0, 0.0));
        assertEquals(2, PortalMathPrimitives.quarterTurnsClockwise(180.0, 0.0));
    }

    // IdGenerator

    @Test
    void generatedIdsAreV4AndUnique() {
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(IdGenerator.newId());
        }
        assertEquals(10_000, ids.size());
        assertTrue(IdGenerator.looksLikeV4(IdGenerator.newId()));
        assertEquals(4, IdGenerator.newId().version());
    }

    // Clock

    @Test
    void fixedClockReturnsFrozenTime() {
        Instant frozen = Instant.ofEpochMilli(1_700_000_000_000L);
        Clock clock = Clock.fixed(frozen);
        assertEquals(frozen.toEpochMilli(), clock.currentTimeMillis());
        assertEquals(frozen, clock.now());
        assertEquals(frozen.toEpochMilli(), clock.currentTimeMillis());
    }

    @Test
    void systemClockMovesForward() {
        assertTrue(Clock.system().currentTimeMillis() > 1_600_000_000_000L);
    }

    // Result

    @Test
    void resultOkAndErrCarryPayloads() {
        Result<Integer, String> ok = Result.ok(42);
        assertTrue(ok.isOk());
        assertEquals(42, ok.orElse(-1));
        assertEquals(84, ok.map(i -> i * 2).orElse(-1));
        assertEquals(84, ok.flatMap(i -> Result.<Integer, String>ok(i * 2)).orElse(-1));
        assertEquals("got 42", ok.fold(i -> "got " + i, e -> "err " + e));

        Result<Integer, String> err = Result.err("kaput");
        assertTrue(err.isErr());
        assertEquals(-1, err.orElse(-1));
        assertEquals("err kaput", err.fold(i -> "got " + i, e -> "err " + e));
        assertEquals("kaput", ((Result.Err<Integer, String>) err).error());
        RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> err.orElseThrow(IllegalStateException::new));
        assertEquals("kaput", thrown.getMessage());
        // map/flatMap on Err pass the error through untouched
        assertEquals("kaput", ((Result.Err<Integer, String>) err.map(i -> i + 1)).error());
        assertEquals("kaput", ((Result.Err<Integer, String>) err.flatMap(i -> Result.<Integer, String>ok(1))).error());
    }

    @Test
    void okRejectsNullPayload() {
        assertThrows(NullPointerException.class, () -> Result.ok(null));
        assertThrows(NullPointerException.class, () -> Result.err(null));
    }
}
