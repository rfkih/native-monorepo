// Root build: aggregates lifecycle tasks (build/clean) across modules.
// Per-module configuration comes from the convention plugins in build-logic;
// there is intentionally no `allprojects`/`subprojects` block (Gradle anti-pattern).
plugins {
    base
}
