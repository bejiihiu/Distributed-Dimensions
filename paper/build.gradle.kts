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
    // Cloud для paper бандлится: платформы его не предоставляют.
    implementation(libs.cloud.paper)
    // Public HuskSync API for the compile-time listener; runtime access is
    // linkage-guarded and only used when HuskSync is actually installed.
    compileOnly(libs.husksync.bukkit)

    // Фикстуры (FakeSchedulers, fake gateway, мировые прокси) публикуются через
    // testFixtures, чтобы поздние волны могли гонять логику порталов/сущностей
    // без поднятого сервера. Фикстуры тоже компилируются против paper-api.
    testFixturesImplementation(libs.paper.api)

    testImplementation(testFixtures(project(":paper")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    // Тесты трогают bukkit-интерфейсы (World, прокси) — нужен paper-api на classpath.
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
        // filteringCharset выставлен, но на expand() он не влияет (проверено:
        // кириллица всё равно бьётся). Правило простое: через expand гоняем
        // только ASCII (версию), весь не-ASCII лежит в yml литералом.
        filteringCharset = "UTF-8"
        val props = mapOf("version" to version)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        archiveBaseName.set("distributed-dimensions-paper")
        // Склеиваем META-INF/services всех либ (Configurate, Cloud), иначе SPI провайдеры потеряются.
        mergeServiceFiles()
        // Без этого Shadow 9.x режет дубликаты service-файлов до того как их увидит
        // трансформер (ворнинг при сборке) — провайдеры Configurate/Cloud теряются.
        // Фикс service file merging: дубликаты пропускаем внутрь, склейкой
        // занимается сам трансформер.
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        // Всё своё тащим под своим пакетом, чтобы не конфликтовать с другими плагинами.
        relocate("org.spongepowered.configurate", "kz.bejiihiu.dimensions.libs.configurate")
        relocate("org.yaml.snakeyaml", "kz.bejiihiu.dimensions.libs.snakeyaml")
        relocate("io.leangen.geantyref", "kz.bejiihiu.dimensions.libs.geantyref")
        relocate("org.incendo.cloud", "kz.bejiihiu.dimensions.libs.cloud")
        relocate("com.google.gson", "kz.bejiihiu.dimensions.libs.gson")
        relocate("com.zaxxer.hikari", "kz.bejiihiu.dimensions.libs.hikari")
        relocate("com.mysql", "kz.bejiihiu.dimensions.libs.mysql")
        relocate("org.mariadb", "kz.bejiihiu.dimensions.libs.mariadb")
        relocate("org.postgresql", "kz.bejiihiu.dimensions.libs.postgresql")
        relocate("com.mongodb", "kz.bejiihiu.dimensions.libs.mongodb")
        relocate("org.bson", "kz.bejiihiu.dimensions.libs.bson")
        dependencies {
            // Даёт сервер: Adventure, slf4j и аннотации в jar не кладём.
            exclude(dependency("net.kyori:.*:.*"))
            exclude(dependency("org.slf4j:.*:.*"))
            exclude(dependency("org.jetbrains:annotations:.*"))
        }
    }
}
