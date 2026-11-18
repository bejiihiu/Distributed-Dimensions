package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import com.velocitypowered.api.permission.Tristate;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written fake of the console command source. Permission value is always FALSE to prove
 * the console bypass logic does not consult permissions.
 */
public final class FakeConsoleCommandSource implements ConsoleCommandSource {

    public final List<Component> messages = new ArrayList<>();

    @Override
    public Tristate getPermissionValue(String permission) {
        return Tristate.FALSE;
    }

    @Override
    public Pointers pointers() {
        return Pointers.empty();
    }

    @Override
    public void sendMessage(Component message) {
        messages.add(message);
    }
}
