# 0049. Split into a Business (outlet-terminal) app and an Employee app; terminal auth = outlet credential + PIN operators + personal elevation

- **Status:** Accepted (P0–P4 implemented; P0–P3 deployed+verified on UAT; P5 Employee app in progress)
- **Date:** 2026-08-08
- **Deciders:** Owner (product) + Claude (tech-lead)
- **Related:** [0013](0013-per-login-page-grants-subtractive-ui.md) (login↔employee link, page grants),
  [0014](0014-accounts-receivable-subledger.md)-era PII-at-rest posture (held temp password),
  [0021](0021-multi-company-ownership.md) (roles global per login — the deferred per-outlet-role
  limitation), [0036](0036-register-sessions-and-platform-channel-settlements.md) (register sessions
  are per-outlet, not per-user), [0043](0043-native-android-till-app.md) (Capacitor `server.url`
  shell, localStorage token persistence); `docs/EVENT-CATALOG.md` (`SaleRecorded`); CLAUDE.md hard
  rules 2 (no sync business calls), 5 (tenant/actor from context, not the body), 6 (PII), 7 (events
  additive).

## Context

Native is one React SPA role-gated at runtime into three surfaces (POS / owner-manager back-office /
employee `/me` self-service), wrapped by one thin Capacitor shell that renders the live console
origin. **Every authenticated request is a Keycloak person:** the tenant (`company_id`) and the
acting identity (`sub`) both come from that one person's token. An outlet is only *data* attached to
a person (`user_outlet_assignment`), and — the load-bearing fact — **who rang a sale is derived
implicitly from the logged-in person's `sub`**: it flows into `sale.created_by` (Auditable) and,
critically, into the commission metric `MetricPublished.subject` (`SaleWriter.emitSalesMetric`,
grain `EMPLOYEE`). The commission pipeline (`MetricInputProjectionWriter`, `MeReader.salesSummary`,
`PayrollRunWriter`) keys entirely on that subject = the Keycloak `sub` = `employee.user_id`.

The owner wants two apps with different trust models:
1. A **Business app** set up once per outlet, logged in with **outlet credentials**, kiosk-persistent
   (no auto-logout; manual logout only), running the **till + basic ops**. Cashiers sign in with a
   **quick PIN** to ring sales (credited to them for commission) and see only **name + role**.
   Owner/manager reach the **back office** by signing in with their **personal account** on top.
2. A separate **Employee app** where each employee signs in with their **personal login** for the
   **full self-service** (payslips, time-off, claims, PII profile, own sales/commission).

The forcing constraints: the whole authz/tenancy/RLS stack is built around one RS256 person JWT
(`sub`/`company_id`/`roles`); a device that authenticates as an *outlet* would make every sale
attribute to the device, silently breaking commission; and hard-rule 2 forbids a vertical calling
employee-service synchronously at sale time (so an operator identity cannot be resolved by a live
lookup). Options considered for the device identity: a true device principal (new Keycloak client +
`outlet` claim + `terminal` role — ripples through the gateway converter, every POS route, and both
tenant-binding filters) vs. a per-outlet person-shaped `cashier` login (drops into the existing
pipeline unchanged). Options for capturing the operator: threading a client-supplied seller id
(spoofable; rule-5 violation) vs. a server-verified session (a DB row needs a cross-service lookup —
rule-2 violation) vs. a self-contained signed token verified offline (the existing
`X-Self-Order-Token` precedent).

## Decision

We will split the frontend into a **Business app** and an **Employee app**, and introduce a
**terminal auth model** with three cooperating credentials, none of which is a new gateway principal:

1. **Outlet credential = a dedicated per-outlet Keycloak user with the `cashier` role and exactly one
   `user_outlet_assignment`.** `cashier` already means "POS-capable, not back-office" (every
   `DASHBOARD_ROLES`/`OWNER_ROLES` route excludes it), and `OutletAccessGuard` already pins it to its
   one outlet — so this reuses the entire JWT → `TenantContext` → RLS chain with no gateway/security
   change. It is minted kiosk-shaped (`temporary=false`, no `UPDATE_PASSWORD`, `offline_access`,
   attribute `actor_type=device`), its one-time password stored encrypted at rest for device
   re-setup, and it persists via offline refresh + the shell's localStorage store (ADR 0043). A new
   `actor_type` (`device|user`) claim is **injected and stripped by the gateway** as `X-Actor-Type`.

2. **Operator (cashier) identity = a per-employee PIN that mints a self-contained, HMAC-signed
   operator token, verified OFFLINE inside each vertical** (mirroring `SelfOrderTokenFilter`/
   `SelfOrderPrincipal`). The PIN is an Argon2id hash in a new employee-service `operator_pin` table,
   set/reset by owner/manager only, employee-pick + PIN, with lockout + rate-limit. `POST
   /api/v1/operators/session` verifies it (RLS-scoped, employee-assigned-to-outlet) and returns the
   token (claims: `companyId`, `businessId`, `operatorUserId=employee.user_id`, `displayName`,
   `role`, short `exp`). Verticals read it via `X-Operator-Session`, assert it matches the bound
   tenant + the sale's outlet, and expose an `OperatorPrincipal` request attribute. **The seller is
   taken from this token, never the request body.**

