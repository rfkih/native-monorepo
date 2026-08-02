# ADR 0034 — POS shell + shared payment surface: verticals are adapters, not clones

Date: 2026-08-02 · Status: accepted

## Context

The POS-parity program (ADRs 0023/0024) rolled new verticals out by CLONING the surface shape:
carwash got a fresh `servicepos` terminal, barbershop cloned carwash via `VerticalPosConfig`, and
each vertical carried its own copy of the payment modal (~2,500 near-identical lines across
`PaymentModal` / `BillPaymentModal` / `ServicePaymentModal`). The copies drifted: BillDetail's
category logic missed two fixes the restaurant copy received (7277394 null=All, f050be1
case-insensitive adoption), and the three modals' UAT fixes had to be applied three times
(5d4324c6). The 2026-08 POS redesign also needed one design language across verticals — the
restaurant POS had the "bill tabs" layout while carwash/barbershop still ran the older
two-column paradigm.

## Decision

A shared, **vertical-agnostic presentation layer** lives in `frontend/console/src/features/pos-shell/`:

- `pos-shell/payment/` — the payment surface every vertical renders: frame, breakdown
  (structural `PaymentBreakdownLike`, never a vertical's response type), tender picker,
  cash/digital/full-coverage panel views, checkout-error mapping, quick chips, and
  `usePaymentAttempt` (THE single idempotency-key minting site — one key per panel mount,
  reused across retries; bills pass an external per-initiation key).
- `pos-shell/layout/` — the terminal chrome: `PosStatusBar` (ink-band with identity, outlet
  picker, always-visible connection pill, ≤3 pinned actions) + `TillMenuSheet` (overflow).

**The rule that keeps invariants structural: `pos-shell` is stateless presentation only.** No
fetching, no mutations, no vertical types. Every mutation, wire payload, idempotency key,
offline enqueue, and role gate stays in the vertical's ADAPTER files (`features/pos/PaymentModal`
etc.), which instantiate their own hooks and render the shared views. A new vertical is a config
(`VerticalPosConfig`) + thin adapters over `pos-shell` — this supersedes the frontend half of
ADR 0024's "clone the carwash shape" rollout recipe. The backend half of ADR 0024 (entitlement
fail-closed, module rollout) is unchanged.

## Consequences

- One place to fix payment UX; the three adapters are each ~150–450 lines of behavior only.
- The money-path invariants (per-attempt keys, enqueue-before-success-UI, offline gating) are
  enforced by the layering, not by review vigilance; the P3 money review (PASS) pinned them.
- Behavior asymmetries stay adapter-owned and ADR-bound: bills = one-step digital + external
  key + no gift card (ADR 0026/0027 scope); services = required-attribution gate (ADR 0024).
- Residual (tracked in DEVLOG): service verticals still render their own catalog/summary body;
  the `VerticalPosConfig.shell` block (requirement-chip dock, pinned Charge) is the follow-up.
