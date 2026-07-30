package id.co.nativeapp.loyalty.ledger.domain;

/**
 * The append-only {@code gift_card_ledger_entry.entry_type} — matches the migration CHECK
 * constraint.
 */
public enum GiftCardLedgerEntryType {
  LOAD,
  REDEEM,
  REVERSE,
  ADJUST
}
