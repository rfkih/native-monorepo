# docs/generated — machine-readable project manifests (DO NOT EDIT BY HAND)

These files are **generated** from the single sources of truth and committed, so an AI agent or tool
can query the project's shape without parsing prose. Never hand-edit them — run the generator and
commit the result (a `verifyProjectDocs` gate fails the build if they drift).

- **`services.yaml`** — the deployable services: Gradle module, Flyway migration count + range, and
  whether the service handles events. Source: `settings.gradle.kts` + `services/*/src/main/resources/db/migration` + `build.gradle.kts`.
- **`events.yaml`** — the event registry: each event's name, namespace, schema path, and field list
  with Avro types. Source: `libs/contracts/src/main/resources/avro/*.avsc` (the single schema source —
  ADR 0003). Producer/consumer relationships are **not** here (brittle to derive from source); they
  live in `docs/EVENT-CATALOG.md`.

## Commands
- `./gradlew generateProjectDocs` — regenerate both files (run after adding a service, migration, or event).
- `./gradlew verifyProjectDocs` — fail if the committed files are stale, or an event schema has no
  `EVENT-CATALOG.md` entry (rule 7). Wired into `check`, so CI (`./gradlew build`) enforces it.
