// Standard IntelliJ Platform build.
//
// NOTE: this path needs network access (Gradle distribution + the IntelliJ Platform
// Gradle Plugin). If you only want an installable jar on this machine, use build.ps1
// instead - it compiles against the local IDEA installation and needs no network.

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.commitai"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Uses the IDE already installed on this machine instead of downloading ~1 GB.
        // Swap for create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.3") to build anywhere.
        local("D:/app/idea/IntelliJ IDEA")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 21
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
        }
    }
}
