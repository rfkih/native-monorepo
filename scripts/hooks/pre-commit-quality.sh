#!/usr/bin/env bash
# Claude Code PreToolUse hook (matcher: Bash): blocks a `git commit` when staged files fail
# the repo's format/lint gates — the same checks CI's `gate` job runs, moved to commit time so
# drift never lands ("style: spotless" follow-up commits, console lint breaking CI).
#
# Contract: receives the tool-call JSON on stdin. Exit 0 = allow, exit 2 = block (stderr is
# shown to the model as the reason). Non-commit Bash calls fall through instantly.
set -uo pipefail

payload=$(cat 2>/dev/null || true)
case "$payload" in
  *"git commit"*) ;; # a commit — run the gates
  *) exit 0 ;;
esac

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

staged=$(git diff --cached --name-only --diff-filter=ACMR)
[ -z "$staged" ] && exit 0

# --- Java: spotlessCheck, scoped to the Gradle modules with staged .java files ---
modules=$(printf '%s\n' "$staged" \
  | grep -E '^(services|libs)/[^/]+/.*\.java$' \
  | sed -E 's#^(services|libs)/([^/]+)/.*#:\1:\2#' \
  | sort -u)
if printf '%s\n' "$staged" | grep -qE '^service-template/.*\.java$'; then
  modules=$(printf '%s\n%s\n' "$modules" ':service-template' | sort -u)
fi
if [ -n "$modules" ]; then
  targets=$(printf '%s\n' "$modules" | sed 's#$#:spotlessCheck#' | tr '\n' ' ')
  log=$(mktemp)
  if ! ./gradlew -q $targets >"$log" 2>&1; then
    {
      echo "BLOCKED: spotlessCheck failed for staged Java modules."
      echo "Run: ./gradlew $(printf '%s\n' "$modules" | sed 's#$#:spotlessApply#' | tr '\n' ' ')— then re-stage and commit again."
      tail -30 "$log"
    } >&2
    rm -f "$log"
    exit 2
  fi
  rm -f "$log"
fi

# --- Console: ESLint, scoped to the staged console sources (errors block; warnings pass) ---
console_files=$(printf '%s\n' "$staged" | grep -E '^frontend/console/.*\.(ts|tsx)$' | sed 's#^frontend/console/##')
if [ -n "$console_files" ]; then
  if ! (cd frontend/console && npx eslint $console_files) >&2; then
    echo "BLOCKED: ESLint errors in staged console files — fix them, re-stage, and commit again." >&2
    exit 2
  fi
fi

exit 0
