package kz.bejiihiu.dimensions.velocity;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferRequestMessage;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferResultMessage;
import kz.bejiihiu.dimensions.common.protocol.ProtocolCodec;
import kz.bejiihiu.dimensions.common.protocol.ProtocolMessage;
import kz.bejiihiu.dimensions.velocity.command.DdProxyCommand;
import kz.bejiihiu.dimensions.velocity.listener.DisconnectListener;
import kz.bejiihiu.dimensions.velocity.listener.PluginMessageListener;
import kz.bejiihiu.dimensions.velocity.support.Components;
import kz.bejiihiu.dimensions.velocity.support.FakeConsoleCommandSource;
import kz.bejiihiu.dimensions.velocity.support.FakeInvocation;
import kz.bejiihiu.dimensions.velocity.support.FakeProxyServer;
import kz.bejiihiu.dimensions.velocity.support.FakeServerConnection;
import kz.bejiihiu.dimensions.velocity.support.MutableEnvLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boot behavior end to end against a fake proxy runtime: when the configuration cannot be
 * loaded at all (here: the bundled {@code database.password: ${DB_PASSWORD}} with no
 * {@code DB_PASSWORD} in the environment), the plugin must still register its channel,
 * listeners and {@code /ddproxy} - in a DEGRADED mode where every transfer request is
 * refused with {@code UNAVAILABLE} naming the cause - and a later {@code /ddproxy reload}
 * with a fixed environment must fully re-enable routing with no restart.
 */
class DegradedBootTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("test");
    private static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.create("distributeddimensions", "transfer");
    private static final UUID PLAYER = UUID.randomUUID();

    @TempDir
    Path dataDirectory;

    private FakeProxyServer proxy;
    private MutableEnvLookup env;

    private DistributedDimensionsVelocityPlugin bootDegraded() {
        return boot(false);
    }

    private DistributedDimensionsVelocityPlugin boot(boolean withDatabasePassword) {
        proxy = new FakeProxyServer();
        env = new MutableEnvLookup();
        if (withDatabasePassword) {
            env.set("DB_PASSWORD", "s3cret");
        }
        DistributedDimensionsVelocityPlugin plugin =
                new DistributedDimensionsVelocityPlugin(LOGGER, proxy, dataDirectory, env);
        plugin.onProxyInitialize(new ProxyInitializeEvent());
        return plugin;
    }

    private DimensionTransferRequestMessage request(UUID requestId) {
        return new DimensionTransferRequestMessage(requestId, PLAYER, "overworld", "nether",
                DimensionId.OVERWORLD, DimensionId.of("minecraft:the_nether"),
                new Position(1.5, 64.0, -2.5, 90.0, 0.0), TransferCause.NETHER_PORTAL);
    }

    private PluginMessageListener listener() {
        return proxy.eventManager.listeners().stream()
                .filter(PluginMessageListener.class::isInstance)
                .map(PluginMessageListener.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("plugin-message listener must be registered"));
    }

    private DdProxyCommand adminCommand() {
        var registration = proxy.commandManager.find("ddproxy");
        assertNotNull(registration, "/ddproxy must be registered");
        assertInstanceOf(DdProxyCommand.class, registration.command());
        return (DdProxyCommand) registration.command();
    }

    private DimensionTransferResultMessage dispatch(FakeServerConnection source,
            DimensionTransferRequestMessage message) throws Exception {
        byte[] bytes = ProtocolCodec.encodeToBytes(message);
        listener().onPluginMessage(new PluginMessageEvent(source, source, CHANNEL, bytes));
        assertEquals(1, source.sent.size(), "exactly one result must be sent back");
        ProtocolMessage decoded = ProtocolCodec.decode(source.sent.get(source.sent.size() - 1).bytes());
        return (DimensionTransferResultMessage) decoded;
    }

    /**
     * A request sent by a backend connected to the "overworld" server; the player is not
     * online, so a healthy proxy must refuse it at validation step 1 (REJECTED), proving the
     * fail-closed gate is open.
     */
    private DimensionTransferResultMessage dispatchRequest(FakeServerConnection source) throws Exception {
        return dispatch(source, request(UUID.randomUUID()));
    }

    // ------------------------------------------------------------------ (a) degraded boot

    @Test
    void degradedBootStillRegistersChannelListenersAndCommand() {
        bootDegraded();

        assertTrue(proxy.channelRegistrar.registered.contains(CHANNEL), "channel must be registered");
        assertTrue(proxy.eventManager.listeners().stream().anyMatch(PluginMessageListener.class::isInstance),
                "plugin-message listener must be registered");
        assertTrue(proxy.eventManager.listeners().stream().anyMatch(DisconnectListener.class::isInstance),
                "disconnect listener must be registered");
        assertNotNull(proxy.commandManager.find("ddproxy"), "/ddproxy must be registered");
    }

    @Test
    void degradedBootRefusesEveryTransferWithUnavailableNamingTheCause() throws Exception {
        bootDegraded();
        FakeServerConnection source = new FakeServerConnection("overworld", 25_565);

        DimensionTransferResultMessage result = dispatchRequest(source);

        assertFalse(result.ok());
        assertEquals(TransferError.UNAVAILABLE, result.error());
        assertNotNull(result.message());
        assertTrue(result.message().contains("DB_PASSWORD"),
                "the backend must see the missing variable, got: " + result.message());
        assertFalse(result.message().contains("\n"), "the wire message must be a single line");
        assertEquals(0, proxy.getPlayerCalls,
                "the fail-closed gate must refuse before any player/server lookup happens");
    }

    @Test
    void degradedBootReloadWhileStillBrokenReportsFailureWithoutThrowing() {
        bootDegraded();

        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        adminCommand().execute(new FakeInvocation(console, "reload"));

        String out = Components.plain(console.messages);
        assertTrue(out.contains("Reload failed"), "reload of a still-broken config must report failure, got: " + out);
        assertTrue(out.contains("DB_PASSWORD"), "the reload report must name the missing variable, got: " + out);
    }

    // ------------------------------------------------------------------ (b) status output

    @Test
    void statusPrintsTheDegradedReasonAndTheDisabledRouting() {
        bootDegraded();

        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        adminCommand().execute(new FakeInvocation(console, "status"));

        String out = Components.plain(console.messages);
        assertTrue(out.contains("BROKEN"), "status must flag the broken configuration, got: " + out);
        assertTrue(out.contains("DB_PASSWORD"), "status must name the missing variable, got: " + out);
        assertTrue(out.contains("transfers are DISABLED"), "status must say routing is disabled, got: " + out);
        assertTrue(out.contains("dimensions: unavailable"), "status must not render the dimensions table, got: " + out);
    }

    // ------------------------------------------------------- (c) reload recovers routing

    @Test
    void reloadWithFixedEnvironmentFullyRecoversWithoutRestart() throws Exception {
        DistributedDimensionsVelocityPlugin plugin = bootDegraded();
        assertNotNull(plugin);
        DdProxyCommand command = adminCommand();

        // the report names the cause instead of throwing out of the command
        FakeConsoleCommandSource failed = new FakeConsoleCommandSource();
        command.execute(new FakeInvocation(failed, "reload"));
        assertTrue(Components.plain(failed.messages).contains("Reload failed"));

        // fixing the environment and reloading re-enables routing, live
        env.set("DB_PASSWORD", "s3cret");
        FakeConsoleCommandSource reloaded = new FakeConsoleCommandSource();
        command.execute(new FakeInvocation(reloaded, "reload"));
        assertTrue(Components.plain(reloaded.messages).contains("Configuration reloaded."),
                "a clean load must report success, got: " + Components.plain(reloaded.messages));

        FakeConsoleCommandSource status = new FakeConsoleCommandSource();
        command.execute(new FakeInvocation(status, "status"));
        String out = Components.plain(status.messages);
        assertTrue(out.contains("config: healthy"), "status must report healthy after reload, got: " + out);

        // the fail-closed gate is open again: validation (not the gate) refuses the request
        FakeServerConnection source = new FakeServerConnection("overworld", 25_565);
        DimensionTransferResultMessage result = dispatchRequest(source);
        assertEquals(TransferError.REJECTED, result.error(),
                "routing must reach the validation pipeline after a clean reload");
        assertNotEquals(TransferError.UNAVAILABLE, result.error());
        assertEquals(1, proxy.getPlayerCalls, "the validation pipeline must have consulted the player registry");
    }

    // ------------------------------------------------------------------ (d) happy path

    @Test
    void happyPathBootsHealthyAndRoutesPastTheGate() throws Exception {
        boot(true);

        FakeConsoleCommandSource console = new FakeConsoleCommandSource();
        adminCommand().execute(new FakeInvocation(console, "status"));
        String out = Components.plain(console.messages);
        assertTrue(out.contains("config: healthy"), "a loadable configuration must report healthy, got: " + out);

        FakeServerConnection source = new FakeServerConnection("overworld", 25_565);
        DimensionTransferResultMessage result = dispatchRequest(source);
        assertEquals(TransferError.REJECTED, result.error(),
                "a healthy proxy must refuse at validation step 1 (player offline), not at the gate");
        assertEquals(1, proxy.getPlayerCalls);
    }
}
