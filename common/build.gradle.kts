repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}

dependencies {
    // Adventure goes through api: Component types leak into public signatures.
    // At runtime the platforms provide it; net.kyori is cut from the shade bundles.
    api(libs.adventure.api)
    api(libs.adventure.minimessage)

    // Configurate is bundled into each platform jar with relocation, transitively pulls snakeyaml.
    implementation(libs.configurate.yaml)

    // Wire protocol: gson everywhere, relocated along with the platform jars.
    implementation(libs.gson)

    // Persistence: HikariCP + JDBC drivers (mysql/mariadb/postgres) + the sync mongo driver.
    // All implementation: the drivers are bundled inside the jars, the admin must not carry extra files.
    implementation(libs.hikaricp)
    implementation(libs.mongodb.driver.sync)
    implementation(libs.mysql.connector)
    implementation(libs.mariadb.client)
    implementation(libs.postgresql)

    compileOnly(libs.slf4j.api)
    compileOnly(libs.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // The launcher is needed by the Gradle runner; junit-jupiter does not pull it in.
    testRuntimeOnly(libs.junit.launcher)
    // H2 for tests only: SQL execution (PostgreSQL-compatible mode) — not about dialects.
    testImplementation(libs.h2)
}
