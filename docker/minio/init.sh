#!/bin/sh
# MinIO bootstrap (ADR 0048) — run as a one-shot `minio/mc` container after the minio
# service is healthy (compose.dev.yml / compose.uat.yml `minio-init`). IDEMPOTENT: safe
# to re-run on every stack start; every step tolerates already-exists.
#
# What it provisions:
#   - the single per-environment bucket `native-media` (versioned — object history is the
#     backup story's foundation: RUNBOOK "Object-store backup");
#   - anonymous download on the `restaurant/` prefix ONLY (public menu images, served via
#     the gateway's GET-only /api/media/restaurant/** proxy; unguessable content-hash keys).
#     employee/ (expense receipts) and payment/ (QRIS) stay private — they are read only by
#     their owning service through authenticated endpoints;
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

echo "minio-init: anonymous download on restaurant/ prefix ONLY"
mc anonymous set download "native/${BUCKET}/restaurant"

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
