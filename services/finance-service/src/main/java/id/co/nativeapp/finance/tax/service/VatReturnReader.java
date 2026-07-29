package id.co.nativeapp.finance.tax.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.projection.GlTrialBalanceLineView;
import id.co.nativeapp.finance.gl.service.GlTrialBalanceReader;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.tax.domain.NetDirection;
import id.co.nativeapp.finance.tax.domain.TaxFiling;
import id.co.nativeapp.finance.tax.dto.VatReturnResponse;
import id.co.nativeapp.finance.tax.projection.TaxFilingView;
import id.co.nativeapp.finance.tax.repository.TaxFilingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-side service for the PPN (VAT) return (Phase 4 Tax / PPN, ADR 0017). The return is
 * DERIVED from the double-entry GL — exactly the way the income statement / balance sheet are — so
 * there is no separate tax ledger to keep in sync:
 *
 * <ul>
 *   <li><strong>Output VAT</strong> = credit-net of the VAT_OUTPUT account (2200, a LIABILITY,
 *       credit-normal) over the period = {@code Σcredit − Σdebit}.
 *   <li><strong>Input VAT</strong> = debit-net of the VAT_INPUT account (1300, an ASSET,
 *       debit-normal) over the period = {@code Σdebit − Σcredit}.
 *   <li><strong>Net PPN</strong> = output − input → PAYABLE (≥ 0) or CREDITABLE (&lt; 0).
 * </ul>
 *
 * <p>The account codes are resolved from the SME-pluggable {@code role_account_map} (VAT_OUTPUT /
 * VAT_INPUT), not hard-coded, so a remapped chart is followed automatically. The reader wraps {@link
 * GlTrialBalanceReader#read} so it inherits the Σdebit==Σcredit + single-currency assertions.
 *
 * <p><strong>Once a period is filed</strong>, {@link #read} reports the FILED SNAPSHOT (from {@code
 * tax_filing}), not the live GL: filing posts a netting entry that clears the period's 2200/1300 to
 * zero, so the live recompute would (correctly) read zero — the snapshot is what the return actually
 * was. The writer path uses {@link #compute} (the raw live figures) before it posts that netting
 * entry.
 */
@Service
public class VatReturnReader {

  private final GlTrialBalanceReader glTrialBalanceReader;
  private final RoleAccountResolver roleAccountResolver;
  private final TaxFilingRepository taxFilingRepository;
  private final Clock clock;

  public VatReturnReader(
      GlTrialBalanceReader glTrialBalanceReader,
      RoleAccountResolver roleAccountResolver,
      TaxFilingRepository taxFilingRepository,
      Clock clock) {
    this.glTrialBalanceReader = Objects.requireNonNull(glTrialBalanceReader, "glTrialBalanceReader");
    this.roleAccountResolver = Objects.requireNonNull(roleAccountResolver, "roleAccountResolver");
    this.taxFilingRepository = Objects.requireNonNull(taxFilingRepository, "taxFilingRepository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The PPN return for {@code period}: the FILED snapshot if the period is filed, else the live
   * GL-derived figures. Must run inside a tenant-bound scope so RLS applies.
   */
  @Transactional(readOnly = true)
  public VatReturnResponse read(String period) {
    Objects.requireNonNull(period, "period");
    Optional<TaxFilingView> filing =
        taxFilingRepository.findViewByPeriodAndTaxType(period, TaxFiling.TAX_TYPE_PPN);
    if (filing.isPresent()) {
      TaxFilingView f = filing.get();
      return new VatReturnResponse(
          period,
          f.getCurrency().strip(),
          f.getOutputVatMinor(),
          f.getInputVatMinor(),
          f.getNetMinor(),
          f.getNetDirection(),
          true,
          f.getId(),
          f.getStatus());
    }
    VatReturn live = compute(period);
    long signedNet = Math.subtractExact(live.outputVatMinor(), live.inputVatMinor());
    String direction =
        (signedNet >= 0L ? NetDirection.PAYABLE : NetDirection.CREDITABLE).name();
    return new VatReturnResponse(
        period,
        live.currency(),
        live.outputVatMinor(),
        live.inputVatMinor(),
        Math.abs(signedNet),
        direction,
        false,
        null,
        null);
  }

  /**
   * The raw, live GL-derived PPN figures for {@code period} (output/input VAT + the period's GL
   * currency). Used by {@link TaxFilingWriter} to compute the netting entry BEFORE it posts (which
   * clears the accounts). Relies on the caller's tenant-bound transaction (RLS).
   */
  public VatReturn compute(String period) {
    Objects.requireNonNull(period, "period");
    List<GlTrialBalanceLineView> lines = glTrialBalanceReader.read(period);
    Instant asOf = clock.instant();
    String outputCode = roleAccountResolver.resolve(AccountRole.VAT_OUTPUT, asOf);
    String inputCode = roleAccountResolver.resolve(AccountRole.VAT_INPUT, asOf);
    long outputVat = 0L;
    long inputVat = 0L;
    String currency = null;
    for (GlTrialBalanceLineView line : lines) {
      if (currency == null) {
        currency = line.getCurrency().strip();
      }
      String code = line.getAccountCode();
      if (code.equals(outputCode)) {
        outputVat = Math.subtractExact(line.getTotalCreditMinor(), line.getTotalDebitMinor());
      } else if (code.equals(inputCode)) {
        inputVat = Math.subtractExact(line.getTotalDebitMinor(), line.getTotalCreditMinor());
      }
    }
    return new VatReturn(period, currency, outputVat, inputVat);
  }
}
