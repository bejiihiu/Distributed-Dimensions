package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.mojang.brigadier.tree.CommandNode;

import java.util.Collection;
import java.util.List;

/** Minimal built {@link CommandMeta} for the fake command manager. */
public final class FakeCommandMeta implements CommandMeta {

    private final Collection<String> aliases;
    private final Object plugin;

    FakeCommandMeta(Collection<String> aliases, Object plugin) {
        this.aliases = List.copyOf(aliases);
        this.plugin = plugin;
    }

    @Override
    public Collection<String> getAliases() {
        return aliases;
    }

    @Override
    public Collection<CommandNode<CommandSource>> getHints() {
        return List.of();
    }

    @Override
    public Object getPlugin() {
        return plugin;
    }
}
