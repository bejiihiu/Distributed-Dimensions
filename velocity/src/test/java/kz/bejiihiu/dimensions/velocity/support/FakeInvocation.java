package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;

/** Programmable invocation for command tests. */
public record FakeInvocation(CommandSource source, String... arguments) implements SimpleCommand.Invocation {

    @Override
    public String alias() {
        return "ddproxy";
    }

    @Override
    public CommandSource source() {
        return source;
    }

    @Override
    public String[] arguments() {
        return arguments.clone();
    }
}
