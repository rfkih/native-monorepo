#!/bin/sh
# MinIO bootstrap (ADR 0048) — run as a one-shot `minio/mc` container after the minio
# service is healthy (compose.dev.yml / compose.uat.yml `minio-init`). IDEMPOTENT: safe
# to re-run on every stack start; every step tolerates already-exists.
#
# What it provisions:
#   - the single per-environment bucket `native-media` (versioned — object history is the
#     backup story's foundation: RUNBOOK "Object-store backup");
#   - anonymous download on the restaurant MENU-IMAGE prefix ONLY (`restaurant/*/menu/*`,
#     served via the gateway's GET-only /api/media/restaurant/** proxy; unguessable
#     content-hash keys). Bill attachments (`restaurant/*/bill/*`, ADR 0063) are business-
#     sensitive and stay PRIVATE — read only through restaurant-service's authenticated
#     stream endpoint. employee/ (expense receipts) and payment/ (QRIS) stay private too;
#   - one prefix-scoped user per service (the storage twin of database-per-service): each
#     service's credentials can touch its OWN prefix and nothing else.
#
# Required env: MINIO_ROOT_USER, MINIO_ROOT_PASSWORD,
#   MEDIA_RESTAURANT_ACCESS_KEY/MEDIA_RESTAURANT_SECRET_KEY,
#   MEDIA_EMPLOYEE_ACCESS_KEY/MEDIA_EMPLOYEE_SECRET_KEY,
#   MEDIA_PAYMENT_ACCESS_KEY/MEDIA_PAYMENT_SECRET_KEY.

set -eu

BUCKET="native-media"

echo "minio-init: waiting for minio and configuring alias"
# The container may win the race against minio's first-boot moment even after the
# healthcheck; retry the alias briefly rather than failing the whole init.
tries=0
until mc alias set native http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1; do
  tries=$((tries + 1))
  if [ "$tries" -ge 30 ]; then
    echo "minio-init: minio unreachable after 30 attempts" >&2
    exit 1
  fi
  sleep 2
done

echo "minio-init: ensuring bucket ${BUCKET} (versioned)"
mc mb --ignore-existing "native/${BUCKET}"
mc version enable "native/${BUCKET}"

# Anonymous access: an EXPLICIT bucket policy granting s3:GetObject on the restaurant MENU-IMAGE
# prefix ONLY (`restaurant/*/menu/*`) — never the `download` preset, which can carry prefix
# ListBucket and would let anonymous clients ENUMERATE keys, defeating the unguessable-content-hash
# model (review W1). Scoped to `/menu/` so BILL attachments (`restaurant/*/bill/*`, ADR 0063) are
# NOT anonymously fetchable — a bill's receipt is business-sensitive and is served only through
# restaurant-service's authenticated endpoint. Our keys are exactly {service}/{company}/{domain}/
# {sha}.{ext}, so only menu keys ever contain "/menu/". Consequence: an anonymous GET of a MISSING
# or non-menu key is 403 (S3 returns 404 only to callers holding ListBucket), which is fine — the
# gateway marks every non-2xx no-store and a browser <img> treats 403 and 404 identically.
echo "minio-init: anonymous GetObject-only policy on restaurant/*/menu/* prefix"
cat > /tmp/anonymous-media.json <<POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {"AWS": ["*"]},
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::${BUCKET}/restaurant/*/menu/*"]
    }
  ]
}
POLICY
mc anonymous set-json /tmp/anonymous-media.json "native/${BUCKET}"

# create_scoped_user <service> <access-key> <secret-key>
# One user + one policy per service, allowed Put/Get/Delete under its own prefix only.
create_scoped_user() {
  svc="$1"
  access_key="$2"
  secret_key="$3"
  policy_name="${svc}-media"
  policy_file="/tmp/${policy_name}.json"

  cat > "$policy_file" <<POLICY
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": ["arn:aws:s3:::${BUCKET}/${svc}/*"]
    }
  ]
}
POLICY

  # `user add` upserts (updates the secret if the user exists); policy create/attach
  # tolerate already-exists so the whole script stays idempotent.
  mc admin user add native "$access_key" "$secret_key"
  mc admin policy create native "$policy_name" "$policy_file" || true
  mc admin policy attach native "$policy_name" --user "$access_key" || true
  echo "minio-init: user ${access_key} scoped to ${BUCKET}/${svc}/*"
}

create_scoped_user restaurant "$MEDIA_RESTAURANT_ACCESS_KEY" "$MEDIA_RESTAURANT_SECRET_KEY"
create_scoped_user employee "$MEDIA_EMPLOYEE_ACCESS_KEY" "$MEDIA_EMPLOYEE_SECRET_KEY"
create_scoped_user payment "$MEDIA_PAYMENT_ACCESS_KEY" "$MEDIA_PAYMENT_SECRET_KEY"

echo "minio-init: done"
