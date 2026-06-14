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
    // 1.4.1+ bundles ASM 9.8, which understands Java 25 bytecode (class file major
    // version 69); 1.3.0's ASM 9.7.1 tops out at Java 24 and silently imports zero
    // classes from a Java 25 build, making every layered-architecture rule fail with
    // "failed to check any classes".
    "testImplementation"("com.tngtech.archunit:archunit-junit5:1.4.1")
}

// JaCoCo coverage gate (a ratchet): the floor is set from MEASURED coverage so it can only be
// raised, never regress. *Application, the cross-cutting config/ package, and DTO records are
// excluded from the denominator (low-logic glue, not behaviour worth a coverage number). The
// standards-doc TARGET is instruction >= 0.70 / branch >= 0.60 (0.85 for money/tenant/outbox);
// this initial floor reflects today's tree (tenant + restaurant-service are the lowest) and is
// raised as tests grow.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("test"))
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.65".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.25".toBigDecimal()
            }
        }
    }
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/*Application.*",
                        "**/config/**",
                        "**/*Config.*",
                        // Cross-cutting Spring wiring with no business logic, on a par with the
                        // config/ package and *Config above: the auto-configurations and the
                        // auto-RLS @Aspect promoted into libs/tenant + libs/security (#6/#12).
                        // They are exercised end to end by the RLS isolation / auto-RLS / ProblemDetail
                        // tests, but are bean-wiring glue, not behaviour worth a per-class coverage
                        // number — same rationale the existing exclusions apply.
                        "**/*AutoConfiguration.*",
                        "**/*Aspect.*",
                        "**/*Advice.*",
                        "**/*ExceptionHandler.*",
                        "**/*Request.*",
                        "**/*Response.*",
                        "**/*Command.*",
                        "**/*Result.*",
                    )
                }
            },
        ),
    )
}

// Formatting + Checkstyle + ArchUnit (a normal test) + the coverage floor block via `check`.
tasks.named("check") {
    dependsOn("spotlessCheck", "jacocoTestCoverageVerification")
}
