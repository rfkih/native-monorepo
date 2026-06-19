package id.co.nativeapp.finance.gl.domain;

import java.util.Objects;

/**
 * An ordered line in a {@link PostingTemplate}: a (line_no, account_role, side, amount_basis) tuple
 * loaded from the {@code posting_template} reference table. Value type — no JPA mapping.
 *
 * @param lineNo ordering within the template (1-based)
 * @param accountRole the semantic role resolved to a concrete account via {@code role_account_map}
 * @param side whether this line is a DEBIT or CREDIT
 * @param amountBasis the basis for computing the line amount; only {@code "GROSS"} is supported now
 */
public record TemplateLine(int lineNo, AccountRole accountRole, Side side, String amountBasis) {

  /** The entry side (DEBIT or CREDIT) for a posting-template line. */
  public enum Side {
    DEBIT,
    CREDIT
  }

  public TemplateLine {
    Objects.requireNonNull(accountRole, "accountRole");
    Objects.requireNonNull(side, "side");
    Objects.requireNonNull(amountBasis, "amountBasis");
  }
}
