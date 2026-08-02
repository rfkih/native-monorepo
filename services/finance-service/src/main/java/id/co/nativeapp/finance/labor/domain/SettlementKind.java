package id.co.nativeapp.finance.labor.domain;

import id.co.nativeapp.finance.gl.domain.AccountRole;

/**
 * The five settleable payroll-liability buckets (ADR 0032, Track P phase P5) — a one-shot payment
 * against exactly one of the five {@link AccountRole} liability roles a {@code
 * PayrollLiabilitiesPosted} run recognised (V40). The {@code payroll_settlement.kind} {@code CHECK}
 * constraint (V41) mirrors this enum's names exactly.
 *
 * <p>Deliberately a SHORTER name set than the underlying {@link AccountRole}s (dropping the {@code
 * _PAYABLE} suffix) — the settlement surface names the MONEY MOVEMENT ("pay the net wages", "remit
 * PPh21"), not the GL account it clears.
 */
public enum SettlementKind {
  NET_WAGES(AccountRole.NET_WAGES_PAYABLE),
  PPH21(AccountRole.PPH21_PAYABLE),
  BPJS_KES(AccountRole.BPJS_KES_PAYABLE),
  BPJS_TK(AccountRole.BPJS_TK_PAYABLE),
  OTHER(AccountRole.OTHER_DEDUCTIONS_PAYABLE);

  private final AccountRole liabilityRole;

  SettlementKind(AccountRole liabilityRole) {
    this.liabilityRole = liabilityRole;
  }

  /** The {@link AccountRole} this settlement kind clears (the Dr leg of the settlement entry). */
  public AccountRole liabilityRole() {
    return liabilityRole;
  }
}
