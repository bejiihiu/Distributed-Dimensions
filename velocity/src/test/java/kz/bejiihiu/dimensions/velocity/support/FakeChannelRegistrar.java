package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;

import java.util.ArrayList;
import java.util.List;

/** Records registered channels; the rest of the registrar contract is not exercised by tests. */
public final class FakeChannelRegistrar implements ChannelRegistrar {

    public final List<ChannelIdentifier> registered = new ArrayList<>();

    @Override
    public void register(ChannelIdentifier... identifiers) {
        registered.addAll(List.of(identifiers));
    }

    @Override
    public void unregister(ChannelIdentifier... identifiers) {
        registered.removeAll(List.of(identifiers));
    }
}
