# 0001. Record architecture decisions in ADRs

- **Status:** Accepted
- **Date:** 2026-06-18
- **Deciders:** tech-lead
- **Related:** CLAUDE.md (stack "no change without an ADR"); docs/DEVLOG.md

## Context
`CLAUDE.md` pins the stack "no change without an ADR" and the tech-lead agent's charter says it "owns
ADRs", but the repo had no place to put them — significant decisions (database-per-service, the
transactional outbox, flagged-simplified consolidation) lived only as prose in `docs/DEVLOG.md`,
mixed with day-to-day history. That makes the *why* hard to find for a human or an AI agent, and
gives the tech-lead nowhere canonical to write.

## Decision
We will keep Architecture Decision Records in `docs/adr/` as short, numbered, append-only Markdown
files using the Nygard format (Context / Decision / Consequences), starting from this record. An ADR
is written for cross-cutting, hard-to-reverse, or convention-setting choices. Accepted ADRs are never
rewritten — they are superseded by a newer ADR.

## Consequences
- The tech-lead agent has a canonical target for decisions; `DEVLOG.md` returns to being history and
  current status, not the system of record for rationale.
- New agents/humans can read `docs/adr/README.md` to understand why the architecture is the way it is
  without reverse-engineering the code.
- Small cost: contributors must remember to add an ADR for qualifying changes. The `/new-service`
  command and review checklist point at `docs/adr/` to keep the habit alive.
- Backfilling the major historical decisions (DB-per-service, outbox+CDC, RLS tenancy, the
  flagged-simplified consolidation scope) is a follow-up, done opportunistically.
