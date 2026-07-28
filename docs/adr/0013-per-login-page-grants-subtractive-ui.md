# 0013. Per-login page grants are subtractive UI gating; roles remain the API authz boundary

- **Status:** Accepted
- **Date:** 2026-07-28
- **Deciders:** product owner (rifki) + tech-lead review
- **Related:** ARCHITECTURE.md §"gateway" (role-based routing), [0011](0011-keycloak-admin-via-spring-restclient.md), employee-login increment (DEVLOG 2026-07-28), org-service V8 `user_page_grant`

## Context

The owner asked to make each employee login's console access **adjustable** — choosing which pages a
given login may open. Native already authorizes on Keycloak realm roles: the gateway maps a token's
roles to a route allow-list (`owner`/`manager` → the dashboard surface, `cashier` → the POS surface,
`employee` → the `/me` self-service surface). Roles are coarse: every cashier gets the whole POS
surface (POS, Menu, Kitchen), with no way to give one cashier the till but not the kitchen display.

Two ways to make access adjustable:

1. **Make grants a security control** — encode per-login page permissions into the token (a claim or
   a scope) and enforce them at the gateway alongside roles. This makes the grant authoritative for
   API access, but: grants become fixed at login (a 5-minute token lifespan means a revoked page
   lingers until refresh), the gateway must grow page-aware routing, and page identity (a UI concept)
   leaks into the API authorization model.

2. **Make grants a UI curation tool** — the console consults grants to hide pages; roles stay the
   sole API boundary. Grants are runtime data (fetched, not claimed), so a change applies on the next
   read; the gateway is unchanged; the API model stays role-only.

## Decision

Per-login page grants are **subtractive, UI-level gating only.** They narrow what the console shows a
login; they never widen it, and they are **not** a security boundary — the gateway's role check
remains the authoritative API authorization.

- **Storage:** org-service `user_page_grant` (V8) — `(company_id, user_id [= Keycloak sub], page_key,
  active)`, FORCE RLS, unique per `(company, user, page)`. Modeled on `user_outlet_assignment` (V5)
  minus effective dating. **No event, no downstream consumer:** enforcement is entirely console +
  org-local, so nothing needs to be published (this deliberately avoids the rule-7 event-contract
  surface).
- **Semantics:** zero grant rows for a user = the **full role surface** (grandfather — absence of
  restriction means unrestricted, exactly like outlet assignments). One or more rows = restricted to
  those page keys, still intersected with the role surface (a grant can only subtract).
  Owner/manager **bypass** grants entirely. `/me` is the always-available floor and is never removed
  by the editor, so a login can never be locked out of everything. (Extended 2026-07-28 — see the
  Update below: the bypass is narrowed to **owner-only** so a manager login can be narrowed, and the
  grantable set now spans the dashboard pages too.)
- **Reads:** `GET /api/v1/users/me/pages` (every business role, gateway `@Order(HIGHEST_PRECEDENCE)`
  exact route before the general `/users/**`, mirroring `/me/outlets`); `GET|PUT
  /api/v1/users/{userId}/pages` (owner/manager). The console fetches `/me/pages` after login (short
  staleness, refetch on focus) and **fails open** — a read error grants the full surface rather than
  locking the user out.

## Consequences

- A user who still holds a role can call that role's APIs directly regardless of their page grants —
  e.g. a cashier whose Kitchen page is hidden can still `POST` to a kitchen endpoint if one accepts
  `cashier`. **This is by design:** grants are a usability/curation tool, not a permission system. To
  actually deny an API, change the role (or the gateway route's role set). This is documented so a
  future security review does not read the console-only enforcement as a hole.
- Grant changes apply on the next `/me/pages` read (focus/refetch), not instantly — acceptable for a
  UI-curation tool with a 30-second staleness window.
- Keeping page identity out of the token/gateway means the API authorization model stays purely
  role-based (ARCHITECTURE.md §gateway) — no page-aware routing, no token-lifespan coupling.
- If a genuine per-page **security** requirement ever arises, it is a new role (or a route role-set
  change), not an extension of this table.

## Update (2026-07-28) — grantable set extended to every console page; manager grants

The owner asked to manage page access per person from the org-unit **People** tab, across all pages
(not just the POS surface). This EXTENDS the decision above without changing its nature (grants stay
subtractive, UI-only, role-intersected):

- **Grantable set** now also includes the back-office pages: `dashboard`, `reports` (income
  statement + balance sheet), `org`, `groups`, `close`, `team` — alongside the original `pos`,
  `menu`, `kitchen` and the `me` floor (org-service `ALLOWED_PAGE_KEYS`).
- **Bypass narrowed to owner-only.** Previously owner AND manager bypassed grants; now only the
  **owner** bypasses (so the owner can never be locked out and is the recovery path for any
  mis-grant). A **manager** login's grants now apply, so an owner can hide, say, Finance from one
  manager. Still purely subtractive — a manager can never be *granted* a page their role lacks.
- **Role-aware picker.** The editor shows only the pages the target login's role can actually reach
  (a cashier sees POS/Menu/Kitchen; an owner/manager login sees the dashboard pages + POS surface; an
  employee-only login has just `/me` and nothing to restrict), so a check never implies access the
  gateway would deny.
- The console guards each dashboard route with the grant (owner still bypasses) and filters the shell
  nav; `home` resolves to the first *allowed* page to avoid a redirect loop when a login's landing
  page is hidden. Roles remain the API authz boundary (the Consequences above are unchanged).
