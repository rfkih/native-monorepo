#!/usr/bin/env bash
# Native PROD nightly backup — runs ON THE VPS via cron (ADR 0053 §5 / ADR 0057 Phase 6):
#
#   10 19 * * *  bash $HOME/native-prod/scripts/prod-backup.sh >> $HOME/native-prod/backups/backup.log 2>&1
#   (19:10 UTC = 02:10 WIB, quiet hours)
#
# Produces ONE self-contained encrypted archive per night:
#   backups/nightly/native-<UTC-date>.tar.gz.enc   (AES-256-CBC, PBKDF2, passphrase = BACKUP_PASSPHRASE)
# containing: pg_dump of all 11 databases, a tar of the MinIO data volume (objects do NOT ride
# along in pg_dump — ADR 0048), prod.env (the encrypted-offsite copy ADR 0053 requires — the
# archive itself is the encryption envelope), LAST_GOOD + the release manifests.
#
# The passphrase is the ONE secret that must live outside this box (password manager + the
# offsite pull host). Without it every backup is unreadable — that is the point.
# Retention: 14 nightly archives here; the offsite pull (scripts/prod-backup-pull.ps1) keeps 30.
# Restore: scripts/prod-restore-drill.sh proves an archive actually restores — run it monthly.
set -euo pipefail

DEPLOY_DIR="${NATIVE_PROD_DIR:-$HOME/native-prod}"
KEEP="${BACKUP_KEEP:-14}"
DBS=(org_service restaurant_service carwash_service barbershop_service loyalty_service \
     finance_service entitlement_service employee_service notification_service payment_service keycloak)
cd "$DEPLOY_DIR"
log() { echo "[$(date -u +%FT%TZ)] $*"; }

# Passphrase lives in prod.env; minted on first backup run (idempotent).
if ! grep -q '^BACKUP_PASSPHRASE=' prod.env; then
  log "minting BACKUP_PASSPHRASE into prod.env — copy it to a password manager NOW"
  echo "BACKUP_PASSPHRASE=$(openssl rand -base64 32)" >> prod.env
fi
PASS=$(grep '^BACKUP_PASSPHRASE=' prod.env | head -1 | cut -d= -f2-)

docker inspect -f '{{.State.Status}}' native-prod-postgres 2>/dev/null | grep -q running \
  || { log "FATAL: postgres not running — no backup taken"; exit 1; }

STAMP=$(date -u +%Y%m%d)
STAGE=$(mktemp -d "$DEPLOY_DIR/backups/.stage-XXXXXX")
trap 'rm -rf "$STAGE"' EXIT
mkdir -p backups/nightly "$STAGE/db"

log "dumping ${#DBS[@]} databases"
for db in "${DBS[@]}"; do
  docker exec native-prod-postgres pg_dump -U postgres --no-owner "$db" | gzip > "$STAGE/db/$db.sql.gz"
done

log "snapshotting the MinIO data volume"
docker run --rm -v native-prod_native-prod-minio-data:/data:ro -v "$STAGE":/out alpine \
  tar czf /out/minio-data.tar.gz -C /data . 2>/dev/null

# Disaster-recovery context: secrets + what was running.
cp prod.env "$STAGE/prod.env"
cp LAST_GOOD "$STAGE/LAST_GOOD" 2>/dev/null || true
cp -r releases "$STAGE/releases" 2>/dev/null || true

OUT="backups/nightly/native-$STAMP.tar.gz.enc"
tar czf - -C "$STAGE" . \
  | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass "pass:$PASS" > "$OUT.tmp"
mv "$OUT.tmp" "$OUT"
chmod 600 "$OUT"

# Retention: newest $KEEP archives stay.
ls -1t backups/nightly/native-*.tar.gz.enc 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f

log "backup OK: $OUT ($(du -h "$OUT" | cut -f1)) — $(ls backups/nightly | wc -l) archives retained"
