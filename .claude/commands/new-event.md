---
description: Add a new domain event end-to-end — Avro schema, catalog entry, contract test (rule 7).
argument-hint: <EventName> <producer-service> (e.g. SaleRefunded restaurant-service)
allowed-tools: Read, Write, Edit, Bash, Grep, Glob
---

Add the new event **$1** produced by **$2**, honoring CLAUDE.md rule 7 (every event is in the
catalog with a registered Avro schema; schema changes are backward-compatible only) and rule 3
(publish only via the transactional outbox).

Read first: `docs/EVENT-CATALOG.md` (format + existing events) and a recent event for the pattern —
e.g. `SaleRecorded` in restaurant-service: the `.avsc` under `src/main/resources/avro/` and the
`*Schema` builder in the feature's `messaging` package, plus its `*ContractTest`.

Do:

1. Create the Avro schema `$1.avsc`. **Schema-duplication caveat:** today every consumer keeps its
   own copy of the `.avsc` under its `src/main/resources/avro/`. Until a shared `libs/contracts`
   module exists (see `docs/adr/`), add the `.avsc` to the producer **and every consumer** that will
   read it, byte-for-byte identical — and **list every copy you created** so none drift.
2. Add the producer's `*Schema` builder (Avro `GenericRecord` ↔ aggregate) in the feature's
   `messaging` package; emit via the outbox writer (libs/events), never directly to Kafka.
3. Add an entry to `docs/EVENT-CATALOG.md`: name, producer, consumers, topic, and the payload fields
   with their Avro types.
4. Scaffold a contract test (mirror an existing `*ContractTest`) asserting the serialized payload
   round-trips against the schema.
5. Run `/native-check $2`.

Constraints: money fields are integer minor units + ISO-4217 currency, never float (rule 8); never
put unencrypted PII in a payload (rule 6).
