package kz.bejiihiu.dimensions.common.config;

import java.time.Duration;

/**
 * Cross-server transfer policy. YAML carries the timeout as milliseconds;
 * the record keeps the standard {@link Duration} shape.
 */
public record TransferSettings(Duration timeout, int maxRetries) {

    public TransferSettings {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("transfer timeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
        }
    }
}
