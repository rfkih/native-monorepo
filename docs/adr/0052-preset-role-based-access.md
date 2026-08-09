# 0052. Preset role-based access — office roles vs floor roles, capability-gated at the gateway

- **Status:** Accepted
- **Date:** 2026-08-09
- **Deciders:** product owner (rifki) + tech-lead review
- **Related:** [0013](0013-per-login-page-grants-subtractive-ui.md) (page grants layer subtractively on top of this), [0021](0021-multi-company-ownership.md) (roles are global per login — the per-company limitation this ADR defers), [0049](0049-business-and-employee-apps-outlet-terminal-auth.md) (device + operator-PIN auth for shared tills — orthogonal to who-may-do-what), ARCHITECTURE.md §"gateway" (role-based routing), CLAUDE.md hard rule 2 (gateway is the sole sync auth edge)

## Context

Native had **four** business roles — `owner`, `manager`, `cashier`, `employee` — and the gateway
authorized routes against just two coarse buckets: a broad `owner/manager` gate covering the **entire**
back office (HR, payroll, AND the detailed books all at once) and a `cashier` gate for the POS surface.
`employee` reaches only `/me`.

The owner wants everyone to log in **as themselves**, with their **role** deciding the surface: a
bookkeeper should see the ledgers but not payroll salaries; an HR person should see people and payroll
but not the general ledger; kitchen and floor staff should see only the POS/kitchen side and nothing of
the office. The old two-bucket model can't express any of that — `manager` is all-or-nothing over
finance + payroll, and there is no role between "cashier" and "runs the whole company."

Constraints that make this a real decision:
- **The gateway is the security boundary** (hard rule 2 — the single sync auth edge). Menu-hiding in the
  console and the ADR-0013 page grants are UI curation only; neither can be the access control. Any new
  separation of duties must be enforced as a gateway route→role check.
- Roles are **global per login** (ADR 0021 — a login carries one multivalued role claim across all its
  companies; there is no per-(company,role) grant this increment).
- Whoever defines the roles, the role STRINGS must stay in lockstep across six places or a role silently
  half-exists (see Consequences).

## Decision

Adopt a fixed set of **preset role bundles** (not owner-configurable) split into **office** and **floor**
roles, enforced at the gateway by **named capability arrays**. Eight roles:

| Role | Kind | Capability bundle |
|---|---|---|
| `owner` | office | everything |
| `manager` | office | OPS + REPORTS + HR — **operations only** (no detailed books, no payroll) |
| `accountant` | office | REPORTS + FINANCE (the detailed books) |
| `hr` | office | HR + PAYROLL |
| `cashier` | floor | POS |
| `waitress` | floor | POS (same as cashier for now) |
| `chef` | floor | POS/kitchen (lands on the KDS) |
| `employee` | floor | `/me` self-service only (unchanged) |

**Multi-role is allowed** — a login's capability is the **union** of its roles' bundles (one person can
be `hr`+`accountant` and see both payroll and the books). The console lands a multi-role login on its
highest-privilege home.

**The capability → gateway route map is the security boundary** (`services/gateway/.../config/
RoutingConfig.java`), replacing the single broad array with:

- **POS** = `{owner, manager, cashier, chef, waitress}` — POS/restaurant/carwash/barbershop, loyalty
  (POS), operators, register, stocktake, ingredients, menu. Floor roles ring like a cashier.
- **OPS** = `{owner, manager}` — org-units, outlets, users(team), consolidation-groups, promotions,
  loyalty earn-rules, channels, self-order-access.
- **REPORTS** = `{owner, manager, accountant}` — statements / P&L / revenue (managers see the numbers).
- **FINANCE** = `{owner, accountant}` — ap, ar, customers, invoices, vendors, bank, tax, budgets,
  assets, deferrals, opening-balances, platform-settlements, groups, closes, payroll-liabilities.
- **HR** = `{owner, manager, hr}` — employees, expense claims/categories, leave, overtime,
  work-calendar, leave-balances (manager keeps team + time-off).
- **PAYROLL** = `{owner, hr}` — payroll runs/setup/reports. **`manager` does NOT see payroll.**
- **OWNER** = `{owner}` — the existing `@Order(HIGHEST_PRECEDENCE)` PII carve-outs unchanged (payroll
  bank-file, authorized payslips, 1721-a1 / BPJS, **payment-settings**, menu-image migrate).
