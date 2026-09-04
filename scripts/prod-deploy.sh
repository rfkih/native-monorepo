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
  # prove gateway is up; a 502 from nginx means it is not). busybox wget reports 4xx as
  # "wget: server returned error: HTTP/1.1 401 Unauthorized" — extract the NUMERIC code
  # explicitly (the old $2-based parse read the word "server" and would have passed a 502).
  status=$(docker exec native-prod-edge sh -c \
    "wget -qO /dev/null -T 10 --server-response http://127.0.0.1:8080/api/v1/companies/mine 2>&1 \
     | grep -oE 'HTTP/[0-9.]+ [0-9]{3}' | tail -1 | grep -oE '[0-9]{3}\$'")
  case "$status" in
    401|403|404|200) : ;;
    *) log "SMOKE FAIL: gateway via edge (status='${status:-none}')"; return 1 ;;
  esac
  # ADR 0063 (flaw-audit W5): the MinIO anon policy MUST be narrowed to menu/* — a BILL-prefix key
  # must come back 403 (denied), never 404 (key-miss under a covering policy = bill receipts are
  # publicly fetchable; re-run docker/minio/init.sh on this env). Menu stays anon-readable (404 on
  # a bogus key). Skipped only if the media route itself is absent (pre-0048 stack).
  bogus=$(printf '0%.0s' $(seq 1 64))
  bill_status=$(docker exec native-prod-edge sh -c \
    "wget -qO /dev/null -T 10 --server-response http://127.0.0.1:8080/api/media/restaurant/smoke/bill/${bogus}.jpg 2>&1 \
     | grep -oE 'HTTP/[0-9.]+ [0-9]{3}' | tail -1 | grep -oE '[0-9]{3}\$'")
  if [ "$bill_status" != "403" ]; then
    log "SMOKE FAIL: anon media policy too broad — bill-prefix key returned '${bill_status:-none}' (expected 403). Re-run minio init."; return 1
  fi
  log "smoke OK (console, employee, keycloak issuer, gateway status=$status, bill-media anon=403)"
}

pull_with_retry() { # pull_with_retry <images.yml> — registries blip (TLS timeouts); retry before failing
  local i
  for i in 1 2 3; do
    $COMPOSE -f "$1" $PROJECT pull -q 2>&1 | tee -a deploy.log && return 0
    log "pull attempt $i/3 failed — retrying in 15s"
    sleep 15
  done
  return 1
}

up_manifest() { # up_manifest <images.yml> — (re)converge onto the given pin file
  $COMPOSE -f "$1" $PROJECT up -d --remove-orphans
}

# ---- CDC health (Debezium): connector TASKS RUNNING, not just the connect worker "healthy" ----
# The connect container's healthcheck only proves the Kafka Connect WORKER is up. A connector TASK
# can be FAILED while the worker is "healthy" — e.g. `compose up` recreated postgres with a new IP
# and connect held stale JDBC connections (the 2026-08-16 fleet-wide CDC outage: every outbox task
# FAILED "Couldn't obtain encoding … connect timed out", yet wait_healthy passed connect as
# "running healthy" and the deploy reported SUCCESS while NO outbox event reached any consumer —
# sales/closings/corrections silently stopped flowing to finance). So after a healthy deploy, force
# CDC back to RUNNING: restart connect (drops the stale connections, re-resolves the current
# postgres) then clear any still-FAILED task. Best-effort — a healthy app tier is never failed on
# CDC recovery; ops-watch is the durable alarm if this cannot restore it.
CONNECT_CTR="native-prod-connect"
connect_get()  { docker exec "$CONNECT_CTR" curl -s -m 10         "localhost:8083/$1" 2>/dev/null; }
connect_post() { docker exec "$CONNECT_CTR" curl -s -m 10 -X POST "localhost:8083/$1" 2>/dev/null; }
connect_put()  { docker exec "$CONNECT_CTR" curl -fsS -m 15 -X PUT -H 'Content-Type: application/json' -d "$2" "localhost:8083/$1" >/dev/null 2>&1; }

