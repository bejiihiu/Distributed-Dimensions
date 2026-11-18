package kz.bejiihiu.dimensions.velocity.transfer;

import kz.bejiihiu.dimensions.common.config.DimensionTarget;
import kz.bejiihiu.dimensions.common.config.DimensionsSettings;
import kz.bejiihiu.dimensions.common.model.TransferError;
import kz.bejiihiu.dimensions.common.protocol.DimensionTransferRequestMessage;
import com.velocitypowered.api.proxy.ServerConnection;

/**
 * Request validation for the transfer pipeline. Order of checks matches the wire contract:
 * cheapest, most-local facts first; nothing here mutates proxy state.
 *
 * <ol>
 *   <li>the player is online <em>on this exact connection</em> (a request that claims a
 *       connection the player is not using is never acted on);</li>
 *   <li>the claimed source server equals the server the requesting connection is
 *       registered as;</li>
 *   <li>the claimed target dimension is configured on this proxy, and its configured
 *       server name equals the claimed target server (server names come from the config,
 *       never from code);</li>
 *   <li>the claimed target server is registered in the proxy's server registry.</li>
 * </ol>
 *
 * The protocol version check happens at the codec boundary (the codec hard-fails on any
 * version other than 1) and is additionally reported from the plugin-message listener with
 * a best-effort request correlation; see the listener. {@code source_dimension} is carried
 * for correlation and logging only - the routing decision never depends on it, so it is
 * deliberately not cross-checked here.
 */
public final class TransferValidator {

    private final TransferPlatform platform;

    public TransferValidator(TransferPlatform platform) {
        this.platform = platform;
    }

    public ValidationOutcome validate(DimensionTransferRequestMessage message, ServerConnection source,
                                      DimensionsSettings dimensions) {
        // 1. player online on the exact requesting connection
        if (!platform.isOnConnection(message.playerUuid(), source)) {
            return ValidationOutcome.failed(TransferError.REJECTED,
                    "player " + message.playerUuid() + " is not connected through the requesting connection");
        }
        // 2. claimed source server == the requesting connection's registered server name
        String connectionServer = source.getServerInfo().getName();
        if (!connectionServer.equals(message.sourceServer())) {
            return ValidationOutcome.failed(TransferError.REJECTED,
                    "claimed source server '" + message.sourceServer()
                            + "' does not match the requesting connection's server '" + connectionServer + "'");
        }
        // 3. claimed target dimension is configured, and its server is the claimed target server
        DimensionTarget configured = findDimension(dimensions, message.targetDimension().key());
        if (configured == null) {
            return ValidationOutcome.failed(TransferError.REJECTED,
                    "target dimension '" + message.targetDimension().key() + "' is not configured on this proxy");
        }
        if (!configured.server().equals(message.targetServer())) {
            return ValidationOutcome.failed(TransferError.REJECTED,
                    "target dimension '" + configured.dimension() + "' is configured to server '" + configured.server()
                            + "', but the request claims target server '" + message.targetServer() + "'");
        }
        // 4. the target server exists in the proxy's registry
        if (platform.findServer(message.targetServer()).isEmpty()) {
            return ValidationOutcome.failed(TransferError.BACKEND_DOWN,
                    "target server '" + message.targetServer() + "' is configured but not registered in this proxy");
        }
        return ValidationOutcome.passed();
    }

    private static DimensionTarget findDimension(DimensionsSettings dimensions, String dimensionKey) {
        for (DimensionTarget target : new DimensionTarget[]{
                dimensions.overworld(), dimensions.nether(), dimensions.end()}) {
            if (target.dimension().equals(dimensionKey)) {
                return target;
            }
        }
        return null;
    }
}
