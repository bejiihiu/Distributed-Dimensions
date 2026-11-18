package kz.bejiihiu.dimensions.velocity.transfer;

import kz.bejiihiu.dimensions.common.config.DimensionTarget;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferRequestMessage;
import kz.bejiihiu.dimensions.velocity.support.FakeServerConnection;
import kz.bejiihiu.dimensions.velocity.support.FakeTransferPlatform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The validation pipeline: a request must pass every check, in order, before any connection
 * attempt is issued. Failure reasons are asserted to stay stable for operators.
 */
class TransferValidatorTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private FakeTransferPlatform platform;
    private FakeServerConnection source;
    private TransferValidator validator;

    private static final DimensionsSettings DIMENSIONS = new DimensionsSettings(
            new DimensionTarget("overworld", "minecraft:overworld"),
            new DimensionTarget("nether", "minecraft:the_nether"),
            new DimensionTarget("end", "minecraft:the_end"));

    @BeforeEach
    void setUp() {
        platform = new FakeTransferPlatform();
        source = new FakeServerConnection("overworld", 25_651);
        platform.setCurrentConnection(PLAYER, source);
        platform.registerServer("nether");
        platform.registerServer("end");
        validator = new TransferValidator(platform);
    }

    private static DimensionTransferRequestMessage request(String sourceServer, String targetServer,
                                                          String targetDimension) {
        return new DimensionTransferRequestMessage(
                UUID.randomUUID(), PLAYER, sourceServer, targetServer,
                DimensionId.OVERWORLD,
                DimensionId.of(targetDimension),
                new Position(1.5, 64.0, -2.5, 90.0, 0.0),
                TransferCause.NETHER_PORTAL);
    }

    @Test
    void passesForAWellFormedConfiguredRequest() {
        ValidationOutcome outcome = validator.validate(
                request("overworld", "nether", "minecraft:the_nether"), source, DIMENSIONS);
        assertInstanceOf(ValidationOutcome.Passed.class, outcome);
    }

    @Test
    void passesForEndDimension() {
        ValidationOutcome outcome = validator.validate(
                request("overworld", "end", "minecraft:the_end"), source, DIMENSIONS);
        assertInstanceOf(ValidationOutcome.Passed.class, outcome);
    }

    @Test
    void rejectsWhenPlayerIsNotOnline() {
        FakeTransferPlatform offline = new FakeTransferPlatform();
        offline.registerServer("nether");
        TransferValidator v = new TransferValidator(offline);
        ValidationOutcome outcome = v.validate(
                request("overworld", "nether", "minecraft:the_nether"), source, DIMENSIONS);
        ValidationOutcome.Failed failed = assertInstanceOf(ValidationOutcome.Failed.class, outcome);
        assertEquals(TransferError.REJECTED, failed.error());
    }

    @Test
    void rejectsWhenPlayerIsOnlineButOnADifferentConnection() {
        FakeServerConnection elsewhere = new FakeServerConnection("overworld", 25_666);
        FakeTransferPlatform platform = new FakeTransferPlatform();
        platform.setCurrentConnection(PLAYER, elsewhere); // not *this* connection object
        platform.registerServer("nether");
        TransferValidator v = new TransferValidator(platform);
        ValidationOutcome outcome = v.validate(
                request("overworld", "nether", "minecraft:the_nether"), source, DIMENSIONS);
        ValidationOutcome.Failed failed = assertInstanceOf(ValidationOutcome.Failed.class, outcome);
        assertEquals(TransferError.REJECTED, failed.error());
        assertTrue(failed.reason().contains("not connected through"));
    }

    @Test
    void rejectsWhenClaimedSourceServerDoesNotMatchConnection() {
        ValidationOutcome outcome = validator.validate(
                request("nether", "end", "minecraft:the_end"), source, DIMENSIONS);
        ValidationOutcome.Failed failed = assertInstanceOf(ValidationOutcome.Failed.class, outcome);
        assertEquals(TransferError.REJECTED, failed.error());
        assertTrue(failed.reason().contains("does not match"));
    }

    @Test
    void rejectsWhenTargetDimensionIsNotConfigured() {
        ValidationOutcome outcome = validator.validate(
                request("overworld", "nether", "minecraft:custom_dimension"), source, DIMENSIONS);
        ValidationOutcome.Failed failed = assertInstanceOf(ValidationOutcome.Failed.class, outcome);
        assertEquals(TransferError.REJECTED, failed.error());
        assertTrue(failed.reason().contains("not configured"));
    }

    @Test
    void rejectsWhenConfiguredServerDiffersFromClaimedTarget() {
        FakeTransferPlatform platform = new FakeTransferPlatform();
        FakeServerConnection src = new FakeServerConnection("overworld", 25_671);
        platform.setCurrentConnection(PLAYER, src);
        platform.registerServer("nether-eu"); // configured server, different name than vanilla default
        TransferValidator v = new TransferValidator(platform);
        DimensionsSettings custom = new DimensionsSettings(
                new DimensionTarget("overworld", "minecraft:overworld"),
                new DimensionTarget("nether-eu", "minecraft:the_nether"),
                new DimensionTarget("end", "minecraft:the_end"));
        // request claims the *configured* server name: valid
        ValidationOutcome ok = v.validate(
                request("overworld", "nether-eu", "minecraft:the_nether"), src, custom);
        assertInstanceOf(ValidationOutcome.Passed.class, ok);
        // request claims a different name for the same dimension: rejected
        ValidationOutcome outcome = v.validate(
                request("overworld", "nether", "minecraft:the_nether"), src, custom);
        ValidationOutcome.Failed failed = assertInstanceOf(ValidationOutcome.Failed.class, outcome);
        assertEquals(TransferError.REJECTED, failed.error());
    }

    @Test
    void rejectsWhenTargetServerIsNotRegisteredInProxy() {
        FakeTransferPlatform platform = new FakeTransferPlatform();
        FakeServerConnection src = new FakeServerConnection("overworld", 25_672);
        platform.setCurrentConnection(PLAYER, src);
        // deliberately no registerServer("nether")
        TransferValidator v = new TransferValidator(platform);
        ValidationOutcome outcome = v.validate(
                request("overworld", "nether", "minecraft:the_nether"), src, DIMENSIONS);
        ValidationOutcome.Failed failed = assertInstanceOf(ValidationOutcome.Failed.class, outcome);
        assertEquals(TransferError.BACKEND_DOWN, failed.error());
        assertTrue(failed.reason().contains("not registered"));
    }

    @Test
    void serverNamesComeFromConfigNotFromConstants() {
        // a fully customized topology: no vanilla server names anywhere
        DimensionsSettings custom = new DimensionsSettings(
                new DimensionTarget("world-main", "minecraft:overworld"),
                new DimensionTarget("world-nether", "minecraft:the_nether"),
                new DimensionTarget("world-end", "minecraft:the_end"));
        FakeTransferPlatform platform = new FakeTransferPlatform();
        FakeServerConnection src = new FakeServerConnection("world-main", 25_673);
        platform.setCurrentConnection(PLAYER, src);
        platform.registerServer("world-nether");
        TransferValidator v = new TransferValidator(platform);
        ValidationOutcome outcome = v.validate(
                request("world-main", "world-nether", "minecraft:the_nether"), src, custom);
        assertInstanceOf(ValidationOutcome.Passed.class, outcome);
    }
}
