repositories {
    mavenCentral()
}

dependencies {
    api(project(":api"))

    // Adventure идёт через api: типы Component торчат в публичных сигнатурах.
    // В рантайме его дают сами платформы, из shade-бандлов группа net.kyori вырезается.
    api(libs.adventure.api)
    api(libs.adventure.minimessage)

    // Cloud-ядро идёт через api: платформенные менеджеры команд построены на нём.
    // Релокация в shade переписывает и наши ссылки тоже, так что рассинхрона не будет.
    api(libs.cloud.core)

    // Configurate бандлится в каждый платформенный jar с релокацией, транзитивно тянет snakeyaml.
    implementation(libs.configurate.yaml)

    compileOnly(libs.slf4j.api)
    compileOnly(libs.annotations)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // Лаунчер нужен Gradle раннеру, сам junit-jupiter его не тянет.
    testRuntimeOnly(libs.junit.launcher)
}
