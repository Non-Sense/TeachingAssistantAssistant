import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.10"
    kotlin("plugin.serialization") version "1.4.20"
    id("com.github.johnrengelman.shadow") version "7.1.0"
    application
}

group = "com.n0n5ense"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
    implementation("io.arrow-kt:arrow-core:1.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("com.charleskorn.kaml:kaml:0.48.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.value("MainKt")
}


tasks.test {
    useJUnit()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "1.8"
}

val jar by tasks.getting(Jar::class) {
    manifest {
        attributes["Main-Class"] = "MainKt"
        attributes["Multi-Release"] = true
    }
}