-- finance-service V7 (P3d SEAM 3a) — the SINGLE-CURRENCY group CLOSE core.
--
-- SEAM 2 ingested member trial balances into group_trial_balance under the two-GUC CONJUNCTION RLS
-- (group_id::text = app.current_group AND company_id = app.current_tenant). SEAM 3a adds the CLOSE:
-- it consolidates a group's ingested member trial balances for a period — intercompany elimination,
-- the revenue/expense/net roll-up, and APPEND-ONLY supersession (a higher close_run_seq reverses the
-- prior close and reposts) — all in the SAME group-reporting currency. FX translation (a member in a
-- different base currency than the group's reporting currency) is SEAM 3b and is REJECTED cleanly
-- here, never silently summed. Emitting ConsolidationClosed / the group authz endpoint is SEAM 4.
--
-- Three tables, ALL under the SAME two-GUC conjunction RLS as group_trial_balance (a group table is
-- visible/writable ONLY when its group_id matches the bound group AND its company_id matches the
-- bound LEAD tenant), Auditable (company_id = the group's LEAD, rule 4), FORCE RLS so even the owning
-- migration role is bound, Money as integer minor units + ISO-4217 (never a float, rule 8):
--
--   * consolidation_ledger  — the APPEND-ONLY consolidation postings (intercompany ELIMINATION
--                             contras, ADJUSTMENTs, and supersession REVERSALs). Mirrors how the
--                             labor ledger_posting stays append-only with PRIMARY/REVERSAL roles and
--                             a deterministic synthetic reversal source_entry_id (#23). source_entry_id
--                             is UNIQUE — the idempotency backstop behind the close pipeline.
--   * consolidation_summary — the per-(group, period, close_run_seq) roll-up: group_revenue /
--                             group_expense / group_net (Money, all in the reporting currency) + the
--                             close STATE. A higher close_run_seq's summary is a NEW row; the prior
--                             summary flips to the TERMINAL SUPERSEDED state (sticky, mirroring the
--                             PayrollRunLedger SUPERSEDED), so the latest active summary is the close.
--   * intercompany_match    — the intercompany reconciliation for the period: each (intercompany_ref,
--                             period) pairing of two members' related-party legs, with the match
--                             STATE (MATCHED | UNMATCHED | AMOUNT_MISMATCH | MALFORMED |
--                             OUT_OF_SCOPE_BALANCE_SHEET). An UNMATCHED / AMOUNT_MISMATCH / MALFORMED
--                             row blocks the close (money is never eliminated against nothing nor
--                             wrongly); OUT_OF_SCOPE_BALANCE_SHEET (a pure balance-sheet IC ref) is a
--                             NON-BLOCKING skip (3a consolidates only the P&L).
--
-- finance connects as the NON-superuser app role (no BYPASSRLS); FORCE ROW LEVEL SECURITY makes the
-- conjunction bind even the owning role, so no connection bypasses it (rule 5).

-- ---------------------------------------------------------------------------
-- consolidation_ledger — the append-only consolidation postings (conjunction RLS)
-- ---------------------------------------------------------------------------
CREATE TABLE consolidation_ledger (
    id                       UUID         NOT NULL PRIMARY KEY,

    -- The consolidation group this entry belongs to (the SECOND RLS dimension, in conjunction with
    -- the lead tenant company_id below).
    group_id                 UUID         NOT NULL,

    -- The accounting period (YYYY-MM) this close consolidates.
    period                   VARCHAR(7)   NOT NULL,

    -- ELIMINATION (an intercompany contra that nets internal trade to zero), ADJUSTMENT (a manual
    -- consolidation adjustment — modelled for completeness, not produced by 3a's automatic pass), or
    -- REVERSAL (a supersession contra negating a prior PRIMARY entry append-only).
    entry_type               VARCHAR(16)  NOT NULL,

    -- The consolidation GL account the entry posts to (e.g. the intercompany-revenue / -expense
    -- accounts an elimination contras).
    gl_account_code          VARCHAR(32)  NOT NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float (rule 8). amount_minor
    -- may be NEGATIVE (an elimination contra and every REVERSAL carry a negative leg).
    amount_minor             BIGINT       NOT NULL,
    currency                 CHAR(3)      NOT NULL,

    -- The intercompany reference this elimination nets (the (intercompany_ref, period) match key), or
    -- NULL for a non-intercompany entry.
    intercompany_ref         VARCHAR(64)  NULL,

    -- The member company whose leg this entry eliminates/adjusts (a DIMENSION for traceability), or
    -- NULL. NOT the tenant — the owning tenant is the group's lead (company_id below).
    source_member_company_id UUID         NULL,

    -- PRIMARY (an original ELIMINATION/ADJUSTMENT) or REVERSAL (a supersession contra). A REVERSAL
    -- carries a negated amount and a deterministic synthetic source_entry_id (#23 analog).
    posting_role             VARCHAR(16)  NOT NULL,

    -- The close run that produced this entry. A higher close_run_seq for the same (group, period)
    -- supersedes lower ones: it reverses each prior PRIMARY then reposts (append-only).
    close_run_seq            INTEGER      NOT NULL,

    -- The DETERMINISTIC synthetic id for this entry. For a PRIMARY elimination it is derived from
    -- (group, period, close_run_seq, intercompany_ref, role); for a REVERSAL it is a UUIDv3 of a
    -- DOMAIN-PREFIXED namespaced string over the prior entry id (see ConsolidationReversalEventIds) —
    -- globally collision-free against the labor "<postingId>:REVERSAL" namespace and across groups.
    -- UNIQUE is the idempotency backstop: a re-run at the same close_run_seq derives the same ids and
    -- inserts nothing (clean no-op).
    source_entry_id          UUID         NOT NULL,

    -- Auditable (libs/tenant) — company_id is the group's LEAD (the owning tenant; rule 4).
    created_at               TIMESTAMPTZ  NOT NULL,
    created_by               VARCHAR(255) NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    updated_by               VARCHAR(255) NOT NULL,
    version                  BIGINT       NOT NULL,
    company_id               VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_consolidation_ledger_entry_type
        CHECK (entry_type IN ('ELIMINATION', 'ADJUSTMENT', 'REVERSAL')),
    CONSTRAINT ck_consolidation_ledger_posting_role
        CHECK (posting_role IN ('PRIMARY', 'REVERSAL')),

    -- The idempotency backstop: at most one entry per synthetic id. A re-close at the same
    -- close_run_seq derives identical ids and inserts nothing (the ProcessedEventStore dedupe plus
    -- this UNIQUE), so a re-run can never double-post (rule 3).
    CONSTRAINT uq_consolidation_ledger_source_entry UNIQUE (source_entry_id)
);

ALTER TABLE consolidation_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE consolidation_ledger FORCE ROW LEVEL SECURITY;

-- THE CONJUNCTION POLICY (identical to group_trial_balance, V6): a group row is visible/writable ONLY
-- when its group_id matches the bound group AND its company_id matches the bound LEAD tenant. Same
-- expression in USING and WITH CHECK, NULL-safe text equality (current_setting(..., true) is NULL
-- when a GUC is unset), so any unset/partial binding -> false -> matches nothing (fail closed, never
-- errors). The bind point (RlsConnectionInitializer) guarantees canonical-form group ids, so the
-- text comparison is effectively type-exact without a ::uuid cast (which would RAISE on the '' residue
-- a pooled connection leaves after a SET LOCAL group txn).
CREATE POLICY consolidation_ledger_group_isolation ON consolidation_ledger
    USING (
        group_id::text = current_setting('app.current_group', true)
        AND company_id = current_setting('app.current_tenant', true)
    )
    WITH CHECK (
        group_id::text = current_setting('app.current_group', true)
        AND company_id = current_setting('app.current_tenant', true)
    );

-- The close's reversal scan finds the prior close's PRIMARY entries for a (group, period); index it.
CREATE INDEX idx_consolidation_ledger_group_period
    ON consolidation_ledger (group_id, period, close_run_seq);

-- ---------------------------------------------------------------------------
-- consolidation_summary — the per-(group, period, close_run_seq) roll-up (conjunction RLS)
-- ---------------------------------------------------------------------------
CREATE TABLE consolidation_summary (
    id                       UUID         NOT NULL PRIMARY KEY,

    group_id                 UUID         NOT NULL,
    period                   VARCHAR(7)   NOT NULL,

    -- The group's reporting currency (ISO-4217), mirrored from group_ref. In 3a every member's base
    -- currency MUST equal this (the SAME-CURRENCY scope), else the close is MULTI_CURRENCY_UNSUPPORTED.
    reporting_currency       CHAR(3)      NOT NULL,

    -- The consolidated figures — Money as integer minor units + the reporting_currency above. NEVER a
    -- float (rule 8). revenue and expense are the post-elimination roll-ups; net = revenue - expense.
    group_revenue_minor      BIGINT       NOT NULL,
    group_expense_minor      BIGINT       NOT NULL,
    group_net_minor          BIGINT       NOT NULL,

    -- The close lifecycle state (see ConsolidationState):
    --   CLOSED                       — the close finalised (members complete, currencies match,
    --                                  intercompany reconciled, eliminations posted).
    --   PENDING_MEMBERS              — an active member has no ingested trial balance for the period.
    --   MULTI_CURRENCY_UNSUPPORTED   — a member's base currency differs from the reporting currency
    --                                  (FX translation is SEAM 3b — rejected cleanly, never summed).
    --   INTERCOMPANY_UNRECONCILED    — an UNMATCHED / AMOUNT_MISMATCH intercompany leg blocks the close.
    --   PENDING_MEMBERS_WARMING_UP   — the group_member projection is not yet caught up for the group.
    --   SUPERSEDED                   — a higher close_run_seq reversed and replaced this close
    --                                  (TERMINAL / sticky, mirroring PayrollRunLedger SUPERSEDED).
    state                    VARCHAR(32)  NOT NULL,

    -- 3a writes NO FX, so uses_stub_fx is always false here (it becomes meaningful in 3b). Carried so
    -- the column exists uniformly and 3b need not migrate the table again.
    uses_stub_fx             BOOLEAN      NOT NULL DEFAULT false,

    -- Sticky-OR from the member trial balances: true if ANY consolidated member figure is derived
    -- from ILLUSTRATIVE_PLACEHOLDER statutory rules (self-describing for an auditor).
    uses_illustrative_rules  BOOLEAN      NOT NULL DEFAULT false,

    -- The close run that produced this summary. A higher seq supersedes lower ones.
    close_run_seq            INTEGER      NOT NULL,

    -- Auditable (libs/tenant) — company_id is the group's LEAD (the owning tenant; rule 4).
    created_at               TIMESTAMPTZ  NOT NULL,
    created_by               VARCHAR(255) NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    updated_by               VARCHAR(255) NOT NULL,
    version                  BIGINT       NOT NULL,
    company_id               VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_consolidation_summary_state
        CHECK (state IN ('CLOSED', 'PENDING_MEMBERS', 'MULTI_CURRENCY_UNSUPPORTED',
                         'INTERCOMPANY_UNRECONCILED', 'PENDING_MEMBERS_WARMING_UP', 'SUPERSEDED')),

    -- One summary row per close run of a (group, period). The pipeline upserts on it; a higher
    -- close_run_seq is a NEW row (the prior is flipped to SUPERSEDED, never overwritten).
    CONSTRAINT uq_consolidation_summary UNIQUE (group_id, period, close_run_seq)
);

ALTER TABLE consolidation_summary ENABLE ROW LEVEL SECURITY;
ALTER TABLE consolidation_summary FORCE ROW LEVEL SECURITY;

CREATE POLICY consolidation_summary_group_isolation ON consolidation_summary
    USING (
        group_id::text = current_setting('app.current_group', true)
        AND company_id = current_setting('app.current_tenant', true)
    )
    WITH CHECK (
        group_id::text = current_setting('app.current_group', true)
        AND company_id = current_setting('app.current_tenant', true)
    );

-- The supersession scan finds prior ACTIVE summaries for a (group, period); index that access path.
CREATE INDEX idx_consolidation_summary_group_period
    ON consolidation_summary (group_id, period);

-- ---------------------------------------------------------------------------
-- intercompany_match — the per-(group, period, intercompany_ref) reconciliation (conjunction RLS)
-- ---------------------------------------------------------------------------
CREATE TABLE intercompany_match (
    id                       UUID         NOT NULL PRIMARY KEY,

    group_id                 UUID         NOT NULL,
    period                   VARCHAR(7)   NOT NULL,

    -- The intercompany reference being reconciled (the match key with period).
    intercompany_ref         VARCHAR(64)  NOT NULL,

    -- The two member companies whose related-party legs this reference pairs. member_b is NULL for an
    -- UNMATCHED reference (only one member reported the leg).
    member_a                 UUID         NOT NULL,
    member_b                 UUID         NULL,

    -- The two legs' amounts (Money — minor units + currency). A MATCHED pair has equal-and-opposite
    -- (or equal-magnitude) legs; an AMOUNT_MISMATCH has unequal magnitudes. amount_b is NULL for
    -- UNMATCHED. NEVER a float (rule 8).
    amount_a_minor           BIGINT       NOT NULL,
    amount_a_currency        CHAR(3)      NOT NULL,
    amount_b_minor           BIGINT       NULL,
    amount_b_currency        CHAR(3)      NULL,

    -- The two legs' GL account classes. A well-formed (MATCHED) intercompany pair is EXACTLY one
    -- REVENUE-typed leg (the internal sale) + one EXPENSE-typed leg (the internal purchase); the
    -- close eliminates the REVENUE leg from gross revenue and the EXPENSE leg from gross expense
    -- SEPARATELY. account_type_b is NULL for an UNMATCHED reference (only one leg).
    account_type_a           VARCHAR(16)  NOT NULL,
    account_type_b           VARCHAR(16)  NULL,

    -- MATCHED (STRICTLY well-formed one-revenue+one-expense pair, BOTH strictly positive, equal
    -- magnitude) | UNMATCHED (only one leg) | AMOUNT_MISMATCH (one-rev+one-exp positive pair,
    -- magnitudes differ) | MALFORMED (more than two legs, a self-deal, not a one-revenue+one-expense
    -- pair, a ZERO/NEGATIVE leg amount, or a P&L+balance-sheet mix) | OUT_OF_SCOPE_BALANCE_SHEET
    -- (BOTH legs balance-sheet ASSET/LIABILITY/EQUITY). Anything but MATCHED or
    -- OUT_OF_SCOPE_BALANCE_SHEET blocks the close — money is never eliminated against nothing nor
    -- against the wrong account class. OUT_OF_SCOPE_BALANCE_SHEET is a NON-BLOCKING skip: 3a
    -- consolidates only the P&L, so a pure balance-sheet IC ref does not affect the net and is
    -- neither eliminated nor blocking (balance-sheet IC elimination is a future / SEAM 3b concern).
    -- VARCHAR(32) to hold the longest token, 'OUT_OF_SCOPE_BALANCE_SHEET' (25 chars).
    state                    VARCHAR(32)  NOT NULL,

    -- The close run this reconciliation belongs to (regenerated per close run; a higher seq's close
    -- re-derives the match set for the period).
    close_run_seq            INTEGER      NOT NULL,

    -- Auditable (libs/tenant) — company_id is the group's LEAD (the owning tenant; rule 4).
    created_at               TIMESTAMPTZ  NOT NULL,
    created_by               VARCHAR(255) NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    updated_by               VARCHAR(255) NOT NULL,
    version                  BIGINT       NOT NULL,
    company_id               VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_intercompany_match_state
        CHECK (state IN ('MATCHED', 'UNMATCHED', 'AMOUNT_MISMATCH', 'MALFORMED',
                         'OUT_OF_SCOPE_BALANCE_SHEET')),
    CONSTRAINT ck_intercompany_match_account_type_a
        CHECK (account_type_a IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    CONSTRAINT ck_intercompany_match_account_type_b
        CHECK (account_type_b IS NULL
               OR account_type_b IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),

    -- One reconciliation row per (group, period, intercompany_ref, close_run_seq).
    CONSTRAINT uq_intercompany_match
        UNIQUE (group_id, period, intercompany_ref, close_run_seq)
);

ALTER TABLE intercompany_match ENABLE ROW LEVEL SECURITY;
ALTER TABLE intercompany_match FORCE ROW LEVEL SECURITY;

CREATE POLICY intercompany_match_group_isolation ON intercompany_match
    USING (
        group_id::text = current_setting('app.current_group', true)
        AND company_id = current_setting('app.current_tenant', true)
    )
    WITH CHECK (
        group_id::text = current_setting('app.current_group', true)
        AND company_id = current_setting('app.current_tenant', true)
    );

CREATE INDEX idx_intercompany_match_group_period
    ON intercompany_match (group_id, period);

-- ---------------------------------------------------------------------------
-- group_membership_pending — the WARMING-UP marker (NOT Auditable, NOT under RLS)
-- ---------------------------------------------------------------------------
-- The deterministic hook for the close's WARMING-UP gate (the design-critique Critical): a group is
-- held warming-up while a known membership change has not yet been consumed, so the close does not
-- consolidate a STALE member set. A row here means "this group's group_member projection is behind —
-- HOLD the close". Cross-tenant reference infrastructure keyed solely by the globally-unique group_id
-- (exactly like group_lead / chart_of_account / processed_event): NOT Auditable, NOT under RLS, no
-- amount, no PII — only the public group-level "is the projection behind?" signal. The default
-- deployment leaves it empty (a group whose GroupDefined is consumed is caught up); a deployment that
-- tracks a finer membership watermark inserts/clears a row here, and GroupMembershipReadiness reads it.
CREATE TABLE group_membership_pending (
    group_id UUID NOT NULL PRIMARY KEY
);
