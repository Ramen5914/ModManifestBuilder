import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

group = "games.ramen"
version = "1.0.0"

plugins {
    kotlin("jvm") version "2.4.10"
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    mavenCentral()

    maven {
        url = uri("https://maven.reposilite.com/releases")
    }

    maven {
        url = uri("https://maven.reposilite.com/snapshots")
    }
}

dependencies {
    compileOnly("com.reposilite:reposilite:3.1.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(11)
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<ShadowJar> {
    archiveFileName.set("${rootProject.name}-${project.version}-shadow.jar")
    destinationDirectory.set(file("$rootDir/build/libs/"))
    mergeServiceFiles()
}
