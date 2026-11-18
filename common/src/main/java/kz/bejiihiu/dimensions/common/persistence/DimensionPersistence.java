package kz.bejiihiu.dimensions.common.persistence;

/**
 * Opened persistence for the plugin: the journal plus the portal index, and ownership of the
 * underlying resources. Close it on plugin shutdown; nothing else may close the data source or
 * client underneath.
 */
public final class DimensionPersistence implements AutoCloseable {

    private final TransferOperationJournal journal;
    private final PortalIndex portalIndex;
    private final Runnable closer;

    DimensionPersistence(TransferOperationJournal journal, PortalIndex portalIndex, Runnable closer) {
        this.journal = journal;
        this.portalIndex = portalIndex;
        this.closer = closer;
    }

    public TransferOperationJournal journal() {
        return journal;
    }

    public PortalIndex portalIndex() {
        return portalIndex;
    }

    @Override
    public void close() {
        closer.run();
    }
}
