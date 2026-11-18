package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.util.ProxyVersion;
import net.kyori.adventure.text.Component;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Hand-written fake of the velocity {@link ProxyServer}. Wires the three services the boot
 * sequence uses (channel registrar, event manager, command manager) to fakes; player/server
 * lookups are programmable; everything else is absent by contract of this fixture.
 */
public final class FakeProxyServer implements ProxyServer {

    public final FakeChannelRegistrar channelRegistrar = new FakeChannelRegistrar();
    public final FakeEventManager eventManager = new FakeEventManager();
    public final FakeCommandManager commandManager = new FakeCommandManager();

    /** What {@code getPlayer} returns; empty by default (nobody is on the proxy). */
    public Optional<Player> playerResult = Optional.empty();
    /** What {@code getServer} returns; empty by default. */
    public Optional<RegisteredServer> serverResult = Optional.empty();
    /** Number of times a player lookup was issued - proves whether the routing gate even ran. */
    public int getPlayerCalls;

    @Override
    public void shutdown(Component reason) {
        throw new UnsupportedOperationException("shutdown is not supported by this fake");
    }

    @Override
    public void shutdown() {
        throw new UnsupportedOperationException("shutdown is not supported by this fake");
    }

    @Override
    public boolean isShuttingDown() {
        return false;
    }

    @Override
    public void closeListeners() {
        throw new UnsupportedOperationException("closeListeners is not supported by this fake");
    }

    @Override
    public Optional<Player> getPlayer(String username) {
        getPlayerCalls++;
        return playerResult;
    }

    @Override
    public Optional<Player> getPlayer(UUID uuid) {
        getPlayerCalls++;
        return playerResult;
    }

    @Override
    public Collection<Player> getAllPlayers() {
        return playerResult.map(List::of).orElse(List.of());
    }

    @Override
    public int getPlayerCount() {
        return playerResult.map(p -> 1).orElse(0);
    }

    @Override
    public Optional<RegisteredServer> getServer(String name) {
        return serverResult;
    }

    @Override
    public Collection<RegisteredServer> getAllServers() {
        return serverResult.map(List::of).orElse(List.of());
    }

    @Override
    public Collection<Player> matchPlayer(String partialName) {
        return getAllPlayers();
    }

    @Override
    public Collection<RegisteredServer> matchServer(String partialName) {
        return getAllServers();
    }

    @Override
    public RegisteredServer createRawRegisteredServer(ServerInfo server) {
        throw new UnsupportedOperationException("raw server creation is not supported by this fake");
    }

    @Override
    public RegisteredServer registerServer(ServerInfo server) {
        throw new UnsupportedOperationException("server registration is not supported by this fake");
    }

    @Override
    public void unregisterServer(ServerInfo server) {
        throw new UnsupportedOperationException("server unregistration is not supported by this fake");
    }

    @Override
    public ConsoleCommandSource getConsoleCommandSource() {
        throw new UnsupportedOperationException("console source is not supported by this fake");
    }

    @Override
    public PluginManager getPluginManager() {
        throw new UnsupportedOperationException("plugin manager is not supported by this fake");
    }

    @Override
    public EventManager getEventManager() {
        return eventManager;
    }

    @Override
    public CommandManager getCommandManager() {
        return commandManager;
    }

    @Override
    public Scheduler getScheduler() {
        throw new UnsupportedOperationException("scheduler is not supported by this fake");
    }

    @Override
    public ChannelRegistrar getChannelRegistrar() {
        return channelRegistrar;
    }

    @Override
    public InetSocketAddress getBoundAddress() {
        throw new UnsupportedOperationException("bound address is not supported by this fake");
    }

    @Override
    public ProxyConfig getConfiguration() {
        throw new UnsupportedOperationException("proxy configuration is not supported by this fake");
    }

    @Override
    public ProxyVersion getVersion() {
        throw new UnsupportedOperationException("proxy version is not supported by this fake");
    }

    @Override
    public ResourcePackInfo.Builder createResourcePackBuilder(String url) {
        throw new UnsupportedOperationException("resource packs are not supported by this fake");
    }
}
