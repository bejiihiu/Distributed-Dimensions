package kz.bejiihiu.dimensions.common.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.model.Position;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetherCoordinateConverterTest {

    private static final WorldBorderBounds OW = WorldBorderBounds.DEFAULT_OVERWORLD;
    private static final WorldBorderBounds NETH = WorldBorderBounds.DEFAULT_NETHER;

    @Test
    void referenceTableFromSpec() {
        // project spec reference table (8:1 scaling)
        assertNether(80, 80, 10, 10);
        assertNether(-80, -80, -10, -10);
        assertNether(-29, 81, -4, 10);
        assertNether(0, 0, 0, 0);
        assertNether(1000, -1000, 125, -125);
    }

    @Test
    void floorsTowardNegativeInfinity() {
        // -0.1 * 8 = -0.8 -> floor = -1 (a truncating cast would give 0)
        Position n = NetherCoordinateConverter.overworldToNether(new Position(-0.1, 64, 0), NETH);
        assertEquals(-1.0, n.x());
        // -3.999 * ... -> floor = -4? no: -3.999/8 = -0.4998 -> floor = -1
        Position n2 = NetherCoordinateConverter.overworldToNether(new Position(-31.9, 64, 0), NETH);
        assertEquals(-4.0, n2.x());
        // exactly on a boundary: -32 * 0.125 = -4.0 exactly -> floor is -4
        Position n3 = NetherCoordinateConverter.overworldToNether(new Position(-32.0, 64, 0), NETH);
        assertEquals(-4.0, n3.x());
        // positive boundary
        Position n4 = NetherCoordinateConverter.overworldToNether(new Position(32.0, 64, 0), NETH);
        assertEquals(4.0, n4.x());
        // 7.999 * 0.125 = 0.999875 -> floor = 0
        Position n5 = NetherCoordinateConverter.overworldToNether(new Position(7.999, 64, 0), NETH);
        assertEquals(0.0, n5.x());
        // 8.0 * 0.125 = 1.0 -> floor = 1
        Position n6 = NetherCoordinateConverter.overworldToNether(new Position(8.0, 64, 0), NETH);
        assertEquals(1.0, n6.x());
    }

    @Test
    void fractionalCoordinatesFloorCorrectly() {
        Position n = NetherCoordinateConverter.overworldToNether(new Position(10.7, 64.25, -20.3), NETH);
        assertEquals(Math.floor(10.7 * 0.125), n.x());
        assertEquals(Math.floor(-20.3 * 0.125), n.z());
        assertEquals(64.25, n.y());
    }

    @Test
    void yIsNeverScaled() {
        Position n = NetherCoordinateConverter.overworldToNether(new Position(100, -33.5, 100), NETH);
        assertEquals(-33.5, n.y());
        Position o = NetherCoordinateConverter.netherToOverworld(new Position(100, 127.75, 100), OW);
        assertEquals(127.75, o.y());
    }

    @Test
    void netherToOverworldMultipliesByEightWithoutFloor() {
        Position o = NetherCoordinateConverter.netherToOverworld(new Position(10, 64, 10), OW);
        assertEquals(80.0, o.x());
        assertEquals(80.0, o.z());
        // fractional nether coordinates keep their fraction (no floor in this direction)
        Position o2 = NetherCoordinateConverter.netherToOverworld(new Position(10.5, 64, 10.25), OW);
        assertEquals(84.0, o2.x());
        assertEquals(82.0, o2.z());
    }

    @Test
    void rotationIsPreserved() {
        Position n = NetherCoordinateConverter.overworldToNether(new Position(100, 64, 100, 137.5, -42.25), NETH);
        assertEquals(137.5, n.yaw());
        assertEquals(-42.25, n.pitch());
        Position o = NetherCoordinateConverter.netherToOverworld(new Position(10, 64, 10, 137.5, -42.25), OW);
        assertEquals(137.5, o.yaw());
        assertEquals(-42.25, o.pitch());
    }

    @Test
    void roundTripAcrossDirections() {
        Position source = new Position(960.0, 64, -1288.0, 90, 15);
        Position nether = NetherCoordinateConverter.overworldToNether(source, NETH);
        Position back = NetherCoordinateConverter.netherToOverworld(nether, OW);
        assertEquals(960.0, back.x());
        assertEquals(-1288.0, back.z());
        // lossy through the floor, which is vanilla behavior
    }

    @Test
    void overworldBeyondBorderClampsIntoNetherBorder() {
        // scaled 3_749_998.0 clamps into [west, east - 1.0] = [..., 3_749_997.125], then floors
        Position far = new Position(29_999_984.0, 70, -29_999_984.0);
        Position n = NetherCoordinateConverter.overworldToNether(far, NETH);
        assertEquals(3_749_997.0, n.x());
        assertEquals(-3_749_998.0, n.z());

        Position beyond = new Position(60_000_000.0, 70, 60_000_000.0);
        Position n2 = NetherCoordinateConverter.overworldToNether(beyond, NETH);
        assertEquals(3_749_997.0, n2.x());
        assertEquals(3_749_997.0, n2.z());
    }

    @Test
    void netherBeyondBorderClampsIntoOverworldBorder() {
        // scaled 799_999_992 clamps into [-29_999_985, 29_999_984] on both axes
        Position far = new Position(99_999_999.0, 70, -99_999_999.0);
        Position o = NetherCoordinateConverter.netherToOverworld(far, OW);
        assertEquals(29_999_984.0, o.x());
        assertEquals(-29_999_985.0, o.z());
    }

    @Test
    void inclusiveBoundsInteractionWithFloor() {
        // scaled value lands exactly on the high inclusive bound: clamp keeps it at bound - 1.0
        Position atEdge = new Position(29_999_992.0, 64, 0); // * 0.125 = 3_749_999.0 -> clamped to 3_749_997.0
        Position n = NetherCoordinateConverter.overworldToNether(atEdge, NETH);
        assertEquals(3_749_997.0, n.x());
    }

    @Test
    void customBorderBoundsClampIndependentlyPerAxis() {
        WorldBorderBounds small = new WorldBorderBounds(-10, -20, 30, 25);
        Position p = new Position(500, 64, -500);
        Position n = NetherCoordinateConverter.overworldToNether(p, small);
        // clamp each axis against the custom border, then floor
        assertEquals(Math.floor(small.clampX(500 * 0.125)), n.x());
        assertEquals(Math.floor(small.clampZ(-500 * 0.125)), n.z());
    }

    @Test
    void blockVariantFloorsYToo() {
        Position src = new Position(100, 64.9, 100);
        BlockPos block = NetherCoordinateConverter.overworldToNetherBlock(src, NETH);
        assertEquals(12, block.x());
        assertEquals(64, block.y());
        Position over = new Position(10.7, 64.9, 10.7);
        assertEquals(new BlockPos(85, 64, 85), NetherCoordinateConverter.netherToOverworldBlock(over, OW));
    }

    @Test
    void negativesSymmetric() {
        assertNether(-8, -8, -1, -1);
        assertNether(8, 8, 1, 1);
        assertNether(-9, 9, -2, 1);
        assertNether(0, -1, 0, -1);
    }

    @Test
    void invalidArgumentsRejected() {
        assertThrows(NullPointerException.class, () -> NetherCoordinateConverter.overworldToNether(null, NETH));
        assertThrows(NullPointerException.class, () -> NetherCoordinateConverter.overworldToNether(new Position(0, 64, 0), null));
        assertThrows(NullPointerException.class, () -> NetherCoordinateConverter.netherToOverworld(null, OW));
        assertThrows(NullPointerException.class, () -> NetherCoordinateConverter.netherToOverworld(new Position(0, 64, 0), null));
    }

    private void assertNether(double ox, double oz, double nx, double nz) {
        Position result = NetherCoordinateConverter.overworldToNether(new Position(ox, 100, oz), NETH);
        assertEquals(nx, result.x(), "nether x for ow " + ox);
        assertEquals(nz, result.z(), "nether z for ow " + oz);
        assertEquals(100, result.y(), "y must not be scaled");
    }
}
