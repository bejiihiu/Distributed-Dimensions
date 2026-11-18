package kz.bejiihiu.dimensions.common.config;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** What a backend does when a block edit is attempted inside a mirage world. */
public enum MirageBlockEdits {

    /** Reject the edit and log it. The only supported mode today. */
    CANCEL("cancel");

    private final String id;

    MirageBlockEdits(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static MirageBlockEdits parse(String raw) {
        if (raw != null) {
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            for (MirageBlockEdits value : values()) {
                if (value.id.equals(normalized)) {
                    return value;
                }
            }
        }
        throw new IllegalArgumentException(
                "unknown mirage.block-edits value '" + raw + "' — expected one of: "
                        + Stream.of(values()).map(MirageBlockEdits::id).collect(Collectors.joining(", ")));
    }
}
