package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.platform.domain.SettlementSourceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The settlement-source kind is what decides which GL account a payout credits (ADR 0076). Getting
 * this mapping wrong books real money against the wrong account, and nothing downstream would
 * notice — the entry still balances.
 */
class SettlementSourceKindTest {

  @Test
  @DisplayName("each kind credits the account its balances actually live in")
  void mapsEachKindToItsAccountRole() {
    assertThat(SettlementSourceKind.MARKETPLACE.accountRole())
        .isEqualTo(AccountRole.PLATFORM_RECEIVABLE);
    assertThat(SettlementSourceKind.QRIS.accountRole()).isEqualTo(AccountRole.QRIS_CLEARING);
    assertThat(SettlementSourceKind.CARD.accountRole()).isEqualTo(AccountRole.CARD_CLEARING);
  }

  @Test
  @DisplayName("the role a kind credits matches the clearing role that tender debited at sale")
  void agreesWithTheClearingRoleTheSaleUsed() {
    // The accrual and the settlement must name the SAME account: the sale debits the clearing role
    // for its tender, the payout credits the role for the row's kind. If these ever disagreed, a
    // balance would accrue in one account and be cleared out of another.
    assertThat(SettlementSourceKind.forTender("ONLINE").accountRole())
        .isEqualTo(AccountRole.clearingRoleForTender("ONLINE"));
    assertThat(SettlementSourceKind.forTender("QRIS").accountRole())
        .isEqualTo(AccountRole.clearingRoleForTender("QRIS"));
    assertThat(SettlementSourceKind.forTender("CARD").accountRole())
        .isEqualTo(AccountRole.clearingRoleForTender("CARD"));
  }

  @Test
  @DisplayName("cash and unknown tenders accrue no receivable — they settle in the drawer")
  void carriesNoSourceForTendersNobodySettles() {
    assertThat(SettlementSourceKind.forTender("CASH")).isNull();
    assertThat(SettlementSourceKind.forTender(null)).isNull();
    assertThat(SettlementSourceKind.forTender("SOMETHING_NEW")).isNull();
  }
}
