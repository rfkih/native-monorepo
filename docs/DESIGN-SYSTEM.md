# Native — Design System

> The written form of `frontend/console/src/index.css` `@theme`. The token file is the source of
> truth for *values*; this page is the source of truth for *roles* — which token a thing takes and
> why. Read it before building any console UI (phone, desktop, till, employee app).
> Decisions: [ADR 0077](adr/0077-neutral-ink-brand-replaces-deep-cyan.md) (palette),
> [ADR 0085](adr/0085-the-design-tokens-gain-a-scale.md) (scale + gate),
> [ADR 0075](adr/0075-navigation-and-overlay-contract.md) (one Dialog primitive).
> Enforced by `scripts/check-design-tokens.sh` (pre-commit + CI).

## The rule behind every rule

**Classes name roles, not pixels.** Every utility on a console element comes from `@theme`. No
`text-[13px]`, no `rounded-[14px]`, no `tracking-[0.07em]`, no `bg-black/40`, no `#hex` — if the
scale has no step for what the comp shows, that is a design question (pick the nearest step) or an
ADR (add a step), never a bracket value. The gate fails the commit otherwise.

Exempt, because they are artwork with their own geometry: `features/landing/**`, the print
surfaces (`ThermalReceipt`, `KotView`, `PayslipPrint`, `SelfOrderQr`), and `components/Wordmark`.

## Colour (ADR 0077)

Primary is **maximum contrast against the page, and it inverts between themes** — ink on light,
near-white on dark. Because primary and body text are the same colour, an action is told apart
from a heading by its **lift** (`shadow-lift`) and its **press** (`active:scale-[0.98]`); those are
affordance, not polish.

