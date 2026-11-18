package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.PostOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Records object-registered listeners; event firing and handler registration are not supported. */
public final class FakeEventManager implements EventManager {

    /** One entry per {@code register(plugin, listener)} call, in call order. */
    public record Registration(Object plugin, Object listener) {
    }

    public final List<Registration> registrations = new ArrayList<>();

    /** The registered listeners, in registration order. */
    public List<Object> listeners() {
        return registrations.stream().map(Registration::listener).toList();
    }

    @Override
    public void register(Object plugin, Object listener) {
        registrations.add(new Registration(plugin, listener));
    }

    @Override
    public <E> void register(Object plugin, Class<E> eventClass, PostOrder postOrder,
            EventHandler<E> handler) {
        throw new UnsupportedOperationException("handler registration is not supported by this fake");
    }

    @Override
    public <E> void register(Object plugin, Class<E> eventClass, short postOrder,
            EventHandler<E> handler) {
        throw new UnsupportedOperationException("handler registration is not supported by this fake");
    }

    @Override
    public <E> CompletableFuture<E> fire(E event) {
        throw new UnsupportedOperationException("event firing is not supported by this fake");
    }

    @Override
    public void unregisterListeners(Object plugin) {
        registrations.removeIf(registration -> registration.plugin() == plugin);
    }

    @Override
    public void unregisterListener(Object plugin, Object listener) {
        registrations.removeIf(registration -> registration.plugin() == plugin && registration.listener() == listener);
    }

    @Override
    public <E> void unregister(Object plugin, EventHandler<E> handler) {
        throw new UnsupportedOperationException("handler deregistration is not supported by this fake");
    }
}
