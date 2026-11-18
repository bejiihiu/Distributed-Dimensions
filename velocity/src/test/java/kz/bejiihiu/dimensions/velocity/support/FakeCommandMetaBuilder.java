package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.mojang.brigadier.tree.CommandNode;

import java.util.ArrayList;
import java.util.List;

/** Fluent builder mirroring the production metaBuilder chain. */
public final class FakeCommandMetaBuilder implements CommandMeta.Builder {

    private final List<String> aliases = new ArrayList<>();
    private Object plugin;

    FakeCommandMetaBuilder(String alias) {
        this.aliases.add(alias);
    }

    @Override
    public CommandMeta.Builder aliases(String... aliases) {
        this.aliases.addAll(List.of(aliases));
        return this;
    }

    @Override
    public CommandMeta.Builder hint(CommandNode<CommandSource> node) {
        return this; // hints are irrelevant for the fake command store
    }

    @Override
    public CommandMeta.Builder plugin(Object plugin) {
        this.plugin = plugin;
        return this;
    }

    @Override
    public CommandMeta build() {
        return new FakeCommandMeta(aliases, plugin);
    }
}
