// Base Java conventions shared by every Native module (libs + services).
// Pins the toolchain to Java 25 (auto-provisioned via the foojay resolver in the
// root settings) so the build is reproducible regardless of the host JDK.

plugins {
    `java-library`
    // Enterprise quality gates (Spotless/Checkstyle/JaCoCo/ArchUnit) for every module.
    id("native.quality")
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

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Gradle's default test-executor heap is 512m. Spring caches up to 32 @SpringBootTest
    // contexts per executor, and the big suites (finance 840+, restaurant 820+ tests) grew to the
    // cliff edge — finance OOM'd ("Java heap space") on 2026-09-03 when one more context landed.
    // 1g = 2x headroom fleet-wide; kept modest so CI's parallel per-module executors still fit
    // the runner. The connection-slot analogue of this same creep was fixed in the test bases
    // (max_connections=500) — when THIS limit is hit next, raise it here, not per-module.
    maxHeapSize = "1g"
}
