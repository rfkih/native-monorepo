# QA Checklist — the release gate before a production tag

The owner acceptance sign-off (**gate 6 of 6**, ADR 0053 §4 + ADR 0057). Run against **UAT**
after the candidate has been deployed there. A production tag `vX.Y.Z` may only be pushed when
every applicable box is checked. Copy this list into the release PR/issue and tick it there —
the ticked copy is the sign-off record.

## Gate summary (1–5 are automated; this file is gate 6)
| # | Gate | Where |
|---|------|-------|
| 1 | CI suite green (unit, Testcontainers, ArchUnit, contract, spotless, no-SELECT-*, doc-drift, **migration-safety**) | ci.yml `gate` |
| 2 | AI review / QA / security gates green | ai-gates.yml `ai-gate` |
| 3 | Migration dry-run reviewed (data-engineer) — expand/contract only, or an accepted window | PR review + `migration_safety` |
| 4 | Soak on UAT (24–48 h for risky changes): no error-inbox spike, no Kafka/Debezium lag, stable memory | UAT observation |
| 5 | Security review PASS for money/tenancy/auth/PII changes | ai-gates security + human review |
| 6 | **This checklist**, ticked by the owner | here |

## 6.1 Money (every release)
- [ ] A POS sale posts to the GL; **revenue == recorded sales** for the test day (the money assertion)
- [ ] Amounts display correctly in IDR (no float artifacts, correct thousand separators, locale format)
- [ ] A refund/void reverses its postings exactly; daily close shows expected vs counted per tender
- [ ] Sealed periods refuse new postings; the immutable log hash-chain verifies

## 6.2 Tenancy (every release)
- [ ] Log in as company A: no company-B data anywhere (lists, detail pages, reports, media URLs)
- [ ] Direct API probe with a foreign ID returns 404/403, never data (spot-check one endpoint touched this release)
- [ ] A new signup lands in a fresh, empty company and its books start at zero

## 6.3 Auth & roles (every release)
- [ ] Owner, manager, and floor-role logins each see exactly their nav/permissions (ADR 0052)
- [ ] An employee PIN login can operate the till but cannot reach office surfaces
- [ ] Logout fully clears the session; deep-linking a protected route unauthenticated redirects to login

## 6.4 Vertical POS smoke (the verticals affected this release; all four on a risky core change)
- [ ] **Restaurant** — open bill → items → split/pay → receipt prints/renders; KOT/KDS if touched
- [ ] **Carwash** — ticket quote → checkout → capture
- [ ] **Barbershop** — sale end-to-end
- [ ] **Office/books** — invoice or expense end-to-end incl. approval if touched
- [ ] Offline PWA: a sale taken offline syncs cleanly on reconnect (if POS core touched)

## 6.5 HR & payroll (when touched)
- [ ] A payroll run computes for each employment type in scope; totals match the previous run for unchanged employees
- [ ] Salary remains masked in list views; PII visible only where designed
- [ ] Leave/overtime/expense flows approve and post correctly

## 6.6 Platform (every release)
- [ ] Console and Employee app both load over their public origins (no console errors on the dashboard)
- [ ] Language toggle EN/ID renders the touched screens without missing-key artifacts
- [ ] Media (menu images, receipts) load; an upload round-trips
- [ ] `docker ps` on the host: all containers healthy 15+ minutes after deploy; no restart loops

## 6.7 The feature under release
- [ ] Every acceptance criterion of the tagged feature(s) verified by hand on UAT
- [ ] Its failure modes probed (bad input, double-submit, concurrent use) — not just the happy path
- [ ] Rollback plan stated: expand/contract confirmed, or the maintenance window scheduled

## Sign-off
- [ ] I accept this candidate for production. — **owner**, date: ____________, candidate SHA: ____________
