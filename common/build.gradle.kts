repositories {
    mavenCentral()
}

dependencies {
    // Adventure идёт через api: типы Component торчат в публичных сигнатурах.
    // В рантайме его дают сами платформы, из shade-бандлов группа net.kyori вырезается.
    api(libs.adventure.api)
    api(libs.adventure.minimessage)

    // Cloud-ядро идёт через api: платформенные менеджеры команд построены на нём.
    // Релокация в shade переписывает и наши ссылки тоже, так что рассинхрона не будет.
    api(libs.cloud.core)

    // Configurate бандлится в каждый платформенный jar с релокацией, транзитивно тянет snakeyaml.
    implementation(libs.configurate.yaml)

    // Wire-протокол: gson везде одинаковый, релоцируется вместе со сборкой platform-jar'ов.
    implementation(libs.gson)

    // Персистентность: HikariCP + JDBC-драйвера (mysql/mariadb/postgres) + sync mongo driver.
    // Всё это implementation: драйвера тащим внутрь jar'ов, админу ничего доносить не надо.
    implementation(libs.hikaricp)
    implementation(libs.mongodb.driver.sync)
    implementation(libs.mysql.connector)
    implementation(libs.mariadb.client)
    implementation(libs.postgresql)

    compileOnly(libs.slf4j.api)
    compileOnly(libs.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Лаунчер нужен Gradle раннеру, сам junit-jupiter его не тянет.
    testRuntimeOnly(libs.junit.launcher)
    // H2 только для тестов: выполнение SQL (PostgreSQL-совместимый режим) — не про диалекты.
    testImplementation(libs.h2)
}
