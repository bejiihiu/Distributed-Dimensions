package kz.bejiihiu.dimensions.common.persistence;

/**
 * Opened persistence for the plugin: the journal, the portal index, the respawn-point
 * store and the entity-transfer payload store, and ownership of the underlying resources.
 * Close it on plugin shutdown; nothing else may close the data source or client underneath.
 */
public final class DimensionPersistence implements AutoCloseable {

    private final TransferOperationJournal journal;
    private final PortalIndex portalIndex;
    private final RespawnPointStore respawnPoints;
    private final EntityPayloadStore entityPayloads;
    private final Runnable closer;

    DimensionPersistence(TransferOperationJournal journal, PortalIndex portalIndex,
            RespawnPointStore respawnPoints, EntityPayloadStore entityPayloads, Runnable closer) {
        this.journal = journal;
        this.portalIndex = portalIndex;
        this.respawnPoints = respawnPoints;
        this.entityPayloads = entityPayloads;
        this.closer = closer;
    }

    public TransferOperationJournal journal() {
        return journal;
    }

    public PortalIndex portalIndex() {
        return portalIndex;
    }

    public RespawnPointStore respawnPoints() {
        return respawnPoints;
    }

    public EntityPayloadStore entityPayloads() {
        return entityPayloads;
    }

    @Override
    public void close() {
        closer.run();
    }
}
