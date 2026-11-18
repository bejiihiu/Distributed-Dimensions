package kz.bejiihiu.dimensions.common.model;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A request to move a player from one backend+dimension to another, via the proxy.
 *
 * <p>Server names must match {@code [A-Za-z0-9_-]{1,64}} (Velocity's server name grammar, kept
 * lenient). The source and target must differ — a transfer always crosses a server boundary.
 * Dimensions must also differ; in-place "dimension changes" do not exist in this system.
 *
 * <p>{@code requestId} is the idempotency key for the whole operation: the proxy, the source
 * backend, and the target backend all key their dedup state on it.
 */
public record TransferRequest(
        UUID requestId,
        UUID playerUuid,
        String sourceServer,
        String targetServer,
        DimensionId sourceDimension,
        DimensionId targetDimension,
        Position targetPosition,
        TransferCause cause) {

    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public TransferRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(playerUuid, "playerUuid");
        sourceServer = validateServerName(sourceServer, "sourceServer");
        targetServer = validateServerName(targetServer, "targetServer");
        Objects.requireNonNull(sourceDimension, "sourceDimension");
        Objects.requireNonNull(targetDimension, "targetDimension");
        Objects.requireNonNull(targetPosition, "targetPosition");
        Objects.requireNonNull(cause, "cause");
        if (sourceServer.toLowerCase(Locale.ROOT).equals(targetServer.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("source and target server must differ, got '" + sourceServer + "'");
        }
        if (sourceDimension.equals(targetDimension)) {
            throw new IllegalArgumentException("source and target dimension must differ, got '" + sourceDimension + "'");
        }
    }

    private static String validateServerName(String name, String field) {
        Objects.requireNonNull(name, field);
        if (!SERVER_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    field + " must match [A-Za-z0-9_-]{1,64}, got '" + name + "'");
        }
        return name;
    }
}
