pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions missing JDKs (we pin the Java 25 toolchain in convention plugins).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "native"

include("libs:money")
// Further modules are wired in as each milestone lands:
// include("libs:events")
// include("libs:tenant")
// include("service-template")
// include("services:restaurant-service")
