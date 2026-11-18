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
        // Склеиваем META-INF/services всех либ (Configurate), иначе SPI провайдеры потеряются.
        mergeServiceFiles()
        // Без этого Shadow режет дубликаты service-файлов до того как их увидит
        // трансформер (ворнинг при сборке) - провайдеры Configurate теряются.
        // Фикс service file merging: дубликаты пропускаем внутрь, склейкой
        // занимается сам трансформер.
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

        // Всё своё тащим под своим пакетом, чтобы не конфликтовать с другими плагинами.
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

        dependencies {
            // Даёт прокси (Velocity 4 везёт Adventure 5): Adventure, slf4j и аннотации в jar не кладём.
            exclude(dependency("net.kyori:.*:.*"))
            exclude(dependency("org.slf4j:.*:.*"))
            exclude(dependency("org.jetbrains:annotations:.*"))
            // common тащит cloud-core как api-зависимость - прокси-модулю он не нужен
            // (админ-команды на нативном CommandManager), в jar не кладём.
            exclude(dependency("org.incendo:cloud-core:.*"))
            exclude(dependency("org.incendo:cloud-velocity:.*"))
            exclude(dependency("org.incendo:cloud-services:.*"))
        }
    }
}

// Версия проксируется в BuildConstants до компиляции, чтобы @Plugin видел константу.
val versionedSource = file("src/main/versioned")
val versionedDest = layout.buildDirectory.dir("generated/sources/versioned")
val generateVersionedSources = tasks.register<Copy>("generateVersionedSources") {
    // Комменты в исходнике только ASCII: expand() читает файл в кодировке платформы,
    // не-ASCII превращается в кракозябры независимо от filteringCharset.
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)

    from(versionedSource)
    into(versionedDest)
    expand(props)
}

sourceSets.main.configure { java.srcDir(generateVersionedSources.map { it.outputs }) }
