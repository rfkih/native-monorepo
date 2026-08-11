#!/usr/bin/env bash
# Native PROD deploy — runs ON THE VPS (invoked over SSH by .github/workflows/deploy-prod.yml).
# ADR 0057 §3: health-gated rolling deploy with AUTOMATIC ROLLBACK to LAST_GOOD on any failure.
#
#   bash prod-deploy.sh <release>          e.g. bash prod-deploy.sh v1.2.0
#
# Expects the deploy dir (~/native-prod by default, override $NATIVE_PROD_DIR) to contain:
#   compose.prod.yml  prod.env  prod/  postgres/  keycloak/  minio/       (synced by the workflow)
#   releases/<release>.images.yml                                          (digest-pinned manifest)
#   scripts/prod-deploy.sh  scripts/prod-rollback.sh                       (this file + rollback)
# State it maintains:
#   LAST_GOOD                   the release name that last passed the full gate
#   releases/LAST_GOOD.images.yml   copy of that release's digest manifest (rollback target)
#   backups/pre-<release>-<db>.sql.gz   pre-deploy snapshots (disaster net — NOT the rollback path)
#   deploy.log                  append-only deploy journal
#
# Flow: flock → preflight → DB snapshot → pull → up -d → health gate → smoke → record LAST_GOOD.
# ANY failure after containers started ⇒ auto-rollback to LAST_GOOD (app tier only — migrations are
# expand/contract by CI gate, so the old images run fine against the new schema). Exit codes:
#   0 = deployed and verified;  1 = failed AND rolled back to LAST_GOOD (verified);
#   2 = failed and could NOT roll back (no LAST_GOOD, or rollback itself unhealthy) — page the owner.
set -uo pipefail

DEPLOY_DIR="${NATIVE_PROD_DIR:-$HOME/native-prod}"
RELEASE="${1:?usage: prod-deploy.sh <release-tag>}"
COMPOSE="docker compose --env-file prod.env -f compose.prod.yml"
PROJECT="-p native-prod"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-900}"   # 15 min: first boot pays KC build + Flyway on 11 services
SNAPSHOT_KEEP="${SNAPSHOT_KEEP:-5}"       # pre-deploy snapshot sets to retain
DBS=(org_service restaurant_service carwash_service barbershop_service loyalty_service \
     finance_service entitlement_service employee_service notification_service payment_service keycloak)

cd "$DEPLOY_DIR" || { echo "FATAL: $DEPLOY_DIR missing — VPS not provisioned (Phase 5 bootstrap)"; exit 2; }
log() { echo "[$(date -u +%FT%TZ)] $*" | tee -a deploy.log; }

# Activate the tunnel profile recorded at bootstrap (quicktunnel|namedtunnel). Without it,
# `up --remove-orphans` would treat the RUNNING tunnel containers as orphans and DELETE them.
profiles=$(grep -E '^COMPOSE_PROFILES=' prod.env 2>/dev/null | cut -d= -f2-)
[ -n "$profiles" ] && export COMPOSE_PROFILES="$profiles"

# ---- deploy lock (never two deploys, never deploy-vs-rollback) -------------------------------
exec 9>deploy.lock
flock -n 9 || { log "ABORT: another deploy/rollback holds the lock"; exit 2; }

# ---- preflight -------------------------------------------------------------------------------
[ -f prod.env ] || { log "FATAL: prod.env missing — provision first (docker/prod.env.example)"; exit 2; }
MANIFEST="releases/$RELEASE.images.yml"
[ -f "$MANIFEST" ] || { log "FATAL: $MANIFEST missing — workflow must sync it before invoking"; exit 2; }
grep -q '@sha256:' "$MANIFEST" || { log "FATAL: $MANIFEST is not digest-pinned"; exit 2; }
log "=== deploy $RELEASE start (last good: $(cat LAST_GOOD 2>/dev/null || echo '<none>')) ==="

# ---- helpers ---------------------------------------------------------------------------------
wait_healthy() { # wait_healthy <timeout-seconds> — all native-prod containers running & healthy
  local deadline=$(( $(date +%s) + $1 )) bad
  while :; do
    # Every container of the project must be running, and those WITH a healthcheck must be healthy.
    bad=$(docker ps -a --filter "label=com.docker.compose.project=native-prod" --format '{{.Names}}' \
      | while read -r c; do
          case "$c" in *minio-init*|*cloudflared*) continue ;; esac   # one-shot / tunnel: not gated
          state=$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}nohc{{end}}' "$c" 2>/dev/null)
          case "$state" in
            "running healthy"|"running nohc") ;;
            *) echo "$c=$state" ;;
          esac
        done)
    [ -z "$bad" ] && return 0
    if [ "$(date +%s)" -ge "$deadline" ]; then
      log "HEALTH-GATE TIMEOUT — unhealthy: $(echo "$bad" | tr '\n' ' ')"
      return 1
    fi
    sleep 10
  done
}

