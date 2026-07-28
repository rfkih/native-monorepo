-- V6 — access-path indexes for the console HR read endpoints (indexes only — no data
-- change, so the FORCE-RLS migration-backfill trap does not apply here).
--
-- GET /api/v1/employees?orgUnitIds=... filters assignments by org unit + effective window
-- (one row per employee x CURRENT assignment in scope), and derives has_compensation via an
-- EXISTS probe on comp_package by employee + effective window.

CREATE INDEX idx_assignment_org_unit_eff
    ON assignment (org_unit_id, effective_from, effective_to);

CREATE INDEX idx_comp_package_employee_eff
    ON comp_package (employee_id, effective_from, effective_to);
