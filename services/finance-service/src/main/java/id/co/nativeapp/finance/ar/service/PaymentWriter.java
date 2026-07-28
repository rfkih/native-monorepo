package id.co.nativeapp.finance.ar.service;

import id.co.nativeapp.finance.ar.domain.Invoice;
import id.co.nativeapp.finance.ar.domain.InvoiceNotFoundException;
import id.co.nativeapp.finance.ar.domain.InvoicePayment;
import id.co.nativeapp.finance.ar.domain.InvoiceStateException;
import id.co.nativeapp.finance.ar.domain.InvoiceStatus;
import id.co.nativeapp.finance.ar.repository.InvoicePaymentRepository;
import id.co.nativeapp.finance.ar.repository.InvoiceRepository;
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
 * The {@code @Transactional} unit of work for recording a payment against an {@link Invoice}. A
 * distinct proxy-invoked bean (RLS aspect applies). In one transaction it: posts a balanced GL
 * entry (Dr cash-clearing / Cr AR), records the {@link InvoicePayment}, and advances the invoice's
 * {@code paid} total + status. The GL entry is posted only after the overpay / state guards pass,
 * so an invalid payment never leaves a stray journal entry.
 */
@Component
public class PaymentWriter {

  private final InvoiceRepository invoiceRepository;
  private final InvoicePaymentRepository invoicePaymentRepository;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public PaymentWriter(
      InvoiceRepository invoiceRepository,
      InvoicePaymentRepository invoicePaymentRepository,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.invoiceRepository = invoiceRepository;
    this.invoicePaymentRepository = invoicePaymentRepository;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  /**
   * Records a payment of {@code amountMinor} (in the invoice currency) against an ISSUED /
   * PARTIALLY_PAID invoice.
   *
   * @throws InvoiceNotFoundException if the invoice is not in the bound tenant
   * @throws InvoiceStateException if the invoice is not payable, or the amount overpays the balance
   */
  @Transactional
  public UUID record(UUID invoiceId, long amountMinor, String method, String idempotencyKey) {
    // Idempotency replay (code-review C1): if a payment with this key already exists in the tenant,
    // return it WITHOUT posting again. Closes the retry-after-lost-response double-post on partial
    // payments. The per-tenant UNIQUE index (V26) is the backstop against a concurrent identical
    // retry (the loser fails the insert and rolls back — money is never posted twice).
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      Optional<UUID> existing =
          invoicePaymentRepository.findIdByInvoiceIdAndIdempotencyKey(invoiceId, idempotencyKey);
      if (existing.isPresent()) {
        return existing.get();
      }
    }

    Invoice invoice =
        invoiceRepository
            .findById(invoiceId)
            .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
    if (invoice.getStatus() != InvoiceStatus.ISSUED
        && invoice.getStatus() != InvoiceStatus.PARTIALLY_PAID) {
      throw new InvoiceStateException(
          "only an ISSUED or PARTIALLY_PAID invoice can be paid; current status="
              + invoice.getStatus());
    }

    Money amount = Money.ofMinor(amountMinor, invoice.getCurrency());
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("payment amount must be strictly positive: " + amount);
    }
    // Guard overpay BEFORE posting so an invalid payment leaves no journal entry (the domain
    // recordPayment below is the backstop).
    if (amount.compareTo(invoice.outstanding()) > 0) {
      throw new InvoiceStateException(
          "payment " + amount + " exceeds the outstanding balance " + invoice.outstanding());
    }

    String companyId = TenantContext.require().companyId();
    Instant now = clock.instant();
    String period = LedgerPosting.periodOf(now);
    // Same single-base-currency guard as issue (code-review W-1): a payment posts in the CURRENT
    // period (which may differ from the invoice's issue period) in the invoice currency.
    requireConsistentGlCurrency(period, amount);

    // Dr CASH_CLEARING / Cr AR for the payment amount. Fresh source_event_id per payment.
    Map<String, Money> amounts = new LinkedHashMap<>();
    amounts.put("GROSS", amount);
    JournalEntry glEntry =
        journalPostingService.buildEntryFromBreakdown(
            EventKind.PAYMENT_RECEIVED,
            period,
            now,
            UUID.randomUUID(),
            "AR payment received",
            false,
            amounts);
    glEntry.setCompanyId(companyId);
    journalEntryRepository.saveAndFlush(glEntry);
    for (var line : glEntry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }

    InvoicePayment payment =
        InvoicePayment.of(invoiceId, amount, now, method, glEntry.getId(), idempotencyKey);
    payment.setCompanyId(companyId);
    invoicePaymentRepository.save(payment);

    invoice.recordPayment(amount);
    invoiceRepository.save(invoice);
    return payment.getId();
  }

  /**
   * Rejects a payment posting whose currency diverges from any journal entry already in the period
   * for this tenant (code-review W-1). Runs under RLS on the {@code @Transactional} connection, so
   * it sees only this tenant's GL.
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
