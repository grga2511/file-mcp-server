plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.11.1")
    implementation("io.ktor:ktor-server-cio:3.3.3")

    implementation("org.apache.tika:tika-core:3.3.3")
    implementation("org.apache.tika:tika-parsers-standard-package:3.3.3")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("FileMcpServerKt")
}