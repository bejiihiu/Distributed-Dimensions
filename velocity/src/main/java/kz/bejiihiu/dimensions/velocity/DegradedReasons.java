package kz.bejiihiu.dimensions.velocity;

/**
 * Sanitizes free-form failure text for logs and wire messages: control characters and
 * whitespace runs collapse to single spaces and the result is length-capped. The common
 * configuration service never puts secret values into its messages; this only guards the
 * format so a failure reason stays a single, bounded line wherever it is rendered.
 */
public final class DegradedReasons {

    /** Maximum length of a sanitized reason; longer messages are trimmed with an ellipsis. */
    public static final int MAX_REASON_LENGTH = 240;

    private DegradedReasons() {
    }

    /** Normalizes {@code raw} to a single-line, length-capped string; returns "" for null. */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_REASON_LENGTH + 16));
        boolean pendingSpace = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c <= 0x20 || c == 0x7F || Character.isISOControl(c)) {
                pendingSpace = !out.isEmpty();
                continue;
            }
            if (pendingSpace) {
                out.append(' ');
            }
            pendingSpace = false;
            out.append(c);
            if (out.length() > MAX_REASON_LENGTH + 32) {
                break;
            }
        }
        String normalized = out.toString().trim();
        if (normalized.length() > MAX_REASON_LENGTH) {
            normalized = normalized.substring(0, MAX_REASON_LENGTH).trim() + "...";
        }
        return normalized;
    }
}
