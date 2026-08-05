#!/usr/bin/env bash
# Doc-drift gate — fails when the code and the agent-orientation docs disagree:
#   1. Every Avro schema in libs/contracts/src/main/resources/avro/ must be named in
#      docs/EVENT-CATALOG.md (CLAUDE.md rule 7: no event without a catalog entry).
#   2. Every services/<module> directory must be named in docs/PROJECT-MAP.md (the map
#      is the first thing agents read — a missing service means blind spots).
# Runs in CI (ci.yml doc-drift job) and locally: bash scripts/check-doc-drift.sh
set -uo pipefail
cd "$(dirname "$0")/.."

fail=0

# --- 1. Avro schemas ↔ EVENT-CATALOG.md ---
catalog=docs/EVENT-CATALOG.md
for schema in libs/contracts/src/main/resources/avro/*.avsc; do
  event=$(basename "$schema" .avsc)
  if ! grep -q "$event" "$catalog"; then
    echo "DRIFT: $schema has no entry in $catalog (CLAUDE.md rule 7)" >&2
    fail=1
  fi
done

# --- 2. Service modules ↔ PROJECT-MAP.md ---
map=docs/PROJECT-MAP.md
for dir in services/*/; do
  svc=$(basename "$dir")
  [ -f "$dir/build.gradle.kts" ] || continue
  if ! grep -q "$svc" "$map"; then
    echo "DRIFT: services/$svc is not mentioned in $map" >&2
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "" >&2
  echo "Doc drift detected — update the doc(s) above in the same change that touched the code." >&2
  exit 1
fi
echo "doc-drift: clean ($(ls libs/contracts/src/main/resources/avro/*.avsc | wc -l) schemas, $(ls -d services/*/ | wc -l) services)"
