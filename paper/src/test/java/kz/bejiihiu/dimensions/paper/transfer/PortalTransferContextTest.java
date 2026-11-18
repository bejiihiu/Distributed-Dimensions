package kz.bejiihiu.dimensions.paper.transfer;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.PortalOrientation;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferContext;
import kz.bejiihiu.dimensions.common.nether.RelativePortalPosition;
import kz.bejiihiu.dimensions.common.nether.TransferSourceContext;
import kz.bejiihiu.dimensions.common.persistence.PersistenceException;
import kz.bejiihiu.dimensions.common.persistence.TransferContextStore;
import kz.bejiihiu.dimensions.paper.FakePlayers;
import kz.bejiihiu.dimensions.paper.TestWorlds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The portal-typed transfer path: the source portal context captured at the event location is
 * persisted next to the journal insert with the same operation id — the target side reads it
 * at arrival time (the wire request never reaches the target backend).
 */
class PortalTransferContextTest {

    @TempDir
    Path tempDir;

    private FakeTransferBackend backend;
    private PortalTransferService transfers;

    @BeforeEach
    void setUp() {
        backend = new FakeTransferBackend(FakeTransferBackend.loadDefaults(tempDir));
        transfers = new PortalTransferService(backend, java.time.Clock.fixed(
                Instant.ofEpochMilli(1_800_000_000_000L), java.time.ZoneOffset.UTC));
    }

    private TransferSourceContext sourceContext(PortalOrientation axis, double right, double up) {
        return new TransferSourceContext(axis, new RelativePortalPosition(right, up, 0.0), 0.6, 1.8);
    }

    private org.bukkit.entity.Player player() {
        var world = TestWorlds.create("world", org.bukkit.World.Environment.NORMAL);
        return FakePlayers.create(UUID.randomUUID(),
                new org.bukkit.Location(world, 100.0, 64.0, -200.0, 90.0f, 0.0f), true);
    }

    @Test
    void portalTransferPersistsTheSourceContextWithTheJournalOperationId() {
        org.bukkit.entity.Player player = player();
        TransferSourceContext ctx = sourceContext(PortalOrientation.Z_AXIS, 0.25, 0.75);

        PortalTransferService.Outcome outcome = transfers.transfer(player,
                DimensionId.OVERWORLD, DimensionId.NETHER, TransferCause.NETHER_PORTAL,
                new Position(12.0, 64.0, -25.0, 90.0, 0.0), ctx);

        UUID operationId = assertInstanceOf(PortalTransferService.Outcome.Started.class, outcome).operationId();
        TransferContext row = backend.contextStore().find(operationId).orElseThrow(
                () -> new AssertionError("no transfer context written for a portal-typed transfer"));
        assertEquals(operationId, row.operationId());
        assertEquals(player.getUniqueId(), row.playerUuid());
        assertEquals(PortalOrientation.Z_AXIS, row.sourceAxis());
        assertEquals(0.25, row.fx());
        assertEquals(0.75, row.fy());
        assertEquals(0.0, row.forward());
        assertEquals(0.6, row.entityWidth());
        assertEquals(1.8, row.entityHeight());
        // the journal row and the context row agree on the operation id by construction
        assertEquals(1, backend.journalFake().rows().size());
        assertEquals(operationId, backend.journalFake().rows().get(0).operationId());
    }

    @Test
    void transferWithoutSourceContextWritesNoRow() {
        org.bukkit.entity.Player player = player();
        transfers.transfer(player,
                DimensionId.OVERWORLD, DimensionId.NETHER, TransferCause.NETHER_PORTAL,
                new Position(12.0, 64.0, -25.0, 90.0, 0.0));
        assertTrue(backend.contextStore().find(backend.journalFake().rows().get(0).operationId())
                .isEmpty());
    }

    @Test
    void endStyleTransferWritesNoContextRow() {
        org.bukkit.entity.Player player = player();
        transfers.transfer(player,
                DimensionId.OVERWORLD, DimensionId.THE_END, TransferCause.END_PORTAL,
                new Position(0.0, 0.0, 0.0, 90.0, 0.0));
        UUID opId = backend.journalFake().rows().get(0).operationId();
        assertTrue(backend.contextStore().find(opId).isEmpty(),
                "non-portal-typed causes never carry a source context");
    }

    @Test
    void contextStoreFailureStillCompletesTheTransfer() {
        TransferContextStore failing = new TransferContextStore() {
            @Override
            public void put(TransferContext context) {
                throw new PersistenceException("forced context-store failure (test)", null);
            }

            @Override
            public Optional<TransferContext> find(UUID operationId) {
                return Optional.empty();
            }

            @Override
            public int delete(UUID operationId) {
                return 0;
            }

            @Override
            public int deleteOlderThan(Instant cutoff) {
                return 0;
            }
        };
        backend.setTransferContexts(failing);

        org.bukkit.entity.Player player = player();
        TransferSourceContext ctx = sourceContext(PortalOrientation.X_AXIS, 0.5, 0.5);
        PortalTransferService.Outcome outcome = transfers.transfer(player,
                DimensionId.OVERWORLD, DimensionId.NETHER, TransferCause.NETHER_PORTAL,
                new Position(12.0, 64.0, -25.0, 90.0, 0.0), ctx);

        assertInstanceOf(PortalTransferService.Outcome.Started.class, outcome,
                "a context-write failure must not abort the transfer");
        assertEquals(1, backend.journalFake().rows().size(), "the journal row stands on its own");
        assertTrue(backend.warningLog().stream().anyMatch(line -> line.contains("source portal context")),
                "the failure is surfaced as a warning");
    }
}
