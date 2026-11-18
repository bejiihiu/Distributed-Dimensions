repositories {
    mavenCentral()
}

dependencies {
    api(libs.adventure.api)
    compileOnly(libs.annotations)
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("distributed-dimensions-api")
}
