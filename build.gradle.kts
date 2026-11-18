// Shared conventions for all modules: Java, encoding, tests.
// Platform specifics (shade, runners) live in the module build files themselves.
import org.gradle.api.plugins.JavaPluginExtension

subprojects {
    apply(plugin = "java-library")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        // Single toolchain 25 for all modules: paper-api 26.x and velocity-api 4.x
        // are compiled for 25; an older javac cannot even read their class files.
        // Gradle fetches a missing JDK itself through the Foojay resolver (settings.gradle.kts).
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
        }
    }
}
