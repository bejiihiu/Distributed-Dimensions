package kz.bejiihiu.dimensions.paper;

import java.util.regex.Pattern;

/**
 * Redacts credentials embedded in URLs before anything is logged. Covers the
 * {@code scheme://user[:password]@host} shape (JDBC URLs and Mongo URIs). Used on
 * error messages that may quote a connection string — secrets themselves are
 * never logged.
 */
public final class SecretsRedactor {

    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?<=://)[^/@\\s]+(:[^/@\\s]*)?@");

    private SecretsRedactor() {
    }

    /**
     * @param message raw text that might quote a URL; may be {@code null}
     * @return text with embedded URL credentials replaced by {@code ****@}; never {@code null}
     */
    public static String redact(String message) {
        if (message == null) {
            return "<unknown error>";
        }
        return URL_CREDENTIALS.matcher(message).replaceAll("****@");
    }
}
