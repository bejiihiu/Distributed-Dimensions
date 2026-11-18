import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.velocity)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":common"))
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
    compileOnly(libs.annotations)
    // native admin commands - no command framework on this module
    // velocity-api must be on the test classpath too (tests construct velocity types directly);
    // compileOnly keeps it out of the shipped jar the same way.
    testImplementation(libs.velocity.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.slf4j.simple)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    runVelocity {
        velocityVersion(libs.versions.velocity.api.get())
    }

    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        archiveBaseName.set("distributed-dimensions-velocity")
        // Merge META-INF/services from all libs (Configurate), otherwise the SPI providers are lost.
        mergeServiceFiles()
        // Without this Shadow cuts duplicate service files before the transformer
        // sees them (build-time warning) - the Configurate providers are lost.
        // Service file merging fix: duplicates go inside, the transformer does the merging.
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

        // Drop JDBC-driver root-level junk (mysql/mariadb connector metadata) - anchored root
        // patterns only. admin/messages_*.properties, config.yml, our packages and
        // META-INF/services stay untouched.
        filesMatching("INFO_BIN") { exclude() }
        filesMatching("INFO_SRC") { exclude() }
        filesMatching("mariadb.properties") { exclude() }
        filesMatching("deprecated.properties") { exclude() }
        filesMatching("driver.properties") { exclude() }
        filesMatching("CONTRIBUTING.md") { exclude() }
        filesMatching("LICENSE") { exclude() }
        filesMatching("README") { exclude() }

        // Bundle everything of ours under our own package so it does not clash with other plugins.
        relocate("org.spongepowered.configurate", "kz.bejiihiu.dimensions.libs.configurate")
        relocate("org.yaml.snakeyaml", "kz.bejiihiu.dimensions.libs.snakeyaml")
        relocate("io.leangen.geantyref", "kz.bejiihiu.dimensions.libs.geantyref")
        relocate("com.google.gson", "kz.bejiihiu.dimensions.libs.gson")
        relocate("com.zaxxer.hikari", "kz.bejiihiu.dimensions.libs.hikari")
        relocate("com.mysql", "kz.bejiihiu.dimensions.libs.mysql")
        relocate("org.mariadb", "kz.bejiihiu.dimensions.libs.mariadb")
        relocate("org.postgresql", "kz.bejiihiu.dimensions.libs.postgresql")
        relocate("com.mongodb", "kz.bejiihiu.dimensions.libs.mongodb")
        relocate("org.bson", "kz.bejiihiu.dimensions.libs.bson")
        relocate("com.google.protobuf", "kz.bejiihiu.dimensions.libs.protobuf")
        relocate("org.checkerframework", "kz.bejiihiu.dimensions.libs.checkerframework")
        relocate("org.jspecify", "kz.bejiihiu.dimensions.libs.jspecify")

        dependencies {
            // Provided by the proxy (Velocity 4 ships Adventure 5): cut Adventure, slf4j and annotations from the jar.
            exclude(dependency("net.kyori:.*:.*"))
            exclude(dependency("org.slf4j:.*:.*"))
            exclude(dependency("org.jetbrains:annotations:.*"))
        }
    }
}

// The version is proxied into BuildConstants before compilation so @Plugin sees a constant.
val versionedSource = file("src/main/versioned")
val versionedDest = layout.buildDirectory.dir("generated/sources/versioned")
val generateVersionedSources = tasks.register<Copy>("generateVersionedSources") {
    // Comments in the source file are ASCII-only: expand() reads the file in the
    // platform encoding, non-ASCII breaks regardless of filteringCharset.
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)

    from(versionedSource)
    into(versionedDest)
    expand(props)
}

sourceSets.main.configure { java.srcDir(generateVersionedSources.map { it.outputs }) }