3. **Personal owner/manager elevation = a second, ordinary OIDC login** layered on the device for the
   back office (short idle TTL, sessionStorage), never persisted like the outlet credential.

**Seller attribution change:** `Sale` gains `sold_by_user_id` (the operator's `sub`); `SaleWriter`
sets `MetricPublished.subject = operatorUserId` when an operator session is present, else falls back
to today's actor (so nothing changes until PINs exist), and rejects a `device` sale with no operator
session (`409 operator-required`). **`MetricPublished.avsc` does not change** — only *which id the
producer writes* to the existing `subject_id`. **`SaleRecorded.avsc`** gains one optional
`sold_by_user_id` (nullable, default, appended last) for audit/reporting.

**Out of scope / deferred:** a true device principal + per-outlet roles (revisit only if
`cashier`-shaped device users prove insufficient — ADR 0021 limitation); RS256 operator tokens via an
employee-service JWKS (vs the initial HMAC); Play-Store distribution of the Employee app.

## Consequences

**Easier:** the device physically cannot reach the back office (enforced by the existing `cashier`
route gates, not new code); commission attribution is re-captured explicitly and **spoof-proof**
(server-verified signed token, tenant/outlet-scoped, body-supplied seller ignored); RLS/tenancy is
untouched (tenant still binds from the outlet token's `company_id`; the operator token's `companyId`
is only *checked*); the commission consumers need **zero** change (they already key on `subject_id`);
the Employee app is a thin reuse of the self-contained `features/me/*` surface (no backend change).

**Harder / costs:** a new PIN credential + offline-verified operator-token codec in each vertical; a
multi-credential frontend (outlet base token + operator session header + personal elevation token,
with three distinct logout paths); device-user provisioning + lifecycle in org-service.

**Rules the code now follows (enforced):** the operator token is verified **offline** — an ArchUnit
rule bans any restaurant/carwash/barbershop → employee-service sync client (rule 2); the seller comes
from the token/context, never the request body (rule 5); the PIN is Argon2id, never
logged/serialized/evented, write-only to owner/manager (rule 6); operator-token claims stay minimal
(`displayName`+`role`, no NIK/salary — rule 6); `SaleRecorded` grows only an additive
nullable-default-last field with a consumer contract test + EVENT-CATALOG entry (rule 7);
`X-Actor-Type` is gateway-injected and on the strip list (a client can never self-declare `user`);
`actor_type=device` users are filtered out of the Team page and never linked to `employee.user_id`
(a device must never earn commission).

**Rollout is inert-first** so commission never silently breaks: ship the seller field + `actor_type`
claim defaulting to today's behavior (P0), then the PIN/operator session (P1), then the verticals
consuming it (P2), then the device credential + Business-app UX (P3), and only **last** flip the
`operator-required` enforcement (P4); the Employee app (P5) is independent. Follow-up: implementation
plan at `~/.claude/plans/sleepy-sleeping-seahorse.md`.

**Known gap deferred to P4 — async-tender operator attribution.** Cash/manual sales are recorded on
the synchronous ring request, where the operator session is present. A **QRIS/card sale is recorded
at async capture** (the `PaymentChargeSucceeded` Kafka consumer thread), which has no HTTP request
and therefore no operator context — so it falls back to the bound actor. This is inert today (every
actor is still a person), but once outlet-device credentials exist (P3) a PIN-rung digital sale would
credit the device, not the operator. P4 must carry the operator captured at ring time (when the
charge is created) through to the async capture (stamp it on the charge/order and read it back), and
its `operator-required` guard must reconcile with the async path (which never sees `actor_type` or
`X-Operator-Session`). Tracked with the P2 code-review W2 note.

**Resolved in P4 (`de933ebe`).** The ring-time operator is stamped onto the PENDING `payment`
(`payment.sold_by_user_id`, migration V35) at the single digital-tender mint point
(`PaymentWriter.recordPendingDigitalInCurrentTx`) and read back at `PaymentCaptureWriter.capture`,
then threaded via `RecordSaleCommand.soldByUserId` so the async-recorded sale credits the operator —
no event-schema change. The `operator-required` guard reconciles with the async path via a new
per-vertical `ActorTypeProvider` that defaults to `"user"` when there is no HTTP request (the Kafka
consumer thread), so the device guard is inert on capture. A **security-review HIGH** was fixed before
sign-off: the digital-tender stamp now applies the SAME tenant/outlet assertion as the cash path
(`OperatorMismatchException.requireMatch`, shared by `PaymentWriter` and `SaleWriter`) — the HMAC
signing key is fleet-wide, so without it a validly-signed but foreign-outlet operator token could have
become the stored ring-time seller and misattributed commission cross-tenant at capture. Commission
credit follows the RING-TIME operator (`command.soldByUserId` wins over a live capture-time session),
so a shift-change capturer cannot take the ringer's credit.

**P4 residuals (deferred).** (1) `ActorTypeProvider` is fail-open by construction — off-request →
`"user"` — which is load-bearing for the async path but means any future `@Async`/`DeferredResult`
write path would silently skip the device guard; not exploitable today (no async return types on the
write paths) — hardening = thread `actor_type` explicitly on the command, or an ArchUnit ban on async
write-path return types. (2) The carwash/barbershop enforce gate is presence-only (no company/outlet
assertion, the operator is never recorded) — a foreign-but-signed token satisfies it; harmless for
commission there (the operator is never used as the metric subject — the washer/barber is) but thinner
accountability than restaurant. (3) The operator token has no `jti` de-dup, so replay within `exp`
can misattribute (pre-existing P1/P2 bearer-token design).

## Addendum (2026-08-09): per-outlet operator-PIN policy + terminal management + session-scoped operator

A manager can now choose, **per outlet**, whether ringing requires an operator PIN; can **view the
outlet's device login** (username + on-demand, audited password reveal) and manage employee PINs from
the console; and the operator selection now **clears every time the app is closed**.

- **Policy lives in employee-service, not org-service.** New `outlet_operator_policy` (V17,
  `company_id` + `business_id` + `require_pin` default true, FORCE RLS). Deliberately NOT an
  `org_unit` column + `OrgUnitChanged` field — that keeps it inside the one service that already owns
  operators (`operator_pin`, roster, mint), so there is **no Avro/event-contract change (rule 7
  untouched) and the verticals need zero changes**. `GET /api/v1/operators/policy` (POS_ROLES, the
  till reads it); `PUT /api/v1/employees/outlet-pin-policy/{businessId}` (DASHBOARD_ROLES,
  owner/manager). Absent row ⇒ `require_pin=true` (**fail-safe**: a missing/RLS-hidden policy is
  PIN-required, never fail-open).
- **Conditional mint.** `OperatorSessionWriter#verifyAndMint` reads the policy first; `require_pin=false`
  skips the entire PIN load/decoy/lockout/verify branch and mints after only the assignment +
  login-link + role checks — so a no-PIN pick **still mints an `operatorUserId`-bearing token**, and
  both the P4 device-guard ("a device sale must carry an operator") and commission attribution hold
  unchanged. `require_pin=true` is byte-identical to before. The roster is policy-aware (a no-PIN
  outlet lists all assigned + login-linked employees; a PIN outlet keeps assigned-and-has-PIN).
- **Trust model (owner-accepted).** At a `require_pin=false` outlet any POS caller can attribute a
  sale to any assigned + login-linked colleague with no verification — honor-system attribution,
  gated behind the owner/manager opt-in, **bounded to attribution only** (no money/PII, and the
  token's `role` is never used for authorization anywhere).
- **Terminal management UI** (owner/manager, OUTLET-gated): reuses the EXISTING device-credential
  reveal (`GET .../device-credential`, decrypt + `no-store` + reveal audit log) behind an explicit
  "Show password" (a mutation, never cached; plaintext held only in ephemeral state, `.reset()` on
  hide/unmount), create/reset/remove, the require-PIN toggle, and a per-employee Set/Reset PIN dialog
  (the existing `PUT .../operator-pin`). **PINs are one-way Argon2id — set/reset only, never
  viewable**; only the outlet password (reversibly encrypted for re-setup) is revealable.
- **Session-scoped operator.** The operator session moved from `localStorage` to `sessionStorage`
  (survives a reload, clears on a full app/tab close), while the **outlet/device token stays in
  `localStorage`** (kiosk-persistent) — so a closed-and-reopened app re-picks (and re-PINs, if
  required) the operator but stays logged in as the outlet. Still bounded by the token `exp`.
  Capacitor caveat: this relies on a WebView clearing sessionStorage on a cold launch (the same
  assumption the personal-elevation manager already makes); a cold-boot clear is the fallback.

`require_pin` is a **mutable** per-outlet setting — a deliberate departure from ADR 0049's
"settings live at creation" rule (it is an operational preference, not an immutable identity like
country/base-currency). Shipped in three reviewed phases (security + code PASS each): **`3c4bba60`**
(P1 employee-service backend), **`ece1c6d2`** (P2 console terminal/PIN/policy UI), **`14f8f873`**
(P3 till conditional + session-scoped operator). Review fixes: length-cap the PIN input
(`@Size(max=64)`, still admits null/blank so the uniform-401 non-enumeration holds) and `.reset()`
the reveal mutation so the plaintext password can't linger in the MutationCache.
