# 21. Multi-company ownership — one login, 1..N businesses

Date: 2026-07-30

## Status

Accepted

## Context

Each of a user's businesses is a separate legal entity → a separate Native company with its own
books (RLS-isolated; group consolidation provides the rollup). But one login was hard-bound to
exactly ONE company: identity lives entirely in Keycloak (org-service has no user table), the link
was a single-valued KC user attribute `company_id` emitted as a scalar JWT claim, and four readers
assumed that scalar (gateway `TenantContextHeaderFilter` + `RateLimitFilter`, libs
`TenantBindingFilter`, console `auth.tsx`). The gateway stripped client-sent `X-Company-Id`
entirely, so a switcher could not be client state. Worse, `POST /api/v1/companies` never bound its
creator in Keycloak (only /signup did) — an oidc login that created a company could never reach it
(the onboarding loop).

## Decision

1. **The `company_id` claim becomes multi-valued** — the login's ALLOWED COMPANIES, first = the
   default active company. The KC protocol mapper gains `multivalued: true` (both `native-gateway`
   and `native-console` clients); the user attribute was already a list. Every reader normalizes
   `string | string[]` to a list, so pre-rollout scalar tokens keep working. (RUNBOOK: a live KC
   needs the mapper updated via the admin API — realm-JSON edits don't auto-apply.)

2. **The client selects the ACTIVE company per request via `X-Company-Id`, validated against the
   token's set** — at the gateway AND again at every service edge (`TenantBindingFilter`), defense
   in depth for direct service access. In the set → bind it; absent → first allowed (single-company
   logins behave exactly as before); outside the set → **403** (`invalid-company-selection`) — a
   spoofed header is now rejected outright rather than silently overwritten (strictly stronger).
   Exactly ONE tenant binds per request (`TenantContext` unchanged); RLS and the books are
   untouched. No re-auth per switch, no new sync edges — both validators stay JWT-only.

3. **Memberships are managed through the Keycloak attribute** (`KeycloakAdminClient.addCompanyToUser`
   / `removeCompanyFromUser` — GET-merge-PUT preserving all other attributes, idempotent).
   `KeycloakUser` carries `companyIds`; every cross-tenant team/page-grant/outlet guard becomes
   `belongsTo(company)` — a multi-company login is manageable from EACH of its companies' team
   pages. KC's attribute search (`q=company_id:{value}`) matches any value of a multi-valued
   attribute (proven in the acceptance test).

4. **`POST /api/v1/companies` now BINDS its creator** — membership-first with compensating removal
   (the mirror of signup's KC-first + compensating delete): add the membership, create the company,
   remove the membership if the create fails. This is both the "Add another business" flow and the
   onboarding-loop fix. The gateway's companies route uses a tenant-OPTIONAL variant of the tenant
   filter so a 0-company token can reach the two bootstrap endpoints; org-service's per-path
   `tenant-optional` list remains the enforcement point.

5. **`GET /api/v1/companies/mine`** lists the caller's companies (profiles read via
   `TenantContext.callAs` per verified-claim id; dangling memberships skipped) — the switcher's
   data source. Tenant-optional (a fresh login gets `[]`). At the gateway it gets its OWN
   highest-precedence route allowed for **every business role** (`ME_ROLES`, like
   `/users/me/pages`): the console bootstraps each persona's session from it — a `cashier` needs
   it to open the POS — and it exposes only the caller's own claim-derived memberships, so there
   is no exposure to widen. The rest of `/companies/**` stays owner/manager.

6. **Console**: `auth` exposes `companyIds` + `refresh()` (silent renew); the session holds the
   company LIST + a per-login persisted active pointer (validated against the list) and every API
   call sends the active company as `X-Company-Id` (the token-validated selection); the header
   company pill is a **switcher dropdown** (+ "Add business" → the onboarding wizard, which after
   an oidc create silently renews the token so the new membership's claim arrives, then activates
   it). Dev mode keeps a localStorage list — the switcher works without KC. Every TanStack queryKey
   already carries `companyId`, so a switch re-fetches everything automatically.

## Consequences

- One login can own/operate any number of businesses, each with fully isolated books, switchable in
  two clicks; a single-company user sees no change. The cross-business rollup remains the existing
  group-consolidation feature.
- **Known limitation (documented):** realm roles are GLOBAL per login — an owner is `owner` in all
  their companies. Right for the target persona (one owner, N businesses); per-company roles need
  KC groups / per-company role claims later. Invited managers/cashiers/employees remain
  single-company (the invite flow is unchanged).
- **Known limitation (membership-write concurrency):** the membership add is a Keycloak
  GET-merge-PUT with no optimistic control (KC's user PUT offers no ETag/version). Two
  SIMULTANEOUS creates by the SAME login (e.g. two tabs) can interleave and last-write-wins drops
  one membership — that company's row exists but never reaches the creator's claim (unreachable
  until manual KC repair; `/mine` cannot list it). Existing memberships are never lost (both
  writers start from the same base set), so isolation is unaffected. The wizard disables its
  submit while a create is in flight; the residual two-tab window is accepted for the persona
  (one owner adding one business at a time).
- If the post-create silent token renew fails (IdP session gone, blocked iframe), the console
  activates the new company before its claim arrives — tenant calls briefly 403
  (`invalid-company-selection`) until the next automatic renew self-heals. The wizard retries the
  renew once; the create itself is never lost.
- **Deferred:** adding an EXISTING login to another company (invite creates new users today — the
  same `addCompanyToUser` primitive serves it later); company leave/removal; auto-creating a
  consolidation group across an owner's companies; per-company roles.
- The old "spoofed X-Company-Id is silently replaced" contract became "rejected 403" — API clients
  must send either no selection or a member company.
