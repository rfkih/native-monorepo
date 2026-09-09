# 0077. Neutral ink replaces the deep-cyan brand, fleet-wide

- **Status:** Proposed
- **Date:** 2026-09-09
- **Deciders:** owner + Claude (frontend)
- **Related:** `CLAUDE.md` (§Stack, §Conventions — the brand rules this changes),
  `frontend/console/src/index.css` (`@theme`, the single token source),
  [ADR 0034](0034-pos-shell-verticals-are-adapters.md) (the POS shell that inherits these tokens),
  [ADR 0049](0049-business-and-employee-apps-outlet-terminal-auth.md) (the two Android shells),
  [ADR 0075](0075-navigation-and-overlay-contract.md) (untouched here; the phone "Lainnya"
  surface it governs is deliberately deferred — see Consequences).
  Source designs: `Native Console Android.dc.html`, `Native Console Web.dc.html`,
  `Native Till Android.dc.html`.

## Context

The console's brand is a deep cyan held at hue 193°, adopted when the app was rebranded off green.
It was never recorded in an ADR — it lives as prose in `CLAUDE.md`, as a comment block in
`index.css`, and as §1a of the old `design/Native Console.dc.html`. That is precisely the kind of
cross-cutting, hard-to-reverse convention this log exists for, so changing it starts by writing the
record that was missing.

The cyan came with a rule that is itself the evidence of a problem: **600 fills, 700 speaks, 500 is
for icons/charts/strokes only**. That split exists because white on `#0B7F99` measures 4.7:1 — a
pass, but only just, and only at the deep end of the family. Every surface using the brand had to
remember which of three shades it was allowed.

Three redesigns arrived together and all three drop the family:

| Design | Cyan values | Ink `#0E1116` |
|---|---|---|
| `Native Console Web.dc.html` | 0 | 109 |
| `Native Till Android.dc.html` | 1 (a live-status dot) | 99 |
| `Native Console Android.dc.html` | 3 (a live dot + one stray hover border) | 153 |

This is not a phone re-skin that can be scoped to `components/mobile/`. The primary action colour
becomes ink across the console, the desktop back office and the till. Two forces make that cheap
and one makes it expensive:

- **Cheap:** the ink ramp and every semantic colour already match the designs exactly — `#0E1116`,
  `#384151`, `#6B7280`, profit `#0A7E3F` / `#E8F8EE`, amber `#9A6A10` / `#FEF4E2`. Green still means
  profit and nothing else. Only the brand family and the *temperature* of the neutrals (cool
  blue-grey → true grey) move: fourteen values in one file. Because the legacy aliases are already
  repointed through `var()`, most components recolour without being touched.
- **Expensive:** that same indirection means one commit repaints the POS and the desktop console
  along with the phone.

**The designs do not answer dark mode.** All sixteen drawn console screens are light-only, and the
new primary fill `#0E1116` *is* the dark-theme surface colour. Today `index.css` flips
`--color-emerald` to bright cyan `#4FBDD1` under `[data-theme='dark']` precisely so a primary
button stays visible; make the brand ink and that escape hatch is gone — a black button on a black
sheet. A brand change that only works in one theme is not a brand change.

## Decision

We will replace the deep-cyan brand with **neutral ink** across the whole frontend fleet — console
(phone and desktop), POS/till, and the employee app — as a single token change in
`frontend/console/src/index.css`.

- **Primary is maximum contrast against the page, in both themes.** Light: fill `#0E1116`, label
  `#FFFFFF`. Dark: the pair inverts — fill `#F2F2F2`, label `#0E1116`. `--color-on-emerald` already
  exists for exactly this flip and keeps carrying it.
- **The three-shade rule is retired.** White on `#0E1116` is ~18:1, so there is no longer a shade
  that may fill but not speak. `--color-emerald` and `--color-emerald-2` collapse onto ink; the
  `brand-*` ramp stays declared for charts and illustration but is no longer the app's action
  colour.
- **Neutrals go true grey**: `--color-line` `#E5E8EC` → `#E4E4E4`, `--color-line-strong` `#CDD2DA` →
  `#DEDEDE` (with `#C6C6C6` as a heavier rule for totals and sheet grabbers), `--color-hover`
  `#F1F5F6` → `#F5F5F5`, `--color-ink-50` `#F2F5F7` → `#FAFAFA`.
- **The page ground goes white.** `--color-paper` `#F6F9FA` → `#FFFFFF`. Separation is carried by
  borders, not by a tinted page behind white cards.
- **A lifted shadow becomes load-bearing.** With primary and body text the same colour, an ink
  button is told apart from a heading by `0 6px 16px rgba(14,17,22,.22)`. It is not decoration and
  is not to be flattened away.
- **Green keeps its single meaning** — profit and done, nothing else. `--color-info` moves one step
  darker to `#2563EB` so it passes as badge text; a new `--color-loss-ink` `#B4262A` carries
  *interactive* destructive text while `#E5484D` stays the figure colour.
- **Cyan survives as one accent only:** `#0E8FAB` on live-status indicators.

Explicitly **out of scope**: the navigation and information-architecture proposals that arrived in
the same design file (the accountant tab backfill, per-persona tiles, nav search, persona-ordered
groups) are behaviour, not brand, and land separately; moving the phone "Lainnya" sheet to a routed
screen touches ADR 0075's contract and needs its own ADR; the invented `Absensi` (clock in/out)
screen is a new feature, not a migration.

## Consequences

**One file carries the change; one harness proves it.** The rule is that no component hardcodes a
brand hex — everything goes through the `@theme` tokens, as it already does. Enforcement is visual,
not a linter: `frontend/console/scripts/pos-matrix.mjs` already renders a 30-shot vertical ×
viewport × theme matrix including print-emulated receipt and KOT shots, and is the gate for this
change because the POS is the surface most exposed by a token edit it never asked for. The mobile
shot harnesses (`VITE_AUTH_MODE=dev`) cover the phone console.

**Accessibility improves.** Every primary surface moves from ~4.7:1 to ~18:1, in both themes. The
"which shade may I use" rule disappears from review.

**We lose colour as a wayfinding cue.** Cyan marked "this is Native, and this is actionable" at a
glance. Ink does not; the design compensates with shape — the lifted shadow, a 52–56px full-width
CTA at radius 15–16, and `:active { transform: scale(.98) }` press feedback the app does not have
today. If those are dropped as polish, the interface loses affordance and this ADR is not being
followed.

**Print is unaffected.** The print block already forces a white ground and the statements never used
the brand for anything load-bearing.

**Known follow-ups.** (1) The `brand-*` ramp is left declared but unused by the action layer —
sweep it once the chart surfaces (ADR 0071 analytics) have picked their palette. (2) The employee
app and self-order surface inherit the tokens and should be re-shot before release. (3) The stray
`#4FBDD1` hover border in the Android design is a leftover, not a spec — do not port it.
