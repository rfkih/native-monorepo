// Enterprise quality gates, applied transitively to every module via
// native.java-conventions: code formatting (Spotless + google-java-format),
// semantic static analysis (Checkstyle), coverage reporting (JaCoCo), and the
// ArchUnit dependency that powers the layered-architecture tests.
// See docs/ENGINEERING-STANDARDS.md and docs/CODE-STRUCTURE.md.

plugins {
    jacoco
    checkstyle
    id("com.diffplug.spotless")
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.25.2")
        removeUnusedImports()
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = "10.21.1"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
    isIgnoreFailures = false
}

jacoco {
    // 0.8.15+ understands Java 25 bytecode (major version 69); 0.8.12 cannot instrument it.
    toolVersion = "0.8.15"
}

// Always produce a coverage report after tests (consumed by Sonar in CI).
tasks.withType<Test>().configureEach {
    finalizedBy(tasks.named("jacocoTestReport"))
}
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

dependencies {
    "testImplementation"("com.tngtech.archunit:archunit-junit5:1.3.0")
}

// Formatting + Checkstyle + ArchUnit (a normal test) are blocking via `check`.
// The JaCoCo coverage *threshold* gate is intentionally deferred and ratcheted in
// (floor = measured coverage) after the layered refactor lands — enabling a hard
// 70% gate on the existing tree blind would just break the build. See standards doc.
tasks.named("check") {
    dependsOn("spotlessCheck")
}
