package kz.bejiihiu.dimensions.common.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalDescriptorTest {

    private static final UUID PORTAL_ID = UUID.randomUUID();

    private static PortalDescriptor xAxisPortal() {
        return new PortalDescriptor(
                PORTAL_ID,
                DimensionId.NETHER,
                PortalOrientation.X_AXIS,
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
                        new BlockPos(0, 65, 0), new BlockPos(1, 65, 0),
                        new BlockPos(0, 66, 0), new BlockPos(1, 66, 0)),
                new BlockPos(-1, 63, 0),
                new Position(0.5, 64, 0.5));
    }

    @Test
    void innerBoundsAreDerivedFromBlocks() {
        PortalDescriptor portal = xAxisPortal();
        assertEquals(new BlockPos(0, 64, 0), portal.innerMin());
        assertEquals(new BlockPos(1, 66, 0), portal.innerMax());
    }

    @Test
    void blocksAreCanonicalizedBySortingAndDedup() {
        PortalDescriptor unsorted = new PortalDescriptor(
                PORTAL_ID, DimensionId.NETHER, PortalOrientation.X_AXIS,
                List.of(new BlockPos(1, 64, 0), new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
                        new BlockPos(0, 65, 0)),
                new BlockPos(0, 63, 0), new Position(0.5, 64, 0.5));
        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(0, 65, 0), new BlockPos(1, 64, 0)),
                unsorted.blocks());
    }

    @Test
    void emptyBlockListIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PortalDescriptor(
                PORTAL_ID, DimensionId.NETHER, PortalOrientation.X_AXIS, List.of(),
                new BlockPos(0, 63, 0), new Position(0.5, 64, 0.5)));
    }

    @Test
    void blocksOffPlaneAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PortalDescriptor(
                PORTAL_ID, DimensionId.NETHER, PortalOrientation.X_AXIS,
                List.of(new BlockPos(0, 64, 0), new BlockPos(0, 64, 3)),
                new BlockPos(0, 63, 0), new Position(0.5, 64, 0.5)));
        // and the mirror case for Z_AXIS
        assertThrows(IllegalArgumentException.class, () -> new PortalDescriptor(
                PORTAL_ID, DimensionId.OVERWORLD, PortalOrientation.Z_AXIS,
                List.of(new BlockPos(0, 64, 0), new BlockPos(7, 64, 0)),
                new BlockPos(0, 63, 0), new Position(0.5, 64, 0.5)));
    }

    @Test
    void orientationWireRoundTrip() {
        for (PortalOrientation orientation : PortalOrientation.values()) {
            assertEquals(orientation, PortalOrientation.fromWireName(orientation.wireName()));
        }
        assertThrows(IllegalArgumentException.class, () -> PortalOrientation.fromWireName("diagonal"));
    }

    @Test
    void wireNamesAreStable() {
        assertEquals("x_axis", PortalOrientation.X_AXIS.wireName());
        assertEquals("z_axis", PortalOrientation.Z_AXIS.wireName());
        assertTrue(DimensionId.NETHER.toString().contains("nether"));
    }
}
