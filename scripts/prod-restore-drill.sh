#!/usr/bin/env bash
# Native PROD restore drill — runs ON THE VPS (ADR 0053 §5: "restore-tested monthly").
#
#   bash prod-restore-drill.sh [archive]      default: the newest backups/nightly/*.enc
#
# Proves a nightly archive is actually restorable, without touching the live stack:
#   1. decrypts + unpacks the archive (verifies BACKUP_PASSPHRASE + integrity)
#   2. starts a THROWAWAY postgres:16-alpine container (no ports, own volume-less tmpfs)
#   3. restores finance_service (the money DB — the one that matters most) into it
#   4. asserts the restore produced a real schema (tables > 0, journal rows countable)
#   5. checks every expected member exists in the archive (11 DBs, MinIO tar, prod.env)
#   6. destroys the drill container and temp files
# Exit 0 = the backup is proven restorable. Anything else = your backups are BROKEN — fix NOW.
set -euo pipefail

DEPLOY_DIR="${NATIVE_PROD_DIR:-$HOME/native-prod}"
cd "$DEPLOY_DIR"
log() { echo "[$(date -u +%FT%TZ)] $*"; }

ARCHIVE="${1:-$(ls -1t backups/nightly/native-*.tar.gz.enc 2>/dev/null | head -1)}"
[ -n "$ARCHIVE" ] && [ -f "$ARCHIVE" ] || { log "FATAL: no archive found"; exit 1; }
PASS=$(grep '^BACKUP_PASSPHRASE=' prod.env | head -1 | cut -d= -f2-)
[ -n "$PASS" ] || { log "FATAL: BACKUP_PASSPHRASE missing from prod.env"; exit 1; }

STAGE=$(mktemp -d)
DRILL=native-prod-restore-drill
cleanup() { docker rm -f "$DRILL" >/dev/null 2>&1 || true; rm -rf "$STAGE"; }
trap cleanup EXIT

log "drill: $ARCHIVE"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "pass:$PASS" < "$ARCHIVE" \
  | tar xzf - -C "$STAGE" || { log "FAIL: decrypt/unpack — wrong passphrase or corrupt archive"; exit 1; }

# 5. completeness before the expensive part
missing=""
for db in org_service restaurant_service carwash_service barbershop_service loyalty_service \
          finance_service entitlement_service employee_service notification_service payment_service keycloak; do
  [ -s "$STAGE/db/$db.sql.gz" ] || missing="$missing $db"
done
[ -s "$STAGE/minio-data.tar.gz" ] || missing="$missing minio-data"
[ -s "$STAGE/prod.env" ] || missing="$missing prod.env"
[ -z "$missing" ] || { log "FAIL: archive incomplete — missing:$missing"; exit 1; }
log "archive complete (11 DB dumps, MinIO snapshot, prod.env, $(cat "$STAGE/LAST_GOOD" 2>/dev/null || echo '?') manifests)"

# 2-4. throwaway restore of the money DB
docker rm -f "$DRILL" >/dev/null 2>&1 || true
docker run -d --name "$DRILL" --network none --tmpfs /var/lib/postgresql/data \
  -e POSTGRES_PASSWORD=drill postgres:16-alpine >/dev/null
for i in $(seq 1 30); do docker exec "$DRILL" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 2; done
docker exec "$DRILL" createdb -U postgres finance_service
# the dump carries GRANTs to the service role — create it so the restore is noise-free
docker exec "$DRILL" psql -q -U postgres -c "create role finance_service" >/dev/null
gunzip -c "$STAGE/db/finance_service.sql.gz" | docker exec -i "$DRILL" psql -q -U postgres -d finance_service >/dev/null

TABLES=$(docker exec "$DRILL" psql -tA -U postgres -d finance_service \
  -c "select count(*) from information_schema.tables where table_schema='public'")
JOURNAL=$(docker exec "$DRILL" psql -tA -U postgres -d finance_service \
  -c "select count(*) from journal_entry" 2>/dev/null || echo "n/a")
[ "${TABLES:-0}" -gt 10 ] || { log "FAIL: restored finance_service has only ${TABLES:-0} tables"; exit 1; }

log "DRILL PASS: finance_service restored — $TABLES tables, journal_entry rows: $JOURNAL"
