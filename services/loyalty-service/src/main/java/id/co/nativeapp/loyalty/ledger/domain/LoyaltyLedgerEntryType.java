package id.co.nativeapp.loyalty.ledger.domain;

/**
 * The append-only {@code loyalty_ledger_entry.entry_type} — matches the migration CHECK constraint.
 */
public enum LoyaltyLedgerEntryType {
  EARN,
  REDEEM,
  ADJUST,
  REVERSE
}
