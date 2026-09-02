#!/usr/bin/env bash
# Native PROD outbox retention (ADR 0071 P1) — runs ON THE VPS, invoked by prod-backup.sh AFTER a
# successful nightly backup (so every pruned row already exists inside at least one encrypted
# archive, here and offsite). Can also be run by hand: bash scripts/prod-outbox-prune.sh
#
# WHY: the outbox is a QUEUE, not an archive (Debezium relays from the WAL; replay/backfill reads
# the owning service's own domain tables — ADR 0071 §backfill). It was never pruned anywhere in the
# fleet, and JournalEntryPosted (one row per GL posting, i.e. every sale) roughly doubles finance's
# outbox write volume — unbounded growth on a 78 GB host that has already taken a disk-full outage.
#
# WHY HERE and not in the app: the fleet convention is NO @Scheduled jobs (ADR 0029 — the lazy-sweep
# idiom), and a DELETE on the hot write path of the fleet's highest-volume table is the wrong place
# for housekeeping. A nightly SQL prune next to the backup is zero application risk (option b of the
# ADR 0071 retention decision).
#
# SAFETY RAILS (all three must hold before a service's outbox is touched):
#   1. The service's Debezium connector task is RUNNING right now. A missing/failed connector means
#      rows may not have been relayed — pruning would destroy them (this is exactly how finance's
#      unrelayed ConsolidationClosed/TrialBalancePublished backlog would have been lost: its
#      connector did not exist until ADR 0071 P0, and its initial snapshot reads the TABLE).
#   2. Only rows older than OUTBOX_KEEP_DAYS (default 30). A row that old and unrelayed would imply
#      a month-long CDC outage — which ops-watch pages about long before this floor is reached.
#   3. This script runs after the backup, so the pruned rows are in tonight's archive regardless.
set -euo pipefail

DEPLOY_DIR="${NATIVE_PROD_DIR:-$HOME/native-prod}"
KEEP_DAYS="${OUTBOX_KEEP_DAYS:-30}"
CONNECT_CTR="${CONNECT_CONTAINER:-native-prod-connect}"
PG_CTR="${POSTGRES_CONTAINER:-native-prod-postgres}"
cd "$DEPLOY_DIR"
log() { echo "[$(date -u +%FT%TZ)] outbox-prune: $*"; }

# db -> its Debezium connector (docker/debezium/*.json `name`s). notification_service has no
# connector (nothing relayed from it today) so it is deliberately absent — never pruned.
declare -A CONNECTOR=(
  [org_service]=org-outbox-connector
  [restaurant_service]=restaurant-outbox-connector
  [carwash_service]=carwash-outbox-connector
  [barbershop_service]=barbershop-outbox-connector
  [loyalty_service]=loyalty-outbox-connector
  [finance_service]=finance-outbox-connector
  [entitlement_service]=entitlement-outbox-connector
  [employee_service]=employee-outbox-connector
  [payment_service]=payment-outbox-connector
)

docker inspect -f '{{.State.Status}}' "$PG_CTR" 2>/dev/null | grep -q running \
  || { log "postgres not running — nothing pruned"; exit 0; }
docker inspect -f '{{.State.Status}}' "$CONNECT_CTR" 2>/dev/null | grep -q running \
  || { log "connect not running — CDC state unknown, nothing pruned"; exit 0; }

task_running() { # $1 = connector name; true iff the connector's TASK state is RUNNING
  docker exec "$CONNECT_CTR" curl -s -m 10 "localhost:8083/connectors/$1/status" 2>/dev/null \
    | grep -o '"state":"[A-Z]*"' | sed -n 2p | grep -q RUNNING
}

total=0
for db in "${!CONNECTOR[@]}"; do
  conn="${CONNECTOR[$db]}"
  if ! task_running "$conn"; then
    log "SKIP $db — $conn task not RUNNING (rows may be unrelayed; fix CDC first)"
    continue
  fi
  deleted=$(docker exec "$PG_CTR" psql -U postgres -d "$db" -tAc \
    "WITH del AS (DELETE FROM outbox WHERE occurred_at < now() - interval '$KEEP_DAYS days' RETURNING 1) SELECT count(*) FROM del" \
    2>/dev/null || echo 0)
  deleted=${deleted:-0}
  [ "$deleted" -gt 0 ] && log "$db: pruned $deleted row(s) older than ${KEEP_DAYS}d"
  total=$((total + deleted))
done

log "done — $total row(s) pruned fleet-wide (keep ${KEEP_DAYS}d; backup-first; connector-guarded)"
