package kz.bejiihiu.dimensions.common.config;

/**
 * The configuration file is unusable. Carries enough context to fix the problem:
 * a dotted key path for value errors, or the parser message for structural ones.
 *
 * <p>Never carries resolved secret values. When a {@code ${VAR}} reference cannot
 * be expanded, the message names the variable and the config key, nothing else.
 */
public class ConfigurationException extends RuntimeException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
