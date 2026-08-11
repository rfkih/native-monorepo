#!/usr/bin/env bash
# Native PROD rollback — runs ON THE VPS. One command, any time (ADR 0057 §3):
#
#   bash prod-rollback.sh                 → redeploy LAST_GOOD (the release that last passed the gate)
#   bash prod-rollback.sh <release>       → redeploy a specific prior release (its manifest must
#                                           still exist under releases/) and record it as LAST_GOOD
#
# App tier only: this re-applies pinned image digests and never touches the database — safe because
# every migration is expand/contract (CI migration_safety gate). Takes the same deploy lock as
# prod-deploy.sh, health-gates the result, and refuses to leave prod silently broken.
set -uo pipefail

DEPLOY_DIR="${NATIVE_PROD_DIR:-$HOME/native-prod}"
COMPOSE="docker compose --env-file prod.env -f compose.prod.yml"
PROJECT="-p native-prod"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-600}"

cd "$DEPLOY_DIR" || { echo "FATAL: $DEPLOY_DIR missing"; exit 2; }
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a deploy.log; }

exec 9>deploy.lock
flock -n 9 || { log "ABORT: a deploy/rollback already holds the lock"; exit 2; }

if [ $# -ge 1 ]; then
  TARGET="$1"; MANIFEST="releases/$TARGET.images.yml"
else
  TARGET="$(cat LAST_GOOD 2>/dev/null || true)"; MANIFEST="releases/LAST_GOOD.images.yml"
  [ -n "$TARGET" ] || { log "FATAL: no LAST_GOOD recorded — nothing to roll back to"; exit 2; }
fi
[ -f "$MANIFEST" ] || { log "FATAL: $MANIFEST not found"; exit 2; }
grep -q '@sha256:' "$MANIFEST" || { log "FATAL: $MANIFEST is not digest-pinned"; exit 2; }

log "=== rollback to $TARGET start ==="
if ! { $COMPOSE -f "$MANIFEST" $PROJECT pull -q && $COMPOSE -f "$MANIFEST" $PROJECT up -d --remove-orphans; } 2>&1 | tee -a deploy.log; then
  log "ROLLBACK FAILED: compose pull/up errored"; exit 2
fi

# Same health gate as deploy (duplicated inline so this script stands alone on the VPS).
deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
while :; do
  bad=$(docker ps -a --filter "label=com.docker.compose.project=native-prod" --format '{{.Names}}' \
    | while read -r c; do
        case "$c" in *minio-init*|*cloudflared*) continue ;; esac
        state=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}nohc{{end}}' "$c" 2>/dev/null)
        case "$state" in "running healthy"|"running nohc") ;; *) echo "$c=$state" ;; esac
      done)
  [ -z "$bad" ] && break
  if [ "$(date +%s)" -ge "$deadline" ]; then
    log "=== ROLLBACK to $TARGET UNHEALTHY: $(echo "$bad" | tr '\n' ' ') — manual intervention required ==="
    exit 2
  fi
  sleep 10
done

# Explicit-target rollback becomes the new LAST_GOOD (it just passed the gate).
if [ $# -ge 1 ]; then echo "$TARGET" > LAST_GOOD; cp "$MANIFEST" releases/LAST_GOOD.images.yml; fi
log "=== rollback to $TARGET SUCCESS — prod healthy ==="
