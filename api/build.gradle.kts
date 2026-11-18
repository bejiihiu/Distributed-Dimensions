plugins {
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Independent semver for the published api artifact, decoupled from the core plugin version.
// Override with -PapiVersion=X.Y.Z when cutting an api release.
version = (findProperty("apiVersion") as String?) ?: "1.0.0"

base {
    archivesName.set("distributed-dimensions-api")
}

dependencies {
    // Events must extend org.bukkit.event.Event and players are org.bukkit.entity.Player,
    // so the api compiles against paper-api. compileOnly on purpose: consumers already run
    // on Paper/Folia; the published pom must not drag server api onto anyone's classpath.
    // Deliberately NOT project(":common") — common pulls hikari/mongodb/configurate transitively.
    compileOnly(libs.paper.api)
    compileOnly(libs.annotations)

    testImplementation(libs.paper.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach {
    // paper-api on the classpath so javadoc resolves org.bukkit.* types.
    classpath = sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:none", "-quiet")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "distributed-dimensions-api"

            pom {
                name.set("distributed-dimensions-api")
                description.set("Public api for the Distributed Dimensions plugin: events and services for observing and triggering cross-server dimension transfers.")
                url.set("https://github.com/bejiihiu/Distributed-Dimensions")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/bejiihiu/Distributed-Dimensions/blob/master/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("bejiihiu")
                        name.set("bejiihiu")
                    }
                }
                scm {
                    url.set("https://github.com/bejiihiu/Distributed-Dimensions")
                    connection.set("scm:git:git://github.com/bejiihiu/Distributed-Dimensions.git")
                    developerConnection.set("scm:git:ssh://git@github.com/bejiihiu/Distributed-Dimensions.git")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/bejiihiu/Distributed-Dimensions")
            credentials {
                // set GITHUB_ACTOR/GITHUB_TOKEN env vars when actually publishing;
                // never commit credentials. Publishing is a manual ritual, see docs/api.md.
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user")?.toString()
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key")?.toString()
            }
        }
    }
}