# Register EVERY connector from the release's synced debezium/*.json — idempotently (PUT
# /connectors/{name}/config creates a missing connector and updates an existing one). This is the
# missing half of recover_cdc: recovery only restarts ALREADY-REGISTERED connectors, which is
# exactly how finance-service ran without a connector in every environment for months (its outbox
# rows relayed nowhere — ADR 0071 §A). A new producer's connector now goes live BY DEPLOYING it;
# no hand registration, no silent gap. Best-effort per file: one bad json never blocks the rest.
register_connectors() {
  local f name cfg
  for f in debezium/*.json; do
    [ -e "$f" ] || continue
    name=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['name'])" "$f" 2>/dev/null)       || { log "WARN: cdc: cannot parse $f — skipping"; continue; }
    cfg=$(python3 -c "import json,sys;print(json.dumps(json.load(open(sys.argv[1]))['config']))" "$f" 2>/dev/null)       || { log "WARN: cdc: cannot parse $f config — skipping"; continue; }
    if connect_put "connectors/$name/config" "$cfg"; then
      log "cdc: registered/updated connector $name"
    else
      log "WARN: cdc: failed to register $name — register manually (RUNBOOK: local dev section 3 idiom)"
    fi
  done
}
cdc_stopped_connectors() { # names of connectors whose task state is not RUNNING; empty ⇒ all good
  local list c
  list=$(connect_get connectors | tr -d '[]"' | tr ',' ' ') || return 0
  for c in $list; do
    connect_get "connectors/$c/status" | grep -o '"state":"[A-Z]*"' | sed -n 2p | grep -q RUNNING \
      || echo "$c"
  done
}
recover_cdc() {
  docker inspect "$CONNECT_CTR" >/dev/null 2>&1 || { log "cdc: no $CONNECT_CTR container — skipped"; return 0; }
  local stopped; stopped=$(cdc_stopped_connectors | tr '\n' ' ')
  [ -z "${stopped// }" ] && { log "cdc: all connector tasks RUNNING"; return 0; }
  log "cdc: connector task(s) not RUNNING (${stopped}) — restarting connect + failed tasks"
  docker restart "$CONNECT_CTR" >/dev/null 2>&1
  local i; for i in $(seq 1 45); do connect_get connectors >/dev/null 2>&1 && break; sleep 4; done
  local c; for c in $(connect_get connectors | tr -d '[]"' | tr ',' ' '); do
    connect_post "connectors/$c/restart?includeTasks=true&onlyFailed=true" >/dev/null 2>&1 || true
  done
  sleep 15
  stopped=$(cdc_stopped_connectors | tr '\n' ' ')
  [ -z "${stopped// }" ] && { log "cdc: recovered — all connector tasks RUNNING"; return 0; }
  log "ERROR: cdc: connector task(s) STILL not RUNNING after recovery (${stopped}) — outbox events are NOT flowing to consumers (finance P&L etc.); manual attention required"
  return 1
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

# ---- 2. pull FIRST — a pull failure leaves the running stack untouched (no rollback needed) --
log "pulling $MANIFEST"
if ! pull_with_retry "$MANIFEST"; then
  log "=== DEPLOY ABORTED: image pull failed after retries — stack UNTOUCHED, prod still on $(cat LAST_GOOD 2>/dev/null || echo '<none>') ==="
  exit 1
fi

# ---- 3. rolling up (from here on, failures trigger auto-rollback) ----------------------------
log "up ($MANIFEST)"
if ! up_manifest "$MANIFEST" 2>&1 | tee -a deploy.log; then
  log "DEPLOY FAIL: compose up errored"
  DEPLOY_FAILED=1
fi

# ---- 4-5. health gate + smoke ----------------------------------------------------------------
if [ -z "${DEPLOY_FAILED:-}" ]; then
  log "health gate (timeout ${HEALTH_TIMEOUT}s)"
  if wait_healthy "$HEALTH_TIMEOUT" && smoke; then
    # CDC is async and outside the container healthcheck: a deploy that recreated postgres can leave
    # every Debezium task FAILED while the app tier is green (2026-08-16 outage). Recover it here so a
    # deploy never silently kills the event pipeline. Best-effort: the app is healthy, so a CDC hiccup
    # does not fail/rollback the deploy — it logs loudly and ops-watch keeps alerting until resolved.
    register_connectors
    recover_cdc || log "WARN: post-deploy CDC recovery incomplete — see ops-watch (deploy still recorded; app tier is healthy)"
    echo "$RELEASE" > LAST_GOOD
    cp "$MANIFEST" releases/LAST_GOOD.images.yml
    log "=== deploy $RELEASE SUCCESS — recorded as LAST_GOOD ==="
    # Reclaim disk from the SUPERSEDED release's images. Each release pulls the full 13-image set;
    # `compose up` recreates the containers, so the PREVIOUS release's images are now held by no
    # container and pile up until the disk fills (real incident 2026-08-13: 78 GB host hit 100% and
    # Keycloak could not write → logins failed). `docker image prune -af` removes ONLY images with no
    # container (running or stopped): the CURRENT release stays (running stack = the LAST_GOOD
    # rollback target), and any co-located app on this host (blackheart/vector) is untouched because
    # its images are pinned by its own containers. Best-effort — a good deploy is never failed on
    # cleanup; if it errors, the ops-watch disk alert is the backstop.
    before=$(df --output=avail / | tail -1 | tr -dc '0-9')
    docker image prune -af >> deploy.log 2>&1 || log "WARN: post-deploy image prune failed (non-fatal)"
    after=$(df --output=avail / | tail -1 | tr -dc '0-9')
    log "post-deploy prune: freed ~$(( (after - before) / 1024 )) MB (avail $(( after / 1024 )) MB)"
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
# LAST_GOOD's images are almost always already local (they were just running) — a registry
# blip must not block the rollback, so pull is best-effort here.
pull_with_retry releases/LAST_GOOD.images.yml || log "WARN: rollback pull failed — proceeding with local images"
if up_manifest releases/LAST_GOOD.images.yml 2>&1 | tee -a deploy.log \
   && wait_healthy "$HEALTH_TIMEOUT" && smoke; then
  log "=== ROLLED BACK to $(cat LAST_GOOD) — prod healthy on previous release; $RELEASE rejected ==="
  exit 1
fi
log "=== ROLLBACK FAILED — prod is UNHEALTHY, manual intervention required (see deploy.log, backups/) ==="
exit 2
