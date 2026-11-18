package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.model.BlockPos;
import kz.bejiihiu.dimensions.common.nether.WorldAccess;

/**
 * {@link WorldAccess} implementation backed by the gathered {@link ColumnScan} bitsets.
 * Pure memory, no world access — the creation planner can run anywhere, including the
 * entity scheduler, after the gather phase produced this view.
 */
final class OfflineWorldAccess implements WorldAccess {

    private final ColumnScan scan;

    OfflineWorldAccess(ColumnScan scan) {
        this.scan = scan;
    }

    @Override
    public int bottomY() {
        return scan.bottomY();
    }

    @Override
    public int topY() {
        return scan.topY();
    }

    @Override
    public int logicalHeight() {
        return scan.logicalHeight();
    }

    @Override
    public int motionBlockingTopY(int x, int z) {
        return scan.motionBlockingTopY(x, z);
    }

    @Override
    public boolean isReplaceable(BlockPos pos) {
        return scan.isReplaceable(pos.x(), pos.y(), pos.z());
    }

    @Override
    public boolean isSolid(BlockPos pos) {
        return scan.isSolid(pos.x(), pos.y(), pos.z());
    }
}
