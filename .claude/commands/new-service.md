---
description: Scaffold a new deployable service by cloning service-template.
argument-hint: <service-name> (e.g. inventory-service)
allowed-tools: Read, Write, Edit, Bash, Grep, Glob
---

Create a new deployable Spring Boot service **$1** by cloning `service-template/` (the blueprint:
widget feature + the ArchUnit suite + the Auditable/RLS baseline + the Projection-layer +
`repositoryQueriesAreNative` guard).

Do:

1. Copy `service-template/` to `services/$1/`. Rename the package
   `id.co.nativeapp.servicetemplate` → `id.co.nativeapp.<service>`, where `<service>` is `$1` with
   the `-service` suffix dropped (match the existing convention — confirm against `docs/PROJECT-MAP.md`).
2. Register the module in `settings.gradle.kts`: `include("services:$1")` in the services block.
3. Rename the Flyway baseline, the `*Application` class, and `application.yaml` (app name, DB name,
   and the non-superuser Postgres role `<svc>_service` so RLS is enforced at runtime).
4. Keep the ArchUnit `config/LayeredArchitectureTest` intact — do not weaken the Projection layer or
   the native-query guard.
5. Add a row to the `docs/PROJECT-MAP.md` "Services" table (what it owns, events produced/consumed,
   migration range) and note the new service in `docs/DEVLOG.md`.
6. Run `/native-check $1`.

Hard rules: database-per-service, no cross-service joins (rule 1); no synchronous calls between
business services — events + cached read models only (rule 2); events only via the outbox (rule 3);
every table extends `Auditable` + RLS (rules 4–5). A new cross-cutting decision needs an ADR in
`docs/adr/`.