- **ME** = all eight roles.

Two ambiguous prefixes were decided explicitly: **payment-settings stays OWNER-only** (merchant PSP
credentials are owner config, not bookkeeping), and **`/consolidation-groups` = OPS** while
**`/groups` (finance consolidation) = FINANCE**.

One **method-split** carve-out: the **exact `GET /api/v1/org-units`** flat list is readable by every
OFFICE role (`{owner, manager, hr, accountant}`, a `HIGHEST_PRECEDENCE` GET route) while create/rename/
move/deactivate (POST/PUT/DELETE) stay OPS via the general all-methods route. The HR/People area needs
unit names to scope employees and payroll, and per-unit reports name their units — reading the org
*structure* is not *managing* it. Floor roles are excluded (they use the narrower `GET /api/v1/outlets`
picker). employee-service's own `/api/v1/employees/org-units` projection carries no unit name, so the
console reads names from org-service; this widening is what lets an `hr`-alone login use the People area.
The path is **exact, deliberately not `/**`**: the `/api/v1/org-units` prefix is not structure-only —
`GET /{outletId}/device-credential` reveals a *decrypted* till password and `GET /{id}/users` is per-unit
staffing; a wildcard would leak those to `hr`/`accountant` (a decrypted-credential disclosure + POS
escalation across this very boundary — caught in security review). Those `/{id}/...` sub-resources stay
owner/manager-only via the OPS fall-through, pinned by regression tests.

**Out of scope (deferred):** per-company roles ("HR at company A only") — ADR 0021's global-role
limitation stands; revisit with Keycloak groups / per-company claims when a customer needs it. A
dedicated waitress **order-only (no-payment)** screen and a real backed KDS are later work (P3) — for now
`waitress` = `cashier` and `chef` = the existing thin KDS.

## Consequences

- **Separation of duties is now real and enforced.** An `accountant` token gets 200 on `/ap` and 403 on
  `/payroll-runs`; an `hr` token the mirror; a `chef`/`waitress` token 200 on POS routes and 403 on
  `/employees` and `/statements`; a `manager` 200 on ops/reports and **403 on `/ap` and `/payroll-runs`**
  (a deliberate reduction from the old "manager sees all back office"). Pinned by
  `GatewayRoleExpansionTest` (the full role matrix incl. multi-role union).
- **Six lockstep places** must carry the role set; a role missing from any one silently half-exists.
  The choke point is the gateway `TenantJwtAuthoritiesConverter.BUSINESS_ROLES` (a role absent here is
  stripped from `X-Roles` and invisible everywhere — fail-closed). The others: org
  `UserService.ALLOWED_ROLES` + `KeycloakUser.BUSINESS_ROLES` (assignment validation), and the console
  `BUSINESS_ROLES` + `Team.ROLES` + `auth.tsx extractRoles` (UI recognition). Plus the Keycloak realm
  roles themselves (`docker/keycloak/native-realm.json` **and** the live realm via kcadm — a realm-JSON
  edit does not auto-apply).
- **Multi-role assignment** is now replace-a-set: `PATCH /users/{id}` takes `roles: [...]` (was a single
  replace); invite already accepted `additionalRoles`. The self-lockout guard (you can't strip your own
  last privileged role) and the owner-only reset-hierarchy guard are preserved; `hr`/`accountant` are
  **not** privileged for that hierarchy (only owner/manager are).
- **ADR-0013 page grants still layer on top, unchanged** — subtractive, UI-only, role-intersected. They
  can now narrow within a role's surface (hide one finance page from an accountant) but never widen; the
  gateway capability check is the boundary. The console nav/landing presets are the **UX mirror** of this
  table, not a second enforcement — a forbidden deep-link degrades to the role's home, but the API still
  returns 403.
- **`manager` losing payroll + detailed finance is a behavior change** for existing manager logins; it is
  intended (operations-only). An owner who wants a manager to also keep the books adds the `accountant`
  role to that login (multi-role).
- Deferring per-company roles means a login that is `accountant` is the accountant for **all** its
  companies. Acceptable for the current single-company-per-owner UMKM reality; the ADR-0021 note stands
  as the upgrade path.
