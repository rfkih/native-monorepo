# 0085. The design tokens gain a scale — and a gate keeps it

- **Status:** Proposed
- **Date:** 2026-09-11
- **Deciders:** owner + Claude (frontend)
- **Related:** [ADR 0077](0077-neutral-ink-brand-replaces-deep-cyan.md) (the palette this sits on
  top of; this ADR finishes its last three surfaces and its boot skeleton), [ADR 0075](0075-navigation-and-overlay-contract.md)
  (one Dialog primitive — the scrim token is the visual half of that rule),
  `frontend/console/src/index.css` (`@theme`, the single token source), **`docs/DESIGN-SYSTEM.md`**
  (the written form of the system — new with this ADR), `scripts/check-design-tokens.sh` (the
  gate). Source designs: `Native Console Android.dc.html`, `Native Till Android.dc.html`.

## Context

The token file had a palette and no scale. ADR 0077 made every colour a token, and the dark theme
follows automatically because every utility reads a `var()`; that architecture is right and this
ADR does not touch it. But size was never tokenised, so it was chosen per call site — and a
static scan of `frontend/console/src` on 2026-09-11 found:

| Dimension | Distinct values | Uses |
|---|---|---|
| Font size (`text-[Npx]` + scale) | 34 arbitrary + 10 scale steps, **7 half-pixel** (9.5 → 15.5) | 885 arbitrary |
| Letter-spacing | 27 | — |
| Radius (`rounded-[Npx]`) | 20 — `rounded-[20px]` alone was `rounded-card` spelled 76 more times | 195 |
| Modal scrim | 7 (`black/20 … /60`, `rgba(14,17,22,.42)`, `ink-900/40`) | — |
| Icon stroke width | 14 | — |

The cause is mechanical, not carelessness: the designs are drawn at 11 / 13 / 15 / 17 px and
Tailwind's default scale sits at 12 / 14 / 16 / 18, so every author who matched the comp had to
reach past the scale, and once `text-[13px]` was normal, `text-[13.5px]` was too. The same phone
screen (`DashboardPhone`) used nine sizes; the same role — the small uppercase section label —
existed in twelve forms. Half-pixel sizes also render differently across Android densities.

Four surfaces also still used tailwindcss-animate's `animate-in fade-in-0 zoom-in-95`, a package
that was never installed: the MobileSheet backdrop and the outlet/till popovers had been cutting
in with no motion since they were written, unnoticed because nothing checked for it.

## Decision

**One scale, in Tailwind's own names, in `@theme`.** Eight steps, whole pixels, one line-height
each: `2xs` 11/14 · `xs` 12/16 · `sm` 13/19 · `base` 15/22 · `lg` 17/24 · `xl` 22/28 · `2xl`
28/34 · `3xl` 32/36. This deliberately **re-values** `sm`, `base`, `lg`, `xl`, `2xl`, `3xl` — the
827 existing `text-sm` sites move from 14 to 13 px and land on the design — rather than adding a
second, parallel vocabulary next to Tailwind's. `4xl` and up keep Tailwind's values for the
landing's display type and the keypad's entry figure.

**Two trackings** — `tracking-display` (−0.02em, anything ≥ 22 px) and `tracking-eyebrow`
(0.08em, the 11 px uppercase label); everything else is 0. **Four radii** — control 12
(`rounded-xl`), tile 16 (`rounded-2xl`), card 20 (`rounded-card`), sheet top 26
(`rounded-t-sheet`); `rounded-3xl` (24) stays for large icon tiles. **One scrim** —
`bg-scrim`, ink at .42 on light and black at .6 on dark. **One fixed ink** — `ink-fixed`, the
only ink token that does not invert, for photo scrims and brand panels that must stay dark in
both themes.

**Every arbitrary value migrates now, mechanically** (nearest step; ties round up), and
**`scripts/check-design-tokens.sh` forbids them from coming back**: `text-[Npx]`,
`rounded-[Npx]`, `tracking-[…]`, `bg-black/N` as a scrim, and the brand ramp as an action colour
(`bg/text/border-brand-*` other than the live-status dot). It runs in the pre-commit hook and in
CI beside `check-no-select-star.sh`, and scopes to `frontend/console/src` minus the landing, the
print surfaces, and the wordmark lockup — those are artwork, and set their own type.

**Out of scope, deliberately.** The phone-title component, the eyebrow component, the hero-figure
component, `Button`'s 40 px default, the remaining hand-rolled overlays, the nine sticky-header
copies, and icon stroke widths are real findings of the same audit; they are behaviour and
components, not tokens, and land in their own changes on top of this scale.

## Consequences

**Sizes move by a pixel almost everywhere, and by more where a title was off the scale.** `text-sm`
14 → 13, `text-lg` 18 → 17, `text-xl` 20 → 22, `text-2xl` 24 → 28. The 25 px KPI figures become
28; the 19 px phone titles become 22. Verified with `scripts/mobile-shots.mjs` (screens + more,
both themes) and `tsc` / eslint / the 981 unit tests; the print surfaces are untouched because they
are excluded, so receipts, KOTs and payslips print exactly as before.

**A comp measurement that is not on the scale is now a design question, not a CSS one.** If a
new design needs 14 px, the answer is `text-sm` (13) or `text-base` (15) — or an ADR that adds a
step. This is the point: the scale is the shared vocabulary, and the gate makes it hold.

**The report that motivated this** and the ranked list of what it leaves for later live outside
the repo (the audit artifact of 2026-09-11); `docs/DESIGN-SYSTEM.md` carries the rules themselves.