| Token | Role | Light → Dark |
|---|---|---|
| `emerald` / `on-emerald` | primary fill / its label (name is historical — it means *brand*) | `#0E1116` / `#FFF` → `#F2F2F2` / `#0E1116` |
| `emerald-2` | hover on primary, brand text, **links** | `#22272E` → `#E2E2E2` |
| `emerald-tint` / `emerald-line` | selected fill / border around a tinted fill | `#ECECEC` / `#E4E4E4` |
| `ink` · `ink-2` · `ink-3` | text: primary · secondary · muted (never lighter than `ink-3`) | `#0E1116` · `#384151` · `#6B7280` |
| `paper` · `surface` · `hover` | page ground (and recessed wells) · cards, sidebar · row hover | `#FAFAFA` · `#FFF` · `#F5F5F5` |
| `line` · `line-strong` | hairline · heavier rule (totals, grabbers) | `#E4E4E4` · `#DEDEDE` |
| `profit` · `profit-ink` · `tint-profit` | **green means profit or done — nothing else**: dot · text · fill | `#16B364` · `#0A7E3F` · `#E8F8EE` |
| `loss` · `loss-ink` · `tint-loss` | loss figure · *interactive* destructive text · fill | `#E5484D` · `#B4262A` · `#FCEAEB` |
| `amber` · `warning` · `tint-warning` | flagged / illustrative text · dot · fill | `#9A6A10` · `#F5A623` · `#FEF4E2` |
| `info` · `tint-info` | informational badge text · fill | `#2563EB` · `#E6EFFE` |
| `scrim` | **the one modal backdrop** (Dialog, Drawer, MobileSheet, the till's own) | ink @ .42 → black @ .6 |
| `ink-fixed` | the only ink that does **not** invert — photo scrims, brand panels dark in both themes; text on it is `text-white` | `#0E1116` |
| `brand-500` | cyan survives as **one accent**: live-status dots. Charts may read `var(--color-brand-*)`. Nothing else. | `#0E8FAB` |

Never: Tailwind's default palette (`gray-500`, `red-600`, …), a hex literal in a component, `text-white`
on an inverting fill (use `text-on-emerald`), `bg-black/N` as a backdrop.

## Type (ADR 0085)

Two families, self-hosted: **Plus Jakarta Sans** for words, **JetBrains Mono** for anything that
lines up in a column — money, counts, codes — always with `tnum`. `font-display` is an alias of
`font-sans` (same face); it is a hint, not a different family.

| Class | px / lh | Role |
|---|---|---|
| `text-2xs` | 11 / 14 | eyebrow, badge, table caption, tab-bar label |
| `text-xs` | 12 / 16 | caption, meta, subtitle under a header title |
| `text-sm` | 13 / 19 | secondary text, dense rows, tables |
| `text-base` | 15 / 22 | body, input, CTA label, list row |
| `text-lg` | 17 / 24 | `ScreenHeader` title, card title, lede |
| `text-xl` | 22 / 28 | section title, phone page title |
| `text-2xl` | 28 / 34 | desktop page title |
| `text-3xl` | 32 / 36 | the **one** hero figure a screen is about (mono, `tnum`) |

`4xl`+ are Tailwind's (landing headlines, the keypad entry figure). Weights: 500 secondary, 600
labels and buttons, 700 titles and figures, 800 landing display only. Tracking has two values —
`tracking-display` (≥ 22 px) and `tracking-eyebrow` (the 11 px uppercase label) — and is 0
everywhere else.

**Roles, as they should be written today** (role components are the next step — until then, use
exactly these strings so they can be swept into one):

- Eyebrow / section label: `text-2xs font-bold uppercase tracking-eyebrow text-ink-3` — only where a
  heading needs a parent label, not above every heading.
- Desktop page title: `font-display text-2xl font-bold tracking-display text-ink`.
- Hero figure: `tnum font-mono text-3xl font-bold leading-none tracking-display text-ink`.
- Money in a row: `tnum font-mono text-sm font-semibold` (13) or `text-base` (15), right-aligned.

## Shape

| Class | px | Role |
|---|---|---|
| `rounded-xl` | 12 | controls: buttons `sm`–`lg`, inputs, chips, nav rows |
| `rounded-2xl` | 16 | tiles, phone row-cards, `xl`/`2xl` CTAs |
| `rounded-card` | 20 | `Card` |
| `rounded-t-sheet` | 26 | the rounded top of every bottom sheet |
| `rounded-3xl` | 24 | large icon tiles only |
| `rounded-full` | — | pills, avatars, dots |

Elevation is spent by role, not stamped: `Card` is border-only; overlays carry `shadow-lg`; the
primary button carries `shadow-lift`. A card that must float passes its own shadow.

## Motion

Entrances and exits live in `index.css` as utilities — `dialog-in/out`, `sheet-up/down`,
`scrim-in/out`, `drawer-in`, `reveal`, `rise-in` — each with its `prefers-reduced-motion` fallback.
Use them; do not name tailwindcss-animate classes (`animate-in`, `fade-in-0`, `zoom-in-95`): the
package is not installed and they silently do nothing. Press feedback is `active:scale-[0.98]`
with `motion-reduce:active:scale-100`.

## Touch and focus (Android shells)

Minimum hit area **44 × 44 px**; the full-width CTA is 52 px (`Button size="xl"`). Inputs
(`Field`, `Select`) are 52 px. Focus is `focus-visible:outline-2 focus-visible:outline-offset-2
focus-visible:outline-emerald` (`outline-offset-[-2px]` inside a bar). Every fixed bottom surface
pads itself with `var(--safe-area-inset-bottom, 0px)` — never bare `env()`.

Known debt, tracked for the next changes (see the 2026-09-11 audit and ADR 0085 "Out of scope"):
`Button`'s default `sm` is 40 px; page titles, eyebrows and hero figures are strings not
components; nine sticky-header copies; hand-rolled overlays outside the till; 14 icon stroke widths.

## The gate

```
bash scripts/check-design-tokens.sh
```

Fails on, outside the exemptions above: `text-[…px|rem]`, `rounded-[…px]`, `tracking-[…]`,
`bg-black/N`, `animate-in` / `fade-in-0` / `zoom-in-95`, and the brand ramp as an action colour
(`bg|text|border|ring|outline-brand-*` other than `bg-brand-400/500`, the live dot). Runs from the
pre-commit hook when console sources are staged, and as the `design_tokens` CI job.
