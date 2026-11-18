package kz.bejiihiu.dimensions.interop;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Resolves this backend's server name as registered on the proxy.
 *
 * <p>The proxy validates that {@code source_server} in an outbound transfer request equals
 * the server the requesting connection is registered as, so the backend must know its own
 * name. Nothing in the plugin configuration declares it today (the configuration records are
 * frozen for this release), so it is resolved in order:
 *
 * <ol>
 *   <li>JVM system property {@value #SYSTEM_PROPERTY} (e.g. {@code -Ddimensions.source-server=overworld})</li>
 *   <li>environment variable {@value #ENV_VAR} (e.g. for containers)</li>
 * </ol>
 *
 * <p>The value must match the proxy's server-name grammar ({@code [A-Za-z0-9_-]{1,64}}). An
 * absent or malformed value resolves to {@code null} and transfers fail closed with
 * {@code TransferError.UNAVAILABLE} and a clear log message — the proxy would reject the
 * request anyway. A proper configuration key belongs to the wiring wave that lifts the
 * configuration freeze.
 */
public final class SourceServerName {

    /** JVM system property carrying the backend's server name. */
    public static final String SYSTEM_PROPERTY = "dimensions.source-server";
    /** Environment variable carrying the backend's server name. */
    public static final String ENV_VAR = "DD_SOURCE_SERVER";

    private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private SourceServerName() {
    }

    /** @return the server name, or {@code null} when unset or malformed */
    public static @Nullable String resolve() {
        String fromProperty = normalize(System.getProperty(SYSTEM_PROPERTY));
        if (fromProperty != null) {
            return fromProperty;
        }
        return normalize(System.getenv(ENV_VAR));
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
