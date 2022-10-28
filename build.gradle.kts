import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.10"
    kotlin("plugin.serialization") version "1.4.20"
    id("com.github.johnrengelman.shadow") version "7.1.0"
    application
}

group = "com.n0n5ense"
version = "1.3-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    val exposedVersion = "0.40.1"
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.39.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-cli:0.3.5")
    implementation("io.arrow-kt:arrow-core:1.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("com.charleskorn.kaml:kaml:0.49.0")
    implementation("org.apache.poi:poi:5.2.2")
    implementation("org.apache.poi:poi-ooxml:5.2.2")
    implementation("org.apache.logging.log4j:log4j-core:2.19.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.7.20")
}

application {
    mainClass.value("MainKt")
}


tasks.test {
    useJUnit()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

val jar by tasks.getting(Jar::class) {
    manifest {
        attributes["Main-Class"] = "MainKt"
        attributes["Multi-Release"] = true
    }
}