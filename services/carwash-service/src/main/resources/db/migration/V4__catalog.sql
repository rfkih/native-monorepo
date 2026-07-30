-- carwash-service V4 — POS-parity catalog: the priced items a ticket can be built from, and the
-- washer directory tickets attribute their bay-work to.
--
--   1. wash_package  — the wash offerings themselves (e.g. "Basic Wash", "Premium Wash").
--   2. wash_addon    — the upsell catalog layered on top of a package (wax, interior, engine wash).
--   3. staff_profile — the tenant's washer directory, optionally linked to the local `staff`
--                      read model (V1) so the sales_amount@employee commission metric can
--                      attribute a ticket to the washer who worked it.
--
-- Every table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a <table>_tenant_isolation
-- policy keyed to app.current_tenant, exactly as V1. Money stored as BIGINT minor units + a CHAR(3)
-- currency column — never a float (rule 8).

-- ---------------------------------------------------------------------------
-- 1. wash_package
-- ---------------------------------------------------------------------------
CREATE TABLE wash_package (
    id             UUID         NOT NULL PRIMARY KEY,

    -- The carwash outlet (org_unit) this package is offered at.
    business_id    UUID         NOT NULL,

    name           VARCHAR(255) NOT NULL,
    description    VARCHAR(1024) NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    price_minor    BIGINT       NOT NULL,
    currency       CHAR(3)      NOT NULL,

    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order  INT          NOT NULL DEFAULT 0,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     VARCHAR(255) NOT NULL,
    version        BIGINT       NOT NULL,
    company_id     VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_wash_package_price_minor_nonneg CHECK (price_minor >= 0)
);

ALTER TABLE wash_package ENABLE ROW LEVEL SECURITY;
ALTER TABLE wash_package FORCE ROW LEVEL SECURITY;

CREATE POLICY wash_package_tenant_isolation ON wash_package
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Hot access path: the checkout picker lists active packages for an outlet, ordered for display.
-- Partial index (WHERE active) keeps it small — retired packages fall out of the index entirely.
CREATE INDEX idx_wash_package_active
    ON wash_package (company_id, business_id)
    WHERE active = TRUE;

-- ---------------------------------------------------------------------------
-- 2. wash_addon — the upsell catalog (wax, interior, engine wash); identical shape to wash_package.
-- ---------------------------------------------------------------------------
CREATE TABLE wash_addon (
    id             UUID         NOT NULL PRIMARY KEY,

    -- The carwash outlet (org_unit) this addon is offered at.
    business_id    UUID         NOT NULL,

    name           VARCHAR(255) NOT NULL,
    description    VARCHAR(1024) NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    price_minor    BIGINT       NOT NULL,
    currency       CHAR(3)      NOT NULL,

    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order  INT          NOT NULL DEFAULT 0,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     VARCHAR(255) NOT NULL,
    version        BIGINT       NOT NULL,
    company_id     VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_wash_addon_price_minor_nonneg CHECK (price_minor >= 0)
);

ALTER TABLE wash_addon ENABLE ROW LEVEL SECURITY;
ALTER TABLE wash_addon FORCE ROW LEVEL SECURITY;

CREATE POLICY wash_addon_tenant_isolation ON wash_addon
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Same access path as wash_package: the checkout upsell picker for an outlet.
CREATE INDEX idx_wash_addon_active
    ON wash_addon (company_id, business_id)
    WHERE active = TRUE;

-- ---------------------------------------------------------------------------
-- 3. staff_profile — the tenant's washer directory
-- ---------------------------------------------------------------------------
-- display_label is tenant-entered (e.g. "Budi", "Bay 2 crew") — the events this service consumes
-- and publishes carry no PII (rule 6), so this is deliberately a free-text label the tenant chooses,
-- not an employee name sourced from HR data. employee_id OPTIONALLY links to the local `staff` read
-- model row (V1: employee_id -> {org_unit_id, active}, kept current by the EmployeeChanged /
-- AssignmentChanged consumer) so that a ticket recorded against this profile can attribute the
-- sales_amount@employee commission metric to the washer, without this service ever holding or
-- displaying employee PII itself.
CREATE TABLE staff_profile (
    id             UUID         NOT NULL PRIMARY KEY,

    -- The carwash outlet (org_unit) this washer profile belongs to.
    business_id    UUID         NOT NULL,

    -- Tenant-entered label identifying the washer at the till. No PII (rule 6).
    display_label  VARCHAR(128) NOT NULL,

    -- Optional link to the local staff read model (V1 `staff.employee_id`) for commission
    -- attribution. No FK: `staff` rows arrive asynchronously via EmployeeChanged/AssignmentChanged
    -- and may not exist yet when a profile is created; the link is opportunistic, not enforced.
    employee_id    UUID         NULL,

    active         BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     VARCHAR(255) NOT NULL,
    version        BIGINT       NOT NULL,
    company_id     VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_staff_profile_company_business_label
        UNIQUE (company_id, business_id, display_label)
);

ALTER TABLE staff_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_profile FORCE ROW LEVEL SECURITY;

CREATE POLICY staff_profile_tenant_isolation ON staff_profile
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Hot access path: the checkout washer picker lists active profiles for an outlet.
CREATE INDEX idx_staff_profile_active
    ON staff_profile (company_id, business_id)
    WHERE active = TRUE;
