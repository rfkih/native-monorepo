#!/usr/bin/env bash
# No-SELECT-* gate — the project forbids `SELECT *` in any production query (CLAUDE.md "Never" +
# CODE-STRUCTURE §3.3): a read path selects only the columns it needs into a projection, and a
# full-entity write-path load names its columns explicitly. `SELECT *` over-fetches, couples the
# query to column order, and silently binds a newly-added column to the wrong field.
#
# WHY a script and not (only) ArchUnit: ArchUnit can read a repository @Query annotation, but NOT a
# raw JdbcTemplate SQL string in a method body or a SQL view. This grep guard covers BOTH — every
# production query text, wherever it lives.
#
# SCOPE: production main code only — services/*/src/main + libs/*/src/main + service-template +
# their SQL migrations. Test code is intentionally exempt: a whole-row snapshot ("SELECT * ... prove
# nothing changed") is a legitimate test technique, not an over-fetch on a response path.
#
# MATCH: `select <*> from` (case-insensitive, any whitespace). This is the over-fetch shape; it does
# NOT flag `count(*)`, `exists(select 1 ...)`, or `sum(x) * y`. Comment lines (javadoc `*`, `//`,
# `{@code ...}`, `@link`) are filtered so the many "never SELECT *" doc comments don't trip it.
#
# Runs in CI (ci.yml no_select_star job) and the pre-commit hook: bash scripts/check-no-select-star.sh
set -uo pipefail
cd "$(dirname "$0")/.."

# Main-code source roots (never src/test, never build output).
mapfile -t files < <(
  { git ls-files 'services/*/src/main/**/*.java' \
                 'libs/*/src/main/**/*.java' \
                 'service-template/src/main/**/*.java' \
                 'services/*/src/main/**/*.sql' \
                 'libs/*/src/main/**/*.sql' \
                 'service-template/src/main/**/*.sql' 2>/dev/null; } | sort -u
)

hits=$(
  printf '%s\n' "${files[@]}" | while IFS= read -r f; do
    [ -n "$f" ] || continue
    grep -niE 'select[[:space:]]+\*[[:space:]]+from' "$f" 2>/dev/null \
      | grep -vE '^\s*[0-9]+:\s*\*|//|\{@code|@link' \
      | sed "s#^#$f:#"
  done
)

if [ -n "$hits" ]; then
  echo "FORBIDDEN: SELECT * found in production query text — select explicit columns / a projection:" >&2
  echo "$hits" >&2
  echo "" >&2
  echo "See CODE-STRUCTURE.md §3.3. Test-only whole-row snapshots are exempt (this scans src/main only)." >&2
  exit 1
fi

echo "no-select-star: clean ($(printf '%s\n' "${files[@]}" | grep -c . ) production source files scanned)"