smoke() { # cheap end-to-end probes through the edge (the tunnels' origin), from inside the network
  # console via edge :8080 → expect the SPA shell
  docker exec native-prod-edge wget -qO /dev/null -T 10 http://127.0.0.1:8080/ || { log "SMOKE FAIL: console origin"; return 1; }
  # employee via edge :8081
  docker exec native-prod-edge wget -qO /dev/null -T 10 http://127.0.0.1:8081/ || { log "SMOKE FAIL: employee origin"; return 1; }
  # Keycloak issuer through the edge (issuer must serve its OIDC discovery)
  docker exec native-prod-edge wget -qO /dev/null -T 10 http://keycloak:8080/auth/realms/native/.well-known/openid-configuration \
    || { log "SMOKE FAIL: keycloak issuer"; return 1; }
  # gateway reachable through the edge: an unauthenticated /api hit must answer (401/403/404 all
  # prove gateway is up; a 502 from nginx means it is not) — wget exits 8 on 4xx/5xx, so probe
  # with server-response and accept any HTTP status except 5xx.
  status=$(docker exec native-prod-edge sh -c \
    "wget -qO /dev/null -T 10 --server-response http://127.0.0.1:8080/api/v1/companies/mine 2>&1 | awk '/HTTP\\//{s=\$2} END{print s}'")
  case "$status" in
    5*|"") log "SMOKE FAIL: gateway via edge (status='${status:-none}')"; return 1 ;;
  esac
  log "smoke OK (console, employee, keycloak issuer, gateway status=$status)"
}

deploy_manifest() { # deploy_manifest <images.yml> — pull + up with the given pin file
  $COMPOSE -f "$1" $PROJECT pull -q && $COMPOSE -f "$1" $PROJECT up -d --remove-orphans
}

# ---- 1. pre-deploy DB snapshot (disaster net — routine rollback NEVER restores it) -----------
if docker inspect -f '{{.State.Status}}' native-prod-postgres 2>/dev/null | grep -q running; then
  log "snapshotting ${#DBS[@]} databases (pre-$RELEASE)"
  mkdir -p backups
  for db in "${DBS[@]}"; do
    if ! docker exec native-prod-postgres pg_dump -U postgres --no-owner "$db" 2>>deploy.log \
        | gzip > "backups/pre-$RELEASE-$db.sql.gz"; then
      log "FATAL: snapshot of $db failed — refusing to deploy without a disaster net"
      exit 2
    fi
  done
  # retain the last N snapshot SETS (by release prefix), delete older
  ls backups/pre-*-org_service.sql.gz 2>/dev/null | sort | head -n -"$SNAPSHOT_KEEP" \
    | sed 's/-org_service\.sql\.gz$//' | while read -r prefix; do rm -f "$prefix"-*.sql.gz; done
else
  log "postgres not running (first deploy?) — skipping snapshot"
fi

# ---- 2-3. pull + rolling up with the new digests ---------------------------------------------
log "pull + up ($MANIFEST)"
if ! deploy_manifest "$MANIFEST" 2>&1 | tee -a deploy.log; then
  log "DEPLOY FAIL: compose pull/up errored"
  DEPLOY_FAILED=1
fi

# ---- 4-5. health gate + smoke ----------------------------------------------------------------
if [ -z "${DEPLOY_FAILED:-}" ]; then
  log "health gate (timeout ${HEALTH_TIMEOUT}s)"
  if wait_healthy "$HEALTH_TIMEOUT" && smoke; then
    echo "$RELEASE" > LAST_GOOD
    cp "$MANIFEST" releases/LAST_GOOD.images.yml
    log "=== deploy $RELEASE SUCCESS — recorded as LAST_GOOD ==="
    exit 0
  fi
  DEPLOY_FAILED=1
fi

# ---- AUTO-ROLLBACK ---------------------------------------------------------------------------
log "deploy $RELEASE FAILED — attempting auto-rollback"
if [ ! -f releases/LAST_GOOD.images.yml ]; then
  log "NO LAST_GOOD manifest — cannot roll back (first deploy). Stack left as-is for diagnosis."
  exit 2
fi
if deploy_manifest releases/LAST_GOOD.images.yml 2>&1 | tee -a deploy.log \
   && wait_healthy "$HEALTH_TIMEOUT" && smoke; then
  log "=== ROLLED BACK to $(cat LAST_GOOD) — prod healthy on previous release; $RELEASE rejected ==="
  exit 1
fi
log "=== ROLLBACK FAILED — prod is UNHEALTHY, manual intervention required (see deploy.log, backups/) ==="
exit 2
