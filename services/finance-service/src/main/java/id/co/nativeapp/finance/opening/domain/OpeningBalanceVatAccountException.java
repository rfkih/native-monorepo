package id.co.nativeapp.finance.opening.domain;

/**
 * An opening-balance line targeted a VAT CONTROL account (QA sweep 2026-08-05). The PPN return is
 * GL-DERIVED — output 2200 credit-net minus input 1300 debit-net over the period's trial balance
 * with no manual-adjustment layer — so an opening control balance on either account would be
 * counted as period VAT activity and overstate the statutory return (and the filing's netting entry
 * would then strand the opening balance on the control account). A migrated pre-go-live VAT
 * position belongs on a generic liability/asset line instead. Surfaced as {@code 422}.
 */
public class OpeningBalanceVatAccountException extends RuntimeException {

  public OpeningBalanceVatAccountException(String accountCode, String role) {
    super(
        "opening-balance line targets the "
            + role
            + " VAT control account "
            + accountCode
            + " — the GL-derived PPN return would count it as period VAT activity; migrate a"
            + " pre-go-live VAT position as a generic liability (payable) or asset (credit) line"
            + " instead");
  }
}
