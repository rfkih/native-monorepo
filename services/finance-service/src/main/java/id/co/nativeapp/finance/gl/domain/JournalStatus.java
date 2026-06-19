package id.co.nativeapp.finance.gl.domain;

/**
 * Lifecycle status of a {@link JournalEntry}. Only {@code POSTED} is used in this release; {@code
 * PENDING} and {@code VOIDED} are reserved for future phases.
 */
public enum JournalStatus {
  POSTED
}
