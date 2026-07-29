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
| [0004](0004-openapi-docs-springdoc.md) | Use springdoc-openapi (3.0.x) for OpenAPI docs, piloted in finance-service | Accepted (pilot; extended fleet-wide by [0008](0008-openapi-docs-fleet-rollout.md)) |
| [0005](0005-error-inbox-and-alerting.md) | Error-inbox + webhook alerting for production-error visibility, piloted in finance-service | Accepted (pilot; extended fleet-wide by [0009](0009-error-inbox-fleet-rollout.md)) |
| [0006](0006-pos-payment-tenders.md) | POS payment tenders — provider-agnostic port, cash live, digital flagged-pending, revenue-at-capture | Accepted |
| [0007](0007-real-psp-adapter-webhook.md) | Real payment-service-provider adapter + settlement webhook (the digital-tender switch) | Proposed |
| [0008](0008-openapi-docs-fleet-rollout.md) | Roll springdoc-openapi out fleet-wide with an `@Operation` ArchUnit enforcer | Accepted |
| [0009](0009-error-inbox-fleet-rollout.md) | Extract the error-inbox into `libs/error-inbox` and roll it out fleet-wide | Accepted |
| [0010](0010-distributed-tracing-otel.md) | Wire distributed tracing (Micrometer Tracing + OpenTelemetry) fleet-wide | Accepted |
| [0011](0011-keycloak-admin-via-spring-restclient.md) | Use Spring RestClient for Keycloak Admin API calls (no keycloak-admin-client library) | Accepted |
| [0012](0012-flatten-org-tree-remove-branch.md) | Flatten the org tree: remove BRANCH and seed a default outlet per business unit | Accepted |
| [0014](0014-accounts-receivable-subledger.md) | Accounts Receivable sub-ledger + the customer/party dimension in finance-service | Accepted |
| [0015](0015-accounts-payable-subledger.md) | Accounts Payable sub-ledger — the vendor-facing mirror of AR | Accepted |
| [0016](0016-bank-reconciliation.md) | Bank accounts & reconciliation — settling the clearing account | Accepted |
| [0017](0017-tax-ppn-vat-return.md) | Tax / PPN — the VAT return, filing & settlement | Accepted |
