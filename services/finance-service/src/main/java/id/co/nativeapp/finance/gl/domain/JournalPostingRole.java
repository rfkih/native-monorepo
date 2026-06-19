package id.co.nativeapp.finance.gl.domain;

/**
 * Whether a {@link JournalEntry} is an original posting or a supersession reversal. {@code PRIMARY}
 * for every entry produced from a first-delivery event; {@code REVERSAL} is reserved for future
 * append-only supersession phases (e.g. labor-run reversal).
 */
public enum JournalPostingRole {
  PRIMARY,
  REVERSAL
}
