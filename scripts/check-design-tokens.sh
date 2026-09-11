#!/usr/bin/env bash
# Design-token gate — the console's classes name ROLES, not pixels (docs/DESIGN-SYSTEM.md,
# ADR 0085). Every size, radius, tracking, scrim and brand colour comes from index.css @theme;
# a bracket value is how 34 font sizes, 20 radii and 27 letter-spacings grew in the first place.
#
# FORBIDDEN in frontend/console/src/**/*.tsx (and .ts):
#   text-[Npx] / text-[Nrem]           → the type scale: text-2xs … text-3xl
#   rounded-[Npx] (any side)           → rounded-xl / 2xl / card / t-sheet / 3xl
#   tracking-[…], tracking-tight/wide… → tracking-display / tracking-eyebrow (the only two);
#                                        and a misspelt tracking-eyebrowX / tracking-displayX,
#                                        which is a class that does not exist and silently
#                                        renders at 0 (a sed slip did exactly that once)
#   bg-black/N                         → bg-scrim (the one modal backdrop)
#   animate-in, fade-in-0, zoom-in-95  → tailwindcss-animate is NOT installed; these do nothing.
#                                        Use dialog-in / sheet-up / scrim-in from index.css.
#   bg|text|border|ring|outline|from|to|via-brand-*  → the brand is ink (emerald / emerald-2 /
#                                        emerald-tint). Cyan survives as the live-status dot only
#                                        (bg-brand-400 / bg-brand-500 are allowed); charts read
#                                        var(--color-brand-*) which this does not match.
#
# EXEMPT (artwork with its own geometry): features/landing/**, the print surfaces (ThermalReceipt,
# KotView, PayslipPrint, SelfOrderQr), the Wordmark lockup, tests.
#
# Runs in CI (ci.yml design_tokens job) and the pre-commit hook: bash scripts/check-design-tokens.sh
set -uo pipefail
cd "$(dirname "$0")/.."

# `src/*.tsx`, not `src/**/*.tsx`: a git pathspec `*` already crosses `/`, and the `**` form
# skipped the files directly under src/ (main.tsx).
mapfile -t files < <(
  git ls-files 'frontend/console/src/*.tsx' 'frontend/console/src/*.ts' 2>/dev/null \
    | grep -vE '/features/landing/|/(ThermalReceipt|KotView|PayslipPrint|SelfOrderQr)\.tsx$|/components/Wordmark\.tsx$|__tests__/|\.test\.tsx?$' \
    | sort -u
)

# One alternation; each branch is a rule above. The (^|[^A-Za-z0-9_-]) guard keeps `text-2xs`
# from matching inside a longer token and lets variants (`sm:`, `max-sm:`, `print:`) through.
pattern='(^|[^A-Za-z0-9_-])('
pattern+='text-\[[0-9.]+(px|rem)\]'
pattern+='|rounded(-[a-z]{1,2})?-\[[0-9.]+px\]'
pattern+='|tracking-\['
pattern+='|tracking-(tighter|tight|wide|wider|widest)([^a-z-]|$)'
pattern+='|tracking-(eyebrow|display)[a-z]'
pattern+='|bg-black/[0-9]+'
pattern+='|animate-in|fade-in-0|zoom-in-95'
pattern+='|(bg|text|border|ring|outline|from|to|via)-brand-(50|100|200|300|600|700|800|900)([^0-9]|$)'
pattern+='|(text|border|ring|outline|from|to|via)-brand-(400|500)([^0-9]|$)'
pattern+=')'

hits=$(
  printf '%s\n' "${files[@]}" | while IFS= read -r f; do
    [ -n "$f" ] || continue
    grep -nE "$pattern" "$f" 2>/dev/null | grep -vE '^\s*[0-9]+:\s*(//|/?\*)' | sed "s#^#$f:#"
  done
)

if [ -n "$hits" ]; then
  echo "FORBIDDEN: arbitrary design values in console sources — use the tokens (docs/DESIGN-SYSTEM.md):" >&2
  echo "$hits" | cut -c1-200 >&2
  echo "" >&2
  echo "text-[Npx] → text-2xs…3xl · rounded-[Npx] → xl/2xl/card/t-sheet · tracking-[…] → display/eyebrow" >&2
  echo "bg-black/N → bg-scrim · animate-in family → dialog-in/sheet-up/scrim-in · brand-* → emerald tokens" >&2
  exit 1
fi

echo "design-tokens: clean ($(printf '%s\n' "${files[@]}" | grep -c .) console source files scanned)"
