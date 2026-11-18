package kz.bejiihiu.dimensions.velocity.support;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;

import java.util.Optional;

/** Hand-written fake of the connection-result contract for unit tests. */
public final class FakeResult implements ConnectionRequestBuilder.Result {

    private final ConnectionRequestBuilder.Status status;
    private final RegisteredServer attempted;

    public FakeResult(ConnectionRequestBuilder.Status status) {
        this(status, new FakeRegisteredServer("attempted", 25_999));
    }

    public FakeResult(ConnectionRequestBuilder.Status status, RegisteredServer attempted) {
        this.status = status;
        this.attempted = attempted;
    }

    @Override
    public RegisteredServer getAttemptedConnection() {
        return attempted;
    }

    @Override
    public Optional<Component> getReasonComponent() {
        return Optional.empty();
    }

    @Override
    public ConnectionRequestBuilder.Status getStatus() {
        return status;
    }
}
