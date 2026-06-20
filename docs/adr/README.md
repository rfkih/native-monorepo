# Architecture Decision Records (ADRs)

This directory is the **append-only log of significant architecture decisions** for Native. The stack
in `CLAUDE.md` is pinned "no change without an ADR" and the tech-lead agent "owns ADRs" — this is
where those records live, so a decision and its *why* are discoverable instead of buried in prose.

## When to write one
Write an ADR when a choice is **cross-cutting, hard to reverse, or sets a convention** other code must
follow: a stack/library pin, a persistence or tenancy rule, an event-contract pattern, a service
boundary, a security posture. Routine, local choices do not need one — use a code comment or DEVLOG.

## How
1. Copy `0000-template.md` to `NNNN-short-title.md` (next zero-padded number).
2. Fill in Context / Decision / Consequences. Keep it short — one screen is ideal.
3. Status starts `Proposed`; set to `Accepted` when adopted. Never edit an Accepted ADR's decision —
   supersede it with a new ADR and set the old one's status to `Superseded by NNNN`.
4. Link it from the relevant doc (CLAUDE.md, CODE-STRUCTURE.md, EVENT-CATALOG.md) if it changes a rule.

## Index
| # | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions in ADRs | Accepted |
| [0002](0002-native-query-and-projection-reads.md) | Repository reads are native queries + projections | Accepted |
| [0003](0003-single-source-event-schemas-libs-contracts.md) | Event Avro schemas have a single source: `libs/contracts` | Accepted |
| [0004](0004-openapi-docs-springdoc.md) | Use springdoc-openapi (3.0.x) for OpenAPI docs, piloted in finance-service | Accepted (pilot) |
