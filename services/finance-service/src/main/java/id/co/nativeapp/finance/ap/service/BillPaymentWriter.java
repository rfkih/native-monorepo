package id.co.nativeapp.finance.ap.service;

import id.co.nativeapp.finance.ap.domain.Bill;
import id.co.nativeapp.finance.ap.domain.BillNotFoundException;
import id.co.nativeapp.finance.ap.domain.BillPayment;
import id.co.nativeapp.finance.ap.domain.BillStateException;
import id.co.nativeapp.finance.ap.domain.BillStatus;
import id.co.nativeapp.finance.ap.repository.BillPaymentRepository;
import id.co.nativeapp.finance.ap.repository.BillRepository;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.JournalPostingService;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for recording a payment against a {@link Bill}. A
 * distinct proxy-invoked bean (RLS aspect applies). In one transaction it: posts a balanced GL
 * entry (Dr AP / Cr cash-clearing), records the {@link BillPayment}, and advances the bill's {@code
 * paid} total + status. The GL entry is posted only after the overpay / state guards pass, so an
 * invalid payment never leaves a stray journal entry.
 */
@Component
public class BillPaymentWriter {

  private final BillRepository billRepository;
  private final BillPaymentRepository billPaymentRepository;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public BillPaymentWriter(
      BillRepository billRepository,
      BillPaymentRepository billPaymentRepository,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.billRepository = billRepository;
    this.billPaymentRepository = billPaymentRepository;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  /**
   * Records a payment of {@code amountMinor} (in the bill currency) against a POSTED /
   * PARTIALLY_PAID bill.
   *
   * @throws BillNotFoundException if the bill is not in the bound tenant
   * @throws BillStateException if the bill is not payable, or the amount overpays the balance
   */
  @Transactional
  public UUID record(UUID billId, long amountMinor, String method, String idempotencyKey) {
    // Idempotency replay (mirroring AR's code-review C1): if a payment with this key already
    // exists in the tenant, return it WITHOUT posting again. Closes the retry-after-lost-response
    // double-post on partial payments. The per-tenant UNIQUE index (V27) is the backstop against a
    // concurrent identical retry (the loser fails the insert and rolls back — money is never
    // posted twice).
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      Optional<UUID> existing =
          billPaymentRepository.findIdByBillIdAndIdempotencyKey(billId, idempotencyKey);
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    Bill bill =
        billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));
    if (bill.getStatus() != BillStatus.POSTED && bill.getStatus() != BillStatus.PARTIALLY_PAID) {
      throw new BillStateException(
          "only a POSTED or PARTIALLY_PAID bill can be paid; current status=" + bill.getStatus());
    }

    Money amount = Money.ofMinor(amountMinor, bill.getCurrency());
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("payment amount must be strictly positive: " + amount);
    }
    // Guard overpay BEFORE posting so an invalid payment leaves no journal entry (the domain
    // recordPayment below is the backstop).
    if (amount.compareTo(bill.outstanding()) > 0) {
      throw new BillStateException(
          "payment " + amount + " exceeds the outstanding balance " + bill.outstanding());
    }

    String companyId = TenantContext.require().companyId();
    Instant now = clock.instant();
    String period = LedgerPosting.periodOf(now);
    // Same single-base-currency guard as post (mirroring AR's code-review W-1): a payment posts in
    // the CURRENT period (which may differ from the bill's post period) in the bill currency.
    requireConsistentGlCurrency(period, amount);

    // Dr AP / Cr CASH_CLEARING for the payment amount. Fresh source_event_id per payment.
    Map<String, Money> amounts = new LinkedHashMap<>();
    amounts.put("GROSS", amount);
    JournalEntry glEntry =
        journalPostingService.buildEntryFromBreakdown(
            EventKind.BILL_PAYMENT_MADE,
            period,
            now,
            UUID.randomUUID(),
            "AP payment made",
            false,
            amounts);
    glEntry.setCompanyId(companyId);
    journalEntryRepository.saveAndFlush(glEntry);
    for (var line : glEntry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }

    BillPayment payment =
        BillPayment.of(billId, amount, now, method, glEntry.getId(), idempotencyKey);
    payment.setCompanyId(companyId);
    billPaymentRepository.save(payment);

    bill.recordPayment(amount);
    billRepository.save(bill);
    return payment.getId();
  }

  /**
   * Rejects a payment posting whose currency diverges from any journal entry already in the period
   * for this tenant (mirroring AR's code-review W-1). Runs under RLS on the {@code @Transactional}
   * connection, so it sees only this tenant's GL.
   */
  private void requireConsistentGlCurrency(String period, Money amount) {
    String incoming = amount.currency().getCurrencyCode();
    List<String> divergent =
        jdbcTemplate.query(
            "SELECT DISTINCT currency FROM journal_entry WHERE period = ? AND currency <> ?",
            (rs, rowNum) -> rs.getString(1).strip(),
            period,
            incoming);
    if (!divergent.isEmpty()) {
      throw new MismatchedPostingCurrencyException(period, divergent.getFirst(), incoming);
    }
  }
}
