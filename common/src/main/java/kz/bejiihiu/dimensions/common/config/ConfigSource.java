package kz.bejiihiu.dimensions.common.config;

/** Where the effective configuration came from. */
public enum ConfigSource {

    /** The file on disk parsed and materialized (possibly with in-memory healing). */
    FILE,

    /** The file on disk is broken; the previously loaded snapshot is in effect. */
    LAST_GOOD,

    /** The file on disk is broken and nothing was loaded before; bundled defaults are in effect. */
    DEFAULTS_FALLBACK,
}
