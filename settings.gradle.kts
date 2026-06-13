pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions missing JDKs (we pin the Java 25 toolchain in convention plugins).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "native"

// Modules are wired in as each milestone lands:
// include("libs:money")
// include("libs:events")
// include("libs:tenant")
// include("service-template")
// include("services:restaurant-service")
