# ADR 0069 — Bill-row pessimistic lock serializes every bill write path

- **Status:** Accepted (2026-08-31)
- **Context epoch:** post-open-bill-lockdown (9712104a), 2026-08-31 functional money-flow audit
- **Precedent:** `RegisterSessionRepository.findWithLockById` (register close, ADR 0036)

## Context

The 2026-08-31 audit found a CRITICAL TOCTOU (findings C1/H1): `Bill.cancel()`'s money invariant
— "no paid lines, no payment-reserved lines" — is a **read of child `bill_line` rows**, while a
PARTIAL split-pay (`markLinesPaidForCash`) and a gateway reservation (`reserveUnpaidLines`) mutate
those child rows via guarded **native UPDATEs that never dirty the parent `bill` row**. Optimistic
`@Version` on `bill` therefore cannot arbitrate a cancel racing those writers: the cancel's line
snapshot goes stale, its `WHERE version=?` still matches, and it commits a CANCELLED bill with a
recorded sale — or a live PSP reservation — stranded on it (double-refund / reconciliation
corruption; for H1, real gateway money that can never capture).

Per-statement fixes (conditional cancel UPDATE with `NOT EXISTS`, line-set `FOR UPDATE`) are
insufficient on their own under READ COMMITTED: each side's subquery reads a snapshot that does
not block on the other side's uncommitted line UPDATEs, so the race only moves.

## Decision

The **`bill` row is the serialization point of every bill write path**. Any transaction that
*changes* or *judges* `bill_line` paid/reserved state MUST load the bill via
`BillRepository#findWithLockById` (`@Lock(PESSIMISTIC_WRITE)`, i.e. `SELECT … FOR UPDATE`) before
touching or reading line state:

- `BillWriter.cancelBill`, `BillWriter.payBill`, `BillWriter.initiatePendingPayment`
- `BillPaymentCaptureWriter.capture`
- `BillPaymentWriter.doAbandon` (both the standalone endpoint and the in-tx self-heals)

The **canonical lock order is `bill` → `bill_line` → `payment`** (the advisory `CashWindowLock`
SHARED acquisition sits between bill and bill_line where present; `RegisterSessionWriter.close`
takes only the advisory EXCLUSIVE + `register_session` row lock and never the bill lock, so no
cycle exists). `appendLines`/`removeLine` stay on optimistic locking: they dirty the bill row
itself (version bump) and the guarded child UPDATEs bump `bill_line.version`, which is sufficient
because their guards are not cross-row reads.

## Consequences

- Cancel-vs-partial-pay and cancel-vs-reserve races now serialize; the loser fails with the
  correct domain exception (`BillHasPaidLinesException` / `BillLineReservedException` /
  `BillNotOpenException`) — pinned by `BillCancelRaceTest` (barrier-raced, exactly-one-winner).
- Bill write paths on the SAME bill are one-at-a-time. Contention is per-bill (a single table's
  tab), so throughput impact is negligible; different bills never contend.
- The lock is intentionally narrow: one row, acquired first, held for one REQUIRES_NEW
  transaction. Any new bill write path MUST follow the same pattern — the invariant is documented
  on `findWithLockById` itself.
