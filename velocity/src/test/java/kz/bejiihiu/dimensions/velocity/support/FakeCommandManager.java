package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.mojang.brigadier.suggestion.Suggestions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Records command registrations; execution goes through the stored {@link Command} directly. */
public final class FakeCommandManager implements CommandManager {

    /** One registration performed through {@code register(meta, command)}. */
    public record Registration(CommandMeta meta, Command command) {

        public String alias() {
            return meta.getAliases().iterator().next();
        }
    }

    public final List<Registration> registrations = new ArrayList<>();

    /** Finds the registered command for {@code alias}, or null when absent. */
    public Registration find(String alias) {
        for (Registration registration : registrations) {
            if (registration.meta().getAliases().contains(alias)) {
                return registration;
            }
        }
        return null;
    }

    @Override
    public CommandMeta.Builder metaBuilder(String alias) {
        return new FakeCommandMetaBuilder(alias);
    }

    @Override
    public CommandMeta.Builder metaBuilder(BrigadierCommand command) {
        throw new UnsupportedOperationException("brigadier commands are not supported by this fake");
    }

    @Override
    @Deprecated
    public void register(BrigadierCommand command) {
        throw new UnsupportedOperationException("brigadier commands are not supported by this fake");
    }

    @Override
    public void register(CommandMeta meta, Command command) {
        registrations.add(new Registration(meta, command));
    }

    @Override
    public void unregister(String alias) {
        registrations.removeIf(registration -> registration.meta().getAliases().contains(alias));
    }

    @Override
    public void unregister(CommandMeta meta) {
        registrations.removeIf(registration -> registration.meta() == meta);
    }

    @Override
    public CommandMeta getCommandMeta(String alias) {
        Registration registration = find(alias);
        return registration == null ? null : registration.meta();
    }

    @Override
    public CompletableFuture<Boolean> executeAsync(CommandSource source, String cmdLine) {
        throw new UnsupportedOperationException("async execution is not supported by this fake");
    }

    @Override
    public CompletableFuture<Boolean> executeImmediatelyAsync(CommandSource source, String cmdLine) {
        throw new UnsupportedOperationException("async execution is not supported by this fake");
    }

    @Override
    public CompletableFuture<List<String>> offerSuggestions(CommandSource source, String cmdLine) {
        throw new UnsupportedOperationException("suggestions are not supported by this fake");
    }

    @Override
    public CompletableFuture<Suggestions> offerBrigadierSuggestions(CommandSource source, String cmdLine) {
        throw new UnsupportedOperationException("suggestions are not supported by this fake");
    }

    @Override
    public Collection<String> getAliases() {
        return registrations.stream().flatMap(registration -> registration.meta().getAliases().stream()).toList();
    }

    @Override
    public boolean hasCommand(String alias) {
        return find(alias) != null;
    }

    @Override
    public boolean hasCommand(String alias, CommandSource source) {
        return hasCommand(alias);
    }
}
