-- ADR 0049: the per-outlet toggle for whether the till's operator sign-in requires a PIN. Today
-- (V16 operator_pin) a PIN is ALWAYS required; this table lets an owner/manager mark an outlet as
-- trust-based (employee-pick alone, no PIN). An ABSENT row means require_pin = true — the safe
-- default (today's behavior) — see OutletOperatorPolicyReader#requirePin.
--
-- No FK to org_unit: business_id is a foreign business key into org-service's own tree (rule 1 —
-- no cross-service joins); it is validated only as an opaque UUID here.
--
-- Every Auditable column + FORCE ROW LEVEL SECURITY, identical shape to every other employee-
-- service table (rule 4 + rule 5) — mirrors V16__operator_pin.sql verbatim, renamed.
CREATE TABLE outlet_operator_policy (
    id               UUID         NOT NULL PRIMARY KEY,
    business_id      UUID         NOT NULL,
    require_pin      BOOLEAN      NOT NULL DEFAULT true,

    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(255) NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    updated_by       VARCHAR(255) NOT NULL,
    version          BIGINT       NOT NULL,
    company_id       VARCHAR(64)  NOT NULL
);

COMMENT ON COLUMN outlet_operator_policy.require_pin IS
    'true = operator sign-in requires a PIN (default, today''s behavior); false = trust-based'
    ' employee-pick sign-in, no PIN check.';

ALTER TABLE outlet_operator_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE outlet_operator_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY outlet_operator_policy_tenant_isolation ON outlet_operator_policy
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE UNIQUE INDEX uq_outlet_operator_policy_business ON outlet_operator_policy (company_id, business_id);
