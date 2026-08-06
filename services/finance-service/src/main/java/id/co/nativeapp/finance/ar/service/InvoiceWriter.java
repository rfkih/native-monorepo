package id.co.nativeapp.finance.ar.service;

import id.co.nativeapp.finance.ar.domain.Customer;
import id.co.nativeapp.finance.ar.domain.CustomerNotFoundException;
import id.co.nativeapp.finance.ar.domain.Invoice;
import id.co.nativeapp.finance.ar.domain.InvoiceLine;
import id.co.nativeapp.finance.ar.domain.InvoiceNotFoundException;
import id.co.nativeapp.finance.ar.domain.InvoiceStateException;
import id.co.nativeapp.finance.ar.domain.InvoiceStatus;
import id.co.nativeapp.finance.ar.repository.CustomerRepository;
import id.co.nativeapp.finance.ar.repository.InvoiceLineRepository;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for the {@link Invoice} lifecycle — create draft, issue,
 * and void. A distinct proxy-invoked bean so the {@code @Transactional} advice + RLS aspect apply
 * (rule 5). On <strong>issue</strong> and <strong>void</strong> it posts a balanced double-entry GL
 * entry <em>in the same transaction</em> as the state change, via the SME-pluggable
 * posting-template framework ({@link JournalPostingService#buildEntryFromBreakdown}) — Dr AR / Cr
 * revenue (+ Cr output VAT) on issue, the contra on void.
 *
 * <p><strong>Revenue recognition (accrual) &amp; scope.</strong> Issuing recognises revenue in the
 * GL on the issue date, so the invoice flows into the GL-derived income statement + balance sheet
 * ({@code 1200 AR}, ASSET) automatically. It does NOT feed the dimensional POS {@code /pnl}
 * dashboard read models (those are fed only by {@code SaleRecorded}); the authoritative accounting
 * statements are the GL-derived ones. This is a deliberate Phase-1 scope choice (see ADR 0014).
 *
 * <p><strong>Idempotency.</strong> The issue GL entry's {@code source_event_id} is the invoice id
 * (UNIQUE), and issue is a DRAFT→ISSUED transition guarded to run once (a re-issue is a 409), so a
 * duplicate issue can never double-post. The void contra uses a fresh event id.
 */
@Component
public class InvoiceWriter {

  /**
   * Output VAT (PPN) applied to a taxable invoice's subtotal, in basis points (1100 bp = 11%, the
   * Indonesian standard PPN rate confirmed for production — ADR 0042). A future data-driven
   * per-jurisdiction rate table is a later enhancement (see V25 note); until then this single
   * official rate applies, and a taxable invoice no longer carries {@code uses_illustrative_rules}.
   */
  static final long OUTPUT_VAT_BP = 1_100L;

  /** Default payment term (net days) when the caller does not supply one. */
  static final int DEFAULT_PAYMENT_TERM_DAYS = 30;

  private final InvoiceRepository invoiceRepository;
  private final InvoiceLineRepository invoiceLineRepository;
  private final CustomerRepository customerRepository;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public InvoiceWriter(
      InvoiceRepository invoiceRepository,
      InvoiceLineRepository invoiceLineRepository,
      CustomerRepository customerRepository,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.invoiceRepository = invoiceRepository;
    this.invoiceLineRepository = invoiceLineRepository;
    this.customerRepository = customerRepository;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  /**
   * Creates a DRAFT invoice for a customer. The subtotal is Σ (unit price × quantity) over the
   * lines; when {@code taxable}, output VAT is added at the ILLUSTRATIVE rate. No GL posting yet.
   *
   * @throws CustomerNotFoundException if the customer id is not in the bound tenant
   * @throws IllegalArgumentException if there are no lines or the currency is invalid
   */
  @Transactional
  public UUID createDraft(
      UUID customerId, String currencyCode, boolean taxable, List<InvoiceLineInput> lineInputs) {
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(currencyCode, "currencyCode");
    if (lineInputs == null || lineInputs.isEmpty()) {
      throw new IllegalArgumentException("an invoice must have at least one line");
    }
    // Validate the customer exists in THIS tenant (RLS-scoped find).
    Customer customer =
        customerRepository
            .findById(customerId)
            .orElseThrow(() -> new CustomerNotFoundException(customerId));

    Currency currency = Currency.getInstance(currencyCode);
    String companyId = TenantContext.require().companyId();

    // Compute the subtotal from the raw inputs (Money math — never a float).
    Money subtotal = Money.zero(currency);
    for (InvoiceLineInput input : lineInputs) {
      Money unitPrice = Money.ofMinor(input.unitPriceMinor(), currency);
      subtotal = subtotal.plus(unitPrice.multiply(input.quantity()));
    }
    Money tax = taxable ? subtotal.applyBasisPoints(OUTPUT_VAT_BP) : Money.zero(currency);

    // VAT is now the official 11% PPN (ADR 0042): a taxable invoice is no longer illustrative.
    Invoice invoice = Invoice.draft(customer.getId(), subtotal, tax, false);
    invoice.setCompanyId(companyId);
    invoiceRepository.save(invoice);

    int lineNo = 1;
    for (InvoiceLineInput input : lineInputs) {
      Money unitPrice = Money.ofMinor(input.unitPriceMinor(), currency);
      InvoiceLine line =
          InvoiceLine.of(
              invoice.getId(), lineNo++, input.description(), input.quantity(), unitPrice);
      line.setCompanyId(companyId);
      invoiceLineRepository.save(line);
    }
    return invoice.getId();
  }

  /**
   * Issues a DRAFT invoice: assigns the number + dates, posts the balanced GL entry (Dr AR / Cr
   * revenue (+ Cr output VAT)), and links it. Revenue is recognised on the issue date (accrual).
   *
   * @param invoiceId the draft invoice
   * @param termDays the payment term in days; {@code null} uses {@link #DEFAULT_PAYMENT_TERM_DAYS}
   * @throws InvoiceNotFoundException if the invoice is not in the bound tenant
   * @throws InvoiceStateException if the invoice is not DRAFT
   */
  @Transactional
  public UUID issue(UUID invoiceId, Integer termDays) {
    Invoice invoice = requireInvoice(invoiceId);
    if (invoice.getStatus() != InvoiceStatus.DRAFT) {
      throw new InvoiceStateException(
          "only a DRAFT invoice can be issued; current status=" + invoice.getStatus());
    }
    String companyId = TenantContext.require().companyId();
    Instant now = clock.instant();
    LocalDate issueDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
    int terms = termDays != null ? termDays : DEFAULT_PAYMENT_TERM_DAYS;
    LocalDate dueDate = issueDate.plusDays(terms);
    String period = LedgerPosting.periodOf(now);

    // GUARD the single-base-currency invariant (code-review M1), mirroring the sale/expense paths'
    // requireConsistentCurrency: an invoice whose currency diverges from the period's
    // already-posted
    // GL currency would put two currencies in the period's trial balance and detonate every
    // statement/close read as a 422. Reject BEFORE posting (the backend cannot trust the client
    // just
    // because the console hides the currency toggle).
    requireConsistentGlCurrency(period, invoice.total());

    // Dr AR (total) / Cr REVENUE (net = subtotal) / Cr VAT_OUTPUT (tax). TAX line zero-omits when
    // the invoice is not taxable. source_event_id = invoice id (UNIQUE backstop; issue runs once).
    JournalEntry glEntry =
        journalPostingService.buildEntryFromBreakdown(
            EventKind.INVOICE_ISSUED,
            period,
            now,
            invoice.getId(),
            "AR invoice issued",
            invoice.isUsesIllustrativeRules(),
            issueAmounts(invoice));
    persistEntry(glEntry, companyId);

    String number = nextInvoiceNumber(companyId);
    invoice.issue(number, issueDate, dueDate, glEntry.getId());
    invoiceRepository.save(invoice);
    return invoice.getId();
  }

  /**
   * Voids an invoice. A DRAFT voids with no GL effect; an ISSUED (unpaid) invoice posts a balanced
   * contra GL entry (the inverse of issue) before transitioning to VOID.
   *
   * @throws InvoiceNotFoundException if the invoice is not in the bound tenant
   * @throws InvoiceStateException if the invoice cannot be voided (paid, or already void)
   */
  @Transactional
  public UUID voidInvoice(UUID invoiceId) {
    Invoice invoice = requireInvoice(invoiceId);
    if (!invoice.isVoidable()) {
      throw new InvoiceStateException(
          "invoice cannot be voided; status=" + invoice.getStatus() + " paid=" + invoice.paid());
    }
    boolean wasIssued = invoice.getStatus() == InvoiceStatus.ISSUED;
    String companyId = TenantContext.require().companyId();
    if (wasIssued) {
      Instant now = clock.instant();
      String period = LedgerPosting.periodOf(now);
      // Same single-base-currency guard as issue (code-review W-1): the void contra posts in the
      // current period, which may differ from the issue period.
      requireConsistentGlCurrency(period, invoice.total());
      JournalEntry contra =
          journalPostingService.buildEntryFromBreakdown(
              EventKind.INVOICE_VOID,
              period,
              now,
              UUID.randomUUID(),
              "AR invoice voided",
              invoice.isUsesIllustrativeRules(),
              issueAmounts(invoice));
      persistEntry(contra, companyId);
    }
    invoice.voidInvoice();
    invoiceRepository.save(invoice);
    return invoice.getId();
  }

  /** The GROSS/NET/TAX breakdown for an issue (or its contra on void). */
  private static Map<String, Money> issueAmounts(Invoice invoice) {
    Map<String, Money> amounts = new LinkedHashMap<>();
    amounts.put("GROSS", invoice.total());
    amounts.put("GROSS_REVENUE", invoice.subtotal());
    amounts.put("TAX", invoice.tax());
    return amounts;
  }

  private void persistEntry(JournalEntry entry, String companyId) {
    entry.setCompanyId(companyId);
    // saveAndFlush forces the journal_entry INSERT before the FK'd line INSERTs (same tx).
    journalEntryRepository.saveAndFlush(entry);
    for (var line : entry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }
  }

  private Invoice requireInvoice(UUID invoiceId) {
    return invoiceRepository
        .findById(invoiceId)
        .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
  }

  /**
   * Rejects an issue whose currency diverges from any journal entry already posted in the period
   * for this tenant — the single-base-currency invariant (code-review M1). Runs under RLS (the
   * aspect bound the tenant GUC for this {@code @Transactional} method), so it only sees this
   * tenant's GL.
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

  /**
   * Assigns the next per-tenant invoice number ({@code INV-00001}, …). A transaction-scoped
   * advisory lock serialises concurrent issues within the tenant so the count-based sequence is
   * race-free; the {@code UNIQUE(company_id, invoice_number)} constraint is the database backstop.
   * The COUNT runs under RLS (the aspect bound the tenant GUC for this {@code @Transactional}
   * method), so it counts only this tenant's numbered invoices. Mirrors the within-company-close
   * advisory-lock precedent.
   */
  private String nextInvoiceNumber(String companyId) {
    jdbcTemplate.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))", "ar_invoice:" + companyId);
    Long numbered =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM invoice WHERE invoice_number IS NOT NULL", Long.class);
    long next = (numbered == null ? 0L : numbered) + 1L;
    return "INV-" + padded(next);
  }

  private static String padded(long value) {
    String digits = Long.toString(value);
    StringBuilder sb = new StringBuilder();
    for (int i = digits.length(); i < 5; i++) {
      sb.append('0');
    }
    return sb.append(digits).toString();
  }
}
