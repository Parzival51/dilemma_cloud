
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)

    id("com.google.cloud.tools.jib") version "3.4.2"
}

group = "com.example"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    implementation("com.google.firebase:firebase-admin:9.3.0")
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.7.3")
    implementation("com.google.guava:guava:32.1.2-jre")
}


jib {
    from { image = "eclipse-temurin:21-jre" }

    to {
        image = "gcr.io/gunun-ikilemi/dilemma-backend"
        tags  = setOf("latest")

    }

    container {
        mainClass = "io.ktor.server.netty.EngineMain"
        ports     = listOf("8080")
        jvmFlags  = listOf("-Duser.timezone=UTC")
    }
}