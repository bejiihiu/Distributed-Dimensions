package kz.bejiihiu.dimensions.velocity;

// Version is injected before compilation by the generateVersionedSources task,
// so the @Plugin annotation sees a constant without hardcoding.
// NOTE: keep this file ASCII-only, expand() mangles non-ASCII (see velocity/build.gradle.kts).
public class BuildConstants {
    public static final String VERSION = "${version}";
}
