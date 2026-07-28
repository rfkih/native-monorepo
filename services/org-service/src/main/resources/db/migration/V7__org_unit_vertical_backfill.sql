-- V7 — redo the V6 vertical backfill under the RLS escape hatch.
--
-- V6's backfill UPDATE ran as the table owner under FORCE ROW LEVEL SECURITY with no
-- tenant GUC bound, so the policy filtered it to ZERO rows and Flyway still reported
-- success (caught on the live dev DB; V6 is already checksummed there, hence a new
-- migration rather than an edit). Only the table owner may drop the forced-policy
-- requirement, and SET LOCAL row_security = off would ERROR for a role subject to RLS
-- rather than bypass it — the correct escape hatch is NO FORCE (fleet precedent:
-- restaurant-service V6). All three statements run in the one migration transaction,
-- and the table ends in the same FORCE state it started in.

ALTER TABLE org_unit NO FORCE ROW LEVEL SECURITY;

UPDATE org_unit
   SET vertical = 'restaurant'
 WHERE type = 'BUSINESS_UNIT'
   AND vertical IS NULL;

ALTER TABLE org_unit FORCE ROW LEVEL SECURITY;
