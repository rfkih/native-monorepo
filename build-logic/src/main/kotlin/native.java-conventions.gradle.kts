// Base Java conventions shared by every Native module (libs + services).
// Pins the toolchain to Java 25 (auto-provisioned via the foojay resolver in the
// root settings) so the build is reproducible regardless of the host JDK.

plugins {
    `java-library`
}

group = "id.co.nativeapp"  // 'native' is a Java reserved word, cannot be a package segment
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Repositories are declared centrally in the root settings.gradle.kts
// (dependencyResolutionManagement), not per-module.

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
