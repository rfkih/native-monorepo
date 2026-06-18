---
description: Scaffold a new feature (bounded sub-context) in a service with the correct layer sub-packages.
argument-hint: <service-name> <feature> (e.g. finance-service settlement)
allowed-tools: Read, Write, Edit, Bash, Grep, Glob
---

Create the feature package **$2** in **$1**, following `docs/CODE-STRUCTURE.md` (package-by-feature
with layer sub-packages, ArchUnit-enforced).

Package root: `id.co.nativeapp.<service>.$2`. Create ONLY the layer sub-packages the feature actually
needs (no empty folders):

- `domain` — `@Entity` aggregate(s); extends libs/tenant `Auditable` (rule 4); `Money` via
  libs/money (rule 8); validates its own invariants; `protected` no-arg ctor for JPA only.
- `repository` — Spring Data interface. **Queries are native + projection** (CLAUDE.md Conventions):
  every `@Query` is `nativeQuery = true`; read paths return a projection from `$2.projection` (only
  the needed columns), never `SELECT *`; write-loads / `count` / `exists` stay entity/scalar.
- `projection` — interface projection(s) for read paths (snake_case alias → camelCase getter).
- `service` — `@Service` orchestration + `@Component @Transactional` `*Writer`/`*Reader` (the tx
  proxy + RLS aspect engage here, never on a controller/repository).
- `dto` — request/response/command/result records.
- `controller` — `@RestController` (only if the feature exposes HTTP).
- `messaging` — Avro `*Schema` + outbox wiring (only if it produces/consumes events — use `/new-event`).

Do NOT: add a manual `WHERE company_id` (RLS auto-applies — rule 5); call another business service
synchronously (rule 2); store money as a float or hardcode a user-facing string. When done, run
`/native-check $1`.

For non-trivial domain logic (payroll, consolidation, FX), validate the approach with the
domain-specialist agent first.
