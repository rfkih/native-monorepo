---
description: Run the Native per-service quality gate (format, compile, checkstyle, non-Docker tests).
argument-hint: <service-name e.g. finance-service | restaurant-service | all>
allowed-tools: Bash, Read, Edit
---

Run the Native per-service quality gate for **$ARGUMENTS**.

This is the gate every task must pass before it is "done" (CLAUDE.md "How to work" §4). Docker is
often unavailable locally, so Testcontainers / `@SpringBootTest` DB tests are **excluded** — say so
explicitly in your summary; they remain a pre-merge gate.

Steps (the module under `:services:` is `:services:<svc>`, e.g. `:services:finance-service`):

1. `./gradlew :services:<svc>:spotlessApply` — auto-format (google-java-format) so the format check passes.
2. `./gradlew :services:<svc>:compileJava :services:<svc>:compileTestJava :services:<svc>:spotlessCheck :services:<svc>:checkstyleMain :services:<svc>:checkstyleTest`
3. Non-DB tests: prefer a `--tests` selection of the service's ArchUnit suite
   (`*.config.LayeredArchitectureTest`) plus its unit, web-slice, and contract tests. If you run the
   full `:test` task and ONLY Testcontainers / `@SpringBootTest` tests fail, report that as a Docker
   gap — not a real failure.

If **$ARGUMENTS** is `all` or empty, run the cross-module gate in ONE invocation (a single Gradle
process avoids lock contention): `./gradlew spotlessCheck checkstyleMain checkstyleTest compileTestJava`.

Report: pass/fail per step, and the exact Testcontainers/DB test classes that still need real Postgres.
