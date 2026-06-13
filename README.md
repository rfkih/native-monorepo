# Native — Developer Agent Team

A dream team of Claude Code subagents that build Native together, with the main session conducting. Each agent is an expert in one lane with its own context window, so the main session stays clean and quality stays high.

## Install
Copy each `*.md` into your repo's `.claude/agents/` (project scope — check them into version control). The main Claude Code session auto-delegates based on each agent's description, or invoke one explicitly: `Use the backend-engineer subagent on M1.4`.

## The team

| Agent | Model | Lane |
|---|---|---|
| **tech-lead** | Opus | Architecture, ADRs, event-contract design, planning |
| **domain-specialist** | Opus | Payroll, Indonesian statutory, consolidation, currency/FX correctness |
| **backend-engineer** | Sonnet | Spring Boot services, endpoints, event consumers |
| **frontend-engineer** | Sonnet | React console + employee app, i18n, Intl, design system |
| **data-engineer** | Sonnet | Postgres schema, Flyway migrations, RLS, partitioning |
| **integration-engineer** | Sonnet | Kafka, Avro, outbox/Debezium, event catalog, contract tests |
| **devops-engineer** | Sonnet | Docker/K8s/Helm, CI/CD, mesh, Vault, observability |
| **qa-engineer** | Sonnet | Unit / integration / contract / E2E / idempotency / chaos tests |
| **security-engineer** | Opus | RLS, PII, auth, audit; runs the security gates; reviews sensitive code |
| **code-reviewer** | Opus | Fresh-context diff review gate before any task is "done" |

## How a task flows
1. **tech-lead** plans the task in plan mode; if it touches the domain, **domain-specialist** validates the approach.
2. **qa-engineer** writes a failing test / acceptance criteria as the target.
3. The relevant builder (**backend / frontend / data / integration / devops**) implements only that scope.
4. **security-engineer** reviews anything touching money, tenancy, auth, or PII (mandatory).
5. **code-reviewer** reviews the diff in a fresh context and reports a pass/fail verdict.
6. **You** approve and commit.

## Model strategy
Run the main session on Opus (coordination + judgment). Opus for the thinkers (tech-lead, domain-specialist, security-engineer, code-reviewer), Sonnet for the builders, and the built-in **Explore** agent (Haiku) for codebase search.

## Mapping to the build plan
- **M0 (skeleton):** devops (stack, CI) + data (Auditable, RLS base) + integration (outbox, schema registry) + backend (Money type, service template).
- **M1 (usable slice):** backend + frontend + data, with qa + security + code-reviewer on every task; domain-specialist watching the Money/currency handling.
- **M2–M3:** all hands — domain-specialist leads payroll, consolidation, and FX; security-engineer drives the hardening gates; devops adds the mesh and Vault at the first service split.

## Two things to know
**Cost.** Each subagent carries its own context, so a subagent-heavy workflow can use several times the tokens of a single thread. Delegate when a task is big, crosses lanes, or needs isolation — not for trivial edits. As a solo dev you don't need all ten active at once; **tech-lead, backend, frontend, data, security, and code-reviewer are the core**, and you add the rest as the build grows.

**Human in the loop.** These agents accelerate the work; they don't replace your judgment. Stay the final authority on the three things that hurt most to get wrong: **event contracts, money/payroll logic, and security boundaries.**
