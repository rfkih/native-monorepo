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
include("libs:contracts")
include("libs:events")
include("libs:tenant")
include("libs:security")
include("libs:entitlement-check")
include("libs:observability")
include("libs:error-inbox")
include("service-template")
// Further modules are wired in as each milestone lands:
include("services:gateway")
include("services:org-service")
include("services:restaurant-service")
include("services:carwash-service")
include("services:barbershop-service")
include("services:finance-service")
include("services:entitlement-service")
include("services:employee-service")
include("services:notification-service")
