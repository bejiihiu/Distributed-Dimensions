package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written fake of a player command source. The permission value is programmable;
 * only the surface the admin command uses is implemented.
 */
public final class FakeCommandSource implements CommandSource {

    public final List<Component> messages = new ArrayList<>();
    private final Tristate permission;

    public FakeCommandSource(Tristate permission) {
        this.permission = permission;
    }

    @Override
    public Tristate getPermissionValue(String permission) {
        return this.permission;
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
