import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-test-fixtures`
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    // HuskSync public compileOnly API artifact; never bundled into the jar.
    maven("https://repo.william278.net/releases")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":api"))
    compileOnly(libs.paper.api)
    // Public HuskSync API for the compile-time listener; runtime access is
    // linkage-guarded and only used when HuskSync is actually installed.
    compileOnly(libs.husksync.bukkit)

    // Fixtures (FakeSchedulers, fake gateway, world proxies) are published through
    // testFixtures so later waves can drive portal/entity logic without a live server.
    // The fixtures compile against paper-api too.
    testFixturesImplementation(libs.paper.api)

    testImplementation(testFixtures(project(":paper")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    // Tests touch bukkit interfaces (World, proxies) — paper-api must be on the classpath.
    testImplementation(libs.paper.api)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G", "-Dcom.mojang.eula.agree=true")
    }

    processResources {
        // filteringCharset is set, but it does not affect expand() (verified:
        // Cyrillic still breaks). Rule of thumb: only ASCII (the version) goes through
        // expand; all non-ASCII lives in the yml as literals.
        filteringCharset = "UTF-8"
        val props = mapOf("version" to version)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        archiveBaseName.set("distributed-dimensions-paper")
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
            // Provided by the server: cut Adventure, slf4j and the annotations from the jar.
            exclude(dependency("net.kyori:.*:.*"))
            exclude(dependency("org.slf4j:.*:.*"))
            exclude(dependency("org.jetbrains:annotations:.*"))
        }
    }
}
