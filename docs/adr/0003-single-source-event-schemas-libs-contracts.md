# 0003. Event Avro schemas have a single source: `libs/contracts`

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** rifki; integration-engineer; backend-engineer
- **Related:** CLAUDE.md rule 7 (event schema changes backward-compatible only); docs/EVENT-CATALOG.md;
  ADR [0001](0001-record-architecture-decisions.md)

## Context
Every service kept its **own copy** of each event's `.avsc` under
`src/main/resources/avro/` — the producer's copy plus a hand-copied "consumer copy" in each consumer.
The 18 events were duplicated across 2–4 services (33 files for 18 events). An audit found **every
duplicated schema had drifted**: distinct file hashes everywhere, `SaleRecorded` in three different
versions.

Decoding is raw-bytes Avro with **no Schema Registry** and no writer schema on the wire
(`AvroSerde.deserialize(bytes, schema)` uses one schema as both writer and reader), so a *structural*
divergence between a producer's copy and a consumer's copy would be a latent wire bug, and the
self-contained contract tests (which produce *and* consume with the same service-local copy) would
not catch it. Verifying the drift was therefore a correctness question, not just tidiness.

**Finding (verified):** stripping Avro `doc` recursively (`jq walk(del(.doc))`) and comparing the
canonical JSON, all 18 events are **byte-identical on the wire** — same fields, types, order,
namespace, logical types. The entire drift is human-readable `doc` text. (An initial line-based diff
falsely flagged `PayrollPosted` because one service put `doc` inline on field lines; the structural
diff cleared it.) So there was no wire bug — but the duplication made one inevitable over time, and
left rule 7 unenforceable (you cannot guarantee backward compatibility against N copy-pasted files).

## Decision
Event Avro schemas live **once** in a new resources-only module `libs/contracts`
(`src/main/resources/avro/<EventName>.avsc`), on the classpath at the same path the `*Schema` loaders
already read (`avro/<EventName>.avsc`). Producers and consumers depend on `libs/contracts`; the
canonical copy is the **producer's** (it owns the contract). The per-service copies are deleted. No
loader code changed — only the resource's owning module.

## Consequences
- A producer and its consumers can no longer drift; rule 7 (backward-compatible only) is now
  enforceable against a single file. Verified by the full contract-test suite (pure serialization
  round-trips, Docker-free) still passing after the move.
- `libs/contracts` is dependency-light (pure resources; Avro is parsed at runtime by `libs/events`),
  so even the schema-free modules pay nothing. The stateless `gateway` does not depend on it.
- Follow-up (not done here): a CI check that fails if two `.avsc` with the same name ever reappear, and
  generating the `docs/generated/events.yaml` topology from this one source. A Schema Registry remains
  the eventual home at the first service split; `libs/contracts` is the single source until then.
