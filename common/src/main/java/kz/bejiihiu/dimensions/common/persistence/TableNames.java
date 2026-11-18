package kz.bejiihiu.dimensions.common.persistence;

import java.util.regex.Pattern;

/**
 * Table/index naming with a deployment-wide prefix. Prefix rules keep every generated
 * identifier well inside the 64-character MySQL/MariaDB limit and dodge quoting entirely.
 */
final class TableNames {

    // at most 31 chars: prefix + "_" + up to 32 more = fits MySQL's 64-char identifier cap with margin
    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9_]{0,30}_?");

    private TableNames() {
    }

    /**
     * Validates the prefix and returns it normalized to end with exactly one underscore, so
     * both {@code "dd"} and {@code "dd_"} yield stable identifiers.
     */
    static String normalize(String prefix) {
        if (prefix == null || !PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException(
                    "table prefix must match [a-z][a-z0-9_]{0,30} with an optional trailing '_', got '" + prefix + "'");
        }
        return prefix.endsWith("_") ? prefix : prefix + "_";
    }

    /** {@code normalize("dd") + "transfer_operations"} → {@code dd_transfer_operations}. */
    static String table(String normalizedPrefix, String base) {
        return normalizedPrefix + base;
    }

    /** Same rule for index names. */
    static String index(String normalizedPrefix, String base) {
        return normalizedPrefix + base;
    }
}
