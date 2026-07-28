# 0014. An employee login's one-time password is stored encrypted until the employee activates

- **Status:** Accepted
- **Date:** 2026-07-28
- **Deciders:** product owner (rifki) + tech-lead review
- **Related:** [0011](0011-keycloak-admin-via-spring-restclient.md) (Keycloak admin via RestClient), employee-login increment (DEVLOG 2026-07-28), employee-service V8 `login_temp_password_enc`, `PiiAttributeConverter` (AES-256-GCM), rule 6

## Context

When an owner creates a login for an HR employee, Keycloak issues a **one-time temporary password**
(change-on-first-login). The owner must hand that password to the employee — but many employees have
no email, so it cannot be emailed, and the owner may not capture it in the single moment it is shown.

Native's existing rule (documented in code — `InviteUserResponse`, `UserController`, the console
copy — **not** a prior ADR) is that the temporary password is **returned once and never stored
server-side**. That is the safest default, but it does not serve the "deskless employee with no
email" case: if the owner loses the password before handing it over, the only recourse is a full
reset, and there is no way to show it on an employee detail page.

The product owner asked for an **employee detail page that shows the login's username and the
one-time password**, with the password visible **until the employee changes it**, then gone.

A password itself can never be shown (Keycloak stores a one-way hash). The question is only whether to
**hold the one-time password** between issuance and first sign-in so it can be re-displayed.

Options considered:

1. **Never store it** (status quo) — show once at creation/reset; the detail page shows only status
   + a Reset button. Most conservative; does not meet the "visible until changed" requirement.
2. **Store it in plaintext** — simplest re-display; turns any DB leak into every pending credential
   compromised. Rejected outright.
3. **Store it encrypted, auto-purged on activation** — hold the temp password with the same
   AES-256-GCM column cipher as salary/NIK/bank, show it only to owner/manager, and delete it the
   moment the employee first authenticates.

## Decision

Adopt option 3. An employee login's one-time password is **held encrypted at rest until the employee
activates**, a deliberate, bounded exception to "never store the temporary password."

- **Where.** On the `employee` row in employee-service (`login_temp_password_enc`), encrypted by the
  existing `PiiAttributeConverter` (AES-256-GCM, random IV per value) — the crypto lives only in
  employee-service, and the credential belongs with the employee record. A `login_temp_password_set_at`
  timestamp drives the TTL backstop.
- **Who can read it.** Only the owner/manager-gated `GET /api/v1/employees/{id}/login` decrypts and
  returns it (the `/api/v1/employees/**` surface is `DASHBOARD_ROLES` at the gateway). It is never in
  the general `EmployeeResponse`, never logged, never in an event, never in `toString()`.
- **Purge on activation.** The employee's first authenticated `/me` call clears it. Reaching `/me`
  proves Keycloak already forced the change (`UPDATE_PASSWORD` blocks token issuance until the
  employee sets their own), so the first authenticated call is a sound activation signal. The purge
  resolves the caller strictly from their JWT `sub`, so it can only ever clear their own value.
- **TTL backstop.** A held password older than 14 days is treated as absent, covering an employee who
  changed their password out of band (e.g. Keycloak's own account page) and never opened the app.
- **Reset.** `POST /api/v1/users/{id}/reset-password` (org-service → Keycloak) issues a fresh
  temporary password; the console re-holds it via the idempotent `login-link`. org-service itself
  never stores the password.

## Consequences

- **Positive.** The detail page shows the username + the one-time password until the employee takes
  over, then it disappears — meeting the requirement for email-less employees. The credential is
  encrypted at rest exactly like existing PII, owner-only, and self-purging.
- **Negative / residual.** A one-time credential now lives (encrypted) in the DB for a bounded window.
  If an employee changes their password out of band and never opens the app, the owner sees a
  now-**invalid** password until the 14-day TTL hides it (harmless — it no longer works). The purge is
  best-effort (tied to a `/me` visit), with the TTL as the guarantee.
- **Scope.** Applies ONLY to employee logins (linked to an employee record). Ordinary teammate invites
  keep the never-store, show-once behavior.
- **Authorization posture (security review Finding 2, tracked).** The decrypted temp is protected by
  the gateway role gate + RLS, and employee-service trusts the gateway-injected headers (no in-service
  JWT validation yet) — the same east-west trust that already guards salary/NIK. Because this endpoint
  returns a live credential, it must ship only behind the gateway on a non-routable internal network
  until mTLS (Linkerd, deferred per ARCHITECTURE §3/§7); in-service JWT validation is the longer-term
  hardening. The **reset** endpoint additionally enforces a role-hierarchy guard: only an owner may
  reset an owner/manager login, so a manager cannot take over the owner account (Finding 1, fixed).
- This ADR **creates** the exception; the "never stored" statements in `InviteUserResponse` /
  `UserController` remain true for the invite response path and for teammate invites.
