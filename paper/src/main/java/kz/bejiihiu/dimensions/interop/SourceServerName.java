package kz.bejiihiu.dimensions.interop;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Resolves this backend's server name as registered on the proxy.
 *
 * <p>The proxy validates that {@code source_server} in an outbound transfer request equals
 * the server the requesting connection is registered as, so the backend must know its own
 * name. It is resolved in order (first non-blank wins):
 *
 * <ol>
 *   <li>configuration key {@code network.source-server} (non-blank), handed over by the caller</li>
 *   <li>environment variable {@value #ENV_VAR} (e.g. for containers)</li>
 *   <li>JVM system property {@value #SYSTEM_PROPERTY} (e.g. {@code -Ddimensions.source-server=overworld})</li>
 * </ol>
 *
 * <p>The value must match the proxy's server-name grammar ({@code [A-Za-z0-9_-]{1,64}}); a
 * source that is blank or malformed is skipped in favour of the next one. When no source
 * yields a valid name, resolution fails closed with {@code null} and transfers fail with
 * {@code TransferError.UNAVAILABLE} and a clear log message — the proxy would reject the
 * request anyway.
 */
public final class SourceServerName {

    /** JVM system property carrying the backend's server name. */
    public static final String SYSTEM_PROPERTY = "dimensions.source-server";
    /** Environment variable carrying the backend's server name. */
    public static final String ENV_VAR = "DD_SOURCE_SERVER";

    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private SourceServerName() {
    }

    /** @return the server name from the env var or system property, or {@code null} when unset or malformed */
    public static @Nullable String resolve() {
        return resolve(null);
    }

    /**
     * Resolves the server name with an explicitly configured value taking precedence.
     *
     * @param configuredSourceServer the raw {@code network.source-server} configuration value;
     *                              {@code null} or a blank string mean "not configured" and the
     *                              environment variable is consulted next
     * @return the server name, or {@code null} when no source yields a valid name
     */
    public static @Nullable String resolve(@Nullable String configuredSourceServer) {
        String fromConfig = normalize(configuredSourceServer);
        if (fromConfig != null) {
            return fromConfig;
        }
        String fromEnv = normalize(System.getenv(ENV_VAR));
        if (fromEnv != null) {
            return fromEnv;
        }
        return normalize(System.getProperty(SYSTEM_PROPERTY));
    }

    private static @Nullable String normalize(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!SERVER_NAME.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }
}
