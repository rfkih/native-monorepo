-- restaurant-service V25 — daily close v2, part 1: once-per-outlet-per-day + per-tender window index
-- (ADR 0038, phase 1). ADR 0036 enforced only one OPEN session per outlet; a day could be opened and
-- closed repeatedly. The daily close is now DAY-FINAL: exactly one session per (company, outlet,
-- business_date), open or closed. A second open (or a reopen) for a day that already has a session is
-- a 409 (surfaced cleanly by the service-layer day probe; this index is the race backstop, exactly as
-- uq_crs_one_open_per_outlet backstops the double-open race).
--
-- NOTE (deploy): this UNIQUE index will fail to build if an outlet already has TWO sessions on the
-- same business_date (legal under ADR 0036's multi-session-per-day). Fresh Testcontainers/CI DBs have
-- none; a long-lived UAT/dev DB may — dedupe (keep the earliest, delete the extras) before applying.
CREATE UNIQUE INDEX uq_crs_one_session_per_outlet_day
    ON cash_register_session (company_id, business_id, business_date);

-- Serves the close's per-tender expected sums (card/QRIS/online): sum sale.amount_minor by tender for
-- an outlet within [opened_at, close). The V21 idx_sale_cash_window is CASH-only (partial); this
-- general (company, outlet, tender, occurred_at) index covers the non-cash tenders too.
CREATE INDEX idx_sale_tender_window
    ON sale (company_id, business_id, tender_type, occurred_at);
