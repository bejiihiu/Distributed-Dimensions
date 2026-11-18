plugins {
    // Foojay resolver for toolchain JDKs: without the -convention plugin the resolver
    // is never registered and Gradle falls back to auto-provisioning (deprecated in 9.x,
    // an error in 10.x). The -convention variant is the supported one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "distributed-dimensions"

include("common", "api", "paper", "velocity")
