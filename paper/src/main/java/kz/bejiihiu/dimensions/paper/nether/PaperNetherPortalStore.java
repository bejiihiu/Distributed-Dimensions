package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.common.persistence.PortalIndex;
import kz.bejiihiu.dimensions.paper.PaperServices;

import java.util.Optional;

/**
 * Production {@link NetherPortalStore}: resolves the index through the services holder. The
 * holder throws while the backend is degraded — that is the signal for "run degraded", never
 * an error to propagate.
 */
public final class PaperNetherPortalStore implements NetherPortalStore {

    @Override
    public Optional<PortalIndex> index() {
        if (!PaperServices.isAvailable()) {
            return Optional.empty();
        }
        try {
            return Optional.of(PaperServices.get().portalIndex());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
