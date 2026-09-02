#!/usr/bin/env bash
# Native PROD outbox retention (ADR 0071 P1) — runs ON THE VPS, invoked by prod-backup.sh AFTER a
# successful nightly backup (so every pruned row already exists inside at least one encrypted
# archive, here and offsite). Can also be run by hand: bash scripts/prod-outbox-prune.sh
# Synced to the VPS by deploy-prod.yml's script rsync (alongside prod-backup.sh) on every deploy.
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
# SAFETY RAILS (all must hold before a service's outbox is touched):
#   1. The service's Debezium connector AND its task(s) are RUNNING right now. A missing/failed
#      connector may mean unrelayed rows — pruning would destroy them (this is exactly how
#      finance's unrelayed ConsolidationClosed/TrialBalancePublished backlog would have been lost:
#      its connector did not exist until ADR 0071 P0, and its initial snapshot reads the TABLE).
#   2. Only rows older than OUTBOX_KEEP_DAYS (default 30). NOTE the column: `occurred_at` is the
#      producer-supplied DOMAIN time, not the insert time — several writers pass the aggregate's
#      occurred_at rather than clock.instant(). That is safe only because producers clamp
#      backdating far inside 30 days (restaurant clamps sale occurred_at to 48 h — see the
#      SEALED-PERIOD note in finance RevenuePostingWriter); if a producer ever accepts months-old
#      occurred_at values, key this prune on an insert-time column instead.
#   3. This script runs after the backup, so the pruned rows are in tonight's archive regardless
#      (retention: 14 nightly local, 30 offsite — a pruned row stays recoverable ~30 days after
#      pruning, not forever).
#
# Deletes are CHUNKED (LIMIT per transaction) with lock_timeout/statement_timeout so the first run
# — against tables never pruned since inception — cannot hold a giant transaction or flood the WAL
# in one burst. Disk space is returned by autovacuum to the table's free-space map (reused by new
# rows), NOT to the OS; expect `du` to shrink only over time. Consider running the very first prune
# by hand and watching disk + connector lag.
set -euo pipefail

KEEP_DAYS="${OUTBOX_KEEP_DAYS:-30}"
CHUNK="${OUTBOX_PRUNE_CHUNK:-50000}"
CONNECT_CTR="${CONNECT_CONTAINER:-native-prod-connect}"
PG_CTR="${POSTGRES_CONTAINER:-native-prod-postgres}"
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

docker inspect -f '{{.State.Status}}' "$PG_CTR" 2>/dev/null | grep -qx running \
  || { log "postgres not running — nothing pruned"; exit 0; }
docker inspect -f '{{.State.Status}}' "$CONNECT_CTR" 2>/dev/null | grep -qx running \
  || { log "connect not running — CDC state unknown, nothing pruned"; exit 0; }

# True iff the connector exists, has at least one task, and EVERY reported state (the connector's
# own and every task's) is RUNNING. Grep-parsed like ops-watch/prod-deploy.sh (no jq dependency):
# any non-RUNNING state string anywhere in the status payload fails closed.
cdc_healthy() {
  local status
  status=$(docker exec "$CONNECT_CTR" curl -s -m 10 "localhost:8083/connectors/$1/status" 2>/dev/null) || return 1
  echo "$status" | grep -q '"state":"RUNNING"' || return 1
  ! echo "$status" | grep -o '"state":"[A-Z]*"' | grep -qv '"state":"RUNNING"' || return 1
  # at least one TASK state beyond the connector's own (a task-less connector relays nothing)
  [ "$(echo "$status" | grep -o '"state":"RUNNING"' | wc -l)" -ge 2 ]
}

total=0
for db in "${!CONNECTOR[@]}"; do
  conn="${CONNECTOR[$db]}"
  if ! cdc_healthy "$conn"; then
    log "SKIP $db — $conn not fully RUNNING (rows may be unrelayed; fix CDC first)"
    continue
  fi
  db_total=0
  while :; do
    deleted=$(docker exec "$PG_CTR" psql -U postgres -d "$db" -tA \
      -c "SET lock_timeout = '5s'" -c "SET statement_timeout = '5min'" \
      -c "WITH del AS (DELETE FROM outbox WHERE id IN (SELECT id FROM outbox WHERE occurred_at < now() - interval '$KEEP_DAYS days' LIMIT $CHUNK) RETURNING 1) SELECT count(*) FROM del" \
      2>/dev/null | tail -1) || { log "WARN $db: delete chunk failed after $db_total row(s)"; break; }
    case "$deleted" in ''|*[!0-9]*) log "WARN $db: unparseable delete result '$deleted'"; break;; esac
    db_total=$((db_total + deleted))
    [ "$deleted" -lt "$CHUNK" ] && break
  done
  [ "$db_total" -gt 0 ] && log "$db: pruned $db_total row(s) older than ${KEEP_DAYS}d"
  total=$((total + db_total))
done

log "done — $total row(s) pruned fleet-wide (keep ${KEEP_DAYS}d; backup-first; connector-guarded)"
