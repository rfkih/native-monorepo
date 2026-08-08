# 0048 — MinIO object storage for binary media (menu images, receipts, static QRIS)

Status: Accepted (2026-08-08)

## Context

All binary media lived in Postgres, three different ways: menu images as **base64 data URLs in
`menu_item.image_url` TEXT** (inlined into every menu payload — console, POS till, anonymous
self-order — and stashed wholesale into the till's IndexedDB offline cache: a menu of N items ships
N× up-to-1.5 MB images in one JSON response, with no per-image HTTP caching), and expense receipts
([ADR 0030](0030-employee-expense-claims.md) §8) + the static QRIS image
([ADR 0045](0045-qris-modes-and-payment-service.md)) as **bytea** behind authenticated endpoints.
Both bytea ADRs explicitly deferred "an object store can replace the table". The bytea idiom's
virtues (magic-byte validation, sha256, size caps, RLS-scoped metadata) are worth keeping; its costs
(DB/backup bloat, every byte through the JVM, no cacheability) grow with adoption.

## Decision

**An S3-compatible object store joins the platform layer as *infrastructure*** — alongside
Postgres/Kafka/Redis, never a media-service: a synchronous media-service would violate the "no sync
calls between business services" rule; talking to the store is like talking to your own database.
**MinIO** (tag-pinned community image) is the deployment in every environment today; all code speaks
the **generic S3 API** (AWS SDK v2, `libs/media-storage`), so the store can move to AWS
S3/R2/SeaweedFS with a config change — that generic exit is also the hedge against MinIO's 2025
community-edition slimming (console removed → we administer headless via `mc`; AGPL is a non-issue
for an unmodified backing store).

- **One bucket per environment** (`native-media`), key =
  `{service}/{companyId}/{domain}/{sha256}.{ext}`. The **service prefix is the storage twin of
  database-per-service**: `docker/minio/init.sh` provisions one prefix-scoped user per service, so
  cross-service access is impossible at the store. `companyId` in the key is tenant isolation and
  makes offboarding a prefix delete. **Content-hash keys** make objects immutable (`Cache-Control:
  immutable`, dedup, CDN-ready).
- **Metadata stays in the owning service's DB** (Auditable + RLS + sha256 + byte size + canonical
  content type — the receipt idiom unchanged); only the blob column is replaced by the object key.
  The blob itself is NOT Debezium-audited content: the audited metadata row + immutable
  content-addressed object together are the record.
- **Serving splits by sensitivity.** Menu images are served **anonymously** through a GET-only
  gateway proxy (`/api/media/restaurant/**` → bucket, `anon:media:` IP rate bucket, immutable cache
  headers): an `<img>` cannot carry a bearer token, the identical images already reach anonymous
  self-order diners (ADR 0029), and short-TTL presigned URLs would break the till's offline cache —
  protection is unguessability (256-bit content hash) + the anonymous-download policy covering ONLY
  the `restaurant/` prefix (MinIO would deny `employee/`/`payment/` reads even if a route existed —
  and none does). Receipts and QRIS keep their **authenticated streaming endpoints** unchanged, now
  reading from the store.
- **Uploads stay in-line through the services** (validate magic bytes → sha256 → put → commit row):
  at ≤ 5 MiB caps there is no need for presigned direct uploads, and validation-in-line is the
  established idiom. S3 put precedes DB commit; a rollback's orphan is a harmless content-addressed
  object (no reaper — the fleet's lazy-sweep idiom).
- **Migration is RLS-bound, never Flyway-side**: legacy rows convert inside normal tenant-bound
  transactions (an owner-triggered per-tenant backfill for menu images; read-through migration for
  receipts/QRIS), deliberately avoiding the NO-FORCE-RLS Flyway backfill trap and any cross-tenant
  enumeration.

## Consequences

- **Backups are now ours to provide.** Bytea rode along in `pg_dump`; the store does not. Bucket
  versioning is enabled from day one and the RUNBOOK carries the `mc mirror` recipe — a scheduled
  mirror is REQUIRED before production relies on receipts in the store (receipts are expense
  evidence).
- **No identity-document images** (KTP/NIK scans, rule 6 PII) under this design — that requires
  SSE-KMS encryption and its own ADR before the first such byte is stored.
- Orphaned objects (rolled-back writes, replaced content) are accepted waste, bounded by content
  addressing; cleanup is best-effort delete on replace, never a correctness concern.
- The community MinIO has no web console: all operations are `mc` one-liners (RUNBOOK). Vendor
  drift is a watched risk with a cheap exit (`mc mirror` to any S3 target + config change).
- Per-service media credentials join the secret set (dev: fixed dev creds in compose; UAT:
  generated into `docker/uat.env`).

## Out of scope (future, in order)

Presigned direct-to-store uploads (if sizes ever grow); CDN in front of `/api/media/**`;
server-side image variants/thumbnails; self-order rendering the (already-shipped) menu image URLs;
SSE-KMS + HR document storage.
