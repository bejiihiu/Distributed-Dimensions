package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.persistence.PortalIndex;

import java.util.Optional;

/**
 * Access to the shared portal index. Empty when the backend is degraded (the caller falls
 * back to a full world scan); the index is an optimization, never a source of truth.
 */
public interface NetherPortalStore {

    Optional<PortalIndex> index();
}
