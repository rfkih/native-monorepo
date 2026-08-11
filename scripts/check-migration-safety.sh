#!/usr/bin/env bash
# Migration-safety gate (ADR 0057 §3) — the release invariant that makes automatic rollback safe:
# every Flyway migration must be EXPAND/CONTRACT (backward-compatible), because prod rollback is
# app-tier only ("redeploy the previous digest") and an image rollback never triggers a DB rollback.
# A migration the OLD image cannot run against would strand prod with no way back.
#
# Two checks, scoped to the DIFF against the base ref (history is never rescanned):
#   1. FORWARD-ONLY: an already-committed migration file may never be modified, deleted, or renamed
#      (Flyway checksums break, and the UAT/prod DBs already ran it).
#   2. EXPAND/CONTRACT: a NEW migration may not contain a breaking shape — DROP TABLE/COLUMN,
#      RENAME, ALTER COLUMN TYPE, SET NOT NULL, ADD COLUMN ... NOT NULL without DEFAULT, TRUNCATE.
#
# Escape hatch (deliberate, visible in review): a breaking migration that has been consciously
# accepted — meaning a MAINTENANCE WINDOW, since it cannot be auto-rolled-back — must carry the
# annotation below inside the .sql file. The gate then warns instead of failing, and the
# annotation itself shows up in the PR diff for the reviewer + data-engineer to sign off.
#
#     -- migration-safety: allow-breaking <why + the planned maintenance window>
#
# Runs in CI (ci.yml migration_safety job): bash scripts/check-migration-safety.sh
# Base ref: $MIGRATION_BASE_REF if set, else origin/master, else master.
set -uo pipefail
cd "$(dirname "$0")/.."

BASE="${MIGRATION_BASE_REF:-}"
if [ -z "$BASE" ]; then
  if git rev-parse --verify -q origin/master >/dev/null; then BASE=origin/master; else BASE=master; fi
fi
if ! git rev-parse --verify -q "$BASE" >/dev/null; then
  echo "migration-safety: base ref '$BASE' not found — fetch history (fetch-depth: 0) or set MIGRATION_BASE_REF" >&2
  exit 1
fi

MIGRATION_GLOB='services/*/src/main/resources/db/migration/*.sql'
fail=0

# ---- 1. Forward-only: no modify/delete/rename of an existing migration -----------------------
# Three-dot range = diff since the merge-base, so a file both added AND edited on this branch
# still shows as a single 'A' (allowed); only files that predate the branch can show M/D/R.
while IFS=$'\t' read -r status file _; do
  [ -n "${status:-}" ] || continue
  case "$status" in
    M*|D*|R*)
      echo "FORBIDDEN ($status): $file — applied migrations are immutable (forward-only; Flyway checksum)." >&2
      echo "  Fix forward with a NEW V<next>__*.sql instead." >&2
      fail=1
      ;;
  esac
done < <(git diff --name-status "$BASE"...HEAD -- $MIGRATION_GLOB)

# ---- 2. Expand/contract: scan ADDED migrations for breaking shapes ---------------------------
# Normalize each file to one-statement-per-line (strip -- comments, collapse newlines, split on ;)
# so multi-line DDL is still caught, then grep the breaking patterns per statement.
breaking_patterns=(
  'DROP[[:space:]]+TABLE'
  'DROP[[:space:]]+COLUMN'
  'RENAME[[:space:]]+(COLUMN|TO)'
  'ALTER[[:space:]]+COLUMN[[:space:]]+[^ ]+[[:space:]]+(SET[[:space:]]+DATA[[:space:]]+)?TYPE'
  'SET[[:space:]]+NOT[[:space:]]+NULL'
  'TRUNCATE'
)

while IFS= read -r file; do
  [ -n "$file" ] || continue
  [ -f "$file" ] || continue

  if grep -qiE -- '--[[:space:]]*migration-safety:[[:space:]]*allow-breaking' "$file"; then
    echo "WARN: $file carries 'migration-safety: allow-breaking' — breaking DDL accepted (maintenance window; NOT auto-rollback-safe)." >&2
    continue
  fi

  statements=$(sed 's/--.*$//' "$file" | tr '\n' ' ' | tr ';' '\n')

  hits=""
  for pat in "${breaking_patterns[@]}"; do
    h=$(printf '%s\n' "$statements" | grep -niE "$pat" || true)
    [ -n "$h" ] && hits="$hits$h"$'\n'
  done
  # ADD COLUMN ... NOT NULL without a DEFAULT in the same statement (old image INSERTs would fail).
  h=$(printf '%s\n' "$statements" | grep -niE 'ADD[[:space:]]+COLUMN' | grep -iE 'NOT[[:space:]]+NULL' | grep -viE 'DEFAULT' || true)
  [ -n "$h" ] && hits="$hits$h"$'\n'

  if [ -n "${hits%$'\n'}" ]; then
    echo "FORBIDDEN: $file contains non-backward-compatible DDL (breaks app-tier rollback — ADR 0057):" >&2
    printf '%s' "$hits" | sed '/^$/d; s/^/  statement /' >&2
    echo "  Split into expand/contract (add-alongside now, drop in a LATER release), or annotate" >&2
    echo "  '-- migration-safety: allow-breaking <reason + window>' to accept a maintenance window." >&2
    fail=1
  fi
done < <(git diff --name-only --diff-filter=A "$BASE"...HEAD -- $MIGRATION_GLOB)

if [ "$fail" -ne 0 ]; then exit 1; fi

added=$(git diff --name-only --diff-filter=A "$BASE"...HEAD -- $MIGRATION_GLOB | grep -c . || true)
echo "migration-safety: clean ($added new migration(s) checked against $BASE)"
