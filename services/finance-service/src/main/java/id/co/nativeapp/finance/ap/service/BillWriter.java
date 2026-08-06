package id.co.nativeapp.finance.ap.service;

import id.co.nativeapp.finance.ap.domain.Bill;
import id.co.nativeapp.finance.ap.domain.BillLine;
import id.co.nativeapp.finance.ap.domain.BillNotFoundException;
import id.co.nativeapp.finance.ap.domain.BillStateException;
import id.co.nativeapp.finance.ap.domain.BillStatus;
import id.co.nativeapp.finance.ap.domain.Vendor;
import id.co.nativeapp.finance.ap.domain.VendorNotFoundException;
import id.co.nativeapp.finance.ap.repository.BillLineRepository;
import id.co.nativeapp.finance.ap.repository.BillRepository;
import id.co.nativeapp.finance.ap.repository.VendorRepository;
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
 * The {@code @Transactional} unit of work for the {@link Bill} lifecycle — create draft, post, and
 * void. A distinct proxy-invoked bean so the {@code @Transactional} advice + RLS aspect apply (rule
 * 5). On <strong>post</strong> and <strong>void</strong> it posts a balanced double-entry GL entry
 * <em>in the same transaction</em> as the state change, via the SME-pluggable posting-template
 * framework ({@link JournalPostingService#buildEntryFromBreakdown}) — Dr expense / Cr AP (+ Dr
 * input VAT) on post, the contra on void.
 *
 * <p><strong>Expense recognition (accrual) &amp; scope.</strong> Posting recognises the expense in
 * the GL on the bill date, so the bill flows into the GL-derived income statement + balance sheet
 * ({@code 2000 AP}, LIABILITY) automatically. It does NOT feed the dimensional POS {@code /pnl}
 * dashboard read models (those are fed only by {@code SaleRecorded}/{@code ExpenseRecorded}); the
 * authoritative accounting statements are the GL-derived ones. This is a deliberate Phase-2 scope
 * choice (see ADR 0015, the AP mirror of ADR 0014).
 *
 * <p><strong>Idempotency.</strong> The post GL entry's {@code source_event_id} is the bill id
 * (UNIQUE), and post is a DRAFT→POSTED transition guarded to run once (a re-post is a 409), so a
 * duplicate post can never double-post. The void contra uses a fresh event id.
 */
@Component
public class BillWriter {

  /**
   * Input VAT (PPN, recoverable) applied to a taxable bill's subtotal, in basis points (1100 bp =
   * 11%, the Indonesian standard PPN rate confirmed for production — ADR 0042). A future
   * data-driven per-jurisdiction rate table is a later enhancement (see V28 note); until then this
   * single official rate applies, and a taxable bill no longer carries {@code
   * uses_illustrative_rules}.
   */
  static final long INPUT_VAT_BP = 1_100L;

  /** Default payment term (net days) when the caller does not supply one. */
  static final int DEFAULT_PAYMENT_TERM_DAYS = 30;

  private final BillRepository billRepository;
  private final BillLineRepository billLineRepository;
  private final VendorRepository vendorRepository;
  private final JournalPostingService journalPostingService;
  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public BillWriter(
      BillRepository billRepository,
      BillLineRepository billLineRepository,
      VendorRepository vendorRepository,
      JournalPostingService journalPostingService,
      JournalEntryRepository journalEntryRepository,
      JournalLineRepository journalLineRepository,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.billRepository = billRepository;
    this.billLineRepository = billLineRepository;
    this.vendorRepository = vendorRepository;
    this.journalPostingService = journalPostingService;
    this.journalEntryRepository = journalEntryRepository;
    this.journalLineRepository = journalLineRepository;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  /**
   * Creates a DRAFT bill for a vendor. The subtotal is Σ (unit price × quantity) over the lines;
   * when {@code taxable}, input VAT is added at the ILLUSTRATIVE rate. No GL posting yet.
   *
   * @throws VendorNotFoundException if the vendor id is not in the bound tenant
   * @throws IllegalArgumentException if there are no lines or the currency is invalid
   */
  @Transactional
  public UUID createDraft(
      UUID vendorId, String currencyCode, boolean taxable, List<BillLineInput> lineInputs) {
    Objects.requireNonNull(vendorId, "vendorId");
    Objects.requireNonNull(currencyCode, "currencyCode");
    if (lineInputs == null || lineInputs.isEmpty()) {
      throw new IllegalArgumentException("a bill must have at least one line");
    }
    // Validate the vendor exists in THIS tenant (RLS-scoped find).
    Vendor vendor =
        vendorRepository
            .findById(vendorId)
            .orElseThrow(() -> new VendorNotFoundException(vendorId));

    Currency currency = Currency.getInstance(currencyCode);
    String companyId = TenantContext.require().companyId();

    // Compute the subtotal from the raw inputs (Money math — never a float).
    Money subtotal = Money.zero(currency);
    for (BillLineInput input : lineInputs) {
      Money unitPrice = Money.ofMinor(input.unitPriceMinor(), currency);
      subtotal = subtotal.plus(unitPrice.multiply(input.quantity()));
    }
    Money tax = taxable ? subtotal.applyBasisPoints(INPUT_VAT_BP) : Money.zero(currency);

    // VAT is now the official 11% PPN (ADR 0042): a taxable bill is no longer illustrative.
    Bill bill = Bill.draft(vendor.getId(), subtotal, tax, false);
    bill.setCompanyId(companyId);
    billRepository.save(bill);

    int lineNo = 1;
    for (BillLineInput input : lineInputs) {
      Money unitPrice = Money.ofMinor(input.unitPriceMinor(), currency);
      BillLine line =
          BillLine.of(bill.getId(), lineNo++, input.description(), input.quantity(), unitPrice);
      line.setCompanyId(companyId);
      billLineRepository.save(line);
    }
    return bill.getId();
  }

  /**
   * Posts a DRAFT bill: assigns the number + dates, posts the balanced GL entry (Dr expense / Cr AP
   * (+ Dr input VAT)), and links it. The expense is recognised on the bill date (accrual).
   *
   * @param billId the draft bill
   * @param termDays the payment term in days; {@code null} uses {@link #DEFAULT_PAYMENT_TERM_DAYS}
   * @throws BillNotFoundException if the bill is not in the bound tenant
   * @throws BillStateException if the bill is not DRAFT
   */
  @Transactional
  public UUID post(UUID billId, Integer termDays) {
    Bill bill = requireBill(billId);
    if (bill.getStatus() != BillStatus.DRAFT) {
      throw new BillStateException(
          "only a DRAFT bill can be posted; current status=" + bill.getStatus());
    }
    String companyId = TenantContext.require().companyId();
    Instant now = clock.instant();
    LocalDate billDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
    int terms = termDays != null ? termDays : DEFAULT_PAYMENT_TERM_DAYS;
    LocalDate dueDate = billDate.plusDays(terms);
    String period = LedgerPosting.periodOf(now);

    // GUARD the single-base-currency invariant (mirroring AR's code-review M1 / the
    // sale/expense paths' requireConsistentCurrency): a bill whose currency diverges from the
    // period's already-posted GL currency would put two currencies in the period's trial balance
    // and detonate every statement/close read as a 422. Reject BEFORE posting (the backend cannot
    // trust the client just because the console hides the currency toggle).
    requireConsistentGlCurrency(period, bill.total());

    // Dr EXPENSE (net = subtotal) / Dr VAT_INPUT (tax) / Cr AP (total). TAX line zero-omits when
    // the bill is not taxable. source_event_id = bill id (UNIQUE backstop; post runs once).
    JournalEntry glEntry =
        journalPostingService.buildEntryFromBreakdown(
            EventKind.BILL_POSTED,
            period,
            now,
            bill.getId(),
            "AP bill posted",
            bill.isUsesIllustrativeRules(),
            postAmounts(bill));
    persistEntry(glEntry, companyId);

    String number = nextBillNumber(companyId);
    bill.post(number, billDate, dueDate, glEntry.getId());
    billRepository.save(bill);
    return bill.getId();
  }

  /**
   * Voids a bill. A DRAFT voids with no GL effect; a POSTED (unpaid) bill posts a balanced contra
   * GL entry (the inverse of post) before transitioning to VOID.
   *
   * @throws BillNotFoundException if the bill is not in the bound tenant
   * @throws BillStateException if the bill cannot be voided (paid, or already void)
   */
  @Transactional
  public UUID voidBill(UUID billId) {
    Bill bill = requireBill(billId);
    if (!bill.isVoidable()) {
      throw new BillStateException(
          "bill cannot be voided; status=" + bill.getStatus() + " paid=" + bill.paid());
    }
    boolean wasPosted = bill.getStatus() == BillStatus.POSTED;
    String companyId = TenantContext.require().companyId();
    if (wasPosted) {
      Instant now = clock.instant();
      String period = LedgerPosting.periodOf(now);
      // Same single-base-currency guard as post (mirroring AR's code-review W-1): the void contra
      // posts in the current period, which may differ from the post period.
      requireConsistentGlCurrency(period, bill.total());
      JournalEntry contra =
          journalPostingService.buildEntryFromBreakdown(
              EventKind.BILL_VOID,
              period,
              now,
              UUID.randomUUID(),
              "AP bill voided",
              bill.isUsesIllustrativeRules(),
              postAmounts(bill));
      persistEntry(contra, companyId);
    }
    bill.voidBill();
    billRepository.save(bill);
    return bill.getId();
  }

  /** The GROSS/NET/TAX breakdown for a post (or its contra on void). */
  private static Map<String, Money> postAmounts(Bill bill) {
    Map<String, Money> amounts = new LinkedHashMap<>();
    amounts.put("GROSS", bill.total());
    amounts.put("NET", bill.subtotal());
    amounts.put("TAX", bill.tax());
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

  private Bill requireBill(UUID billId) {
    return billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));
  }

  /**
   * Rejects a post whose currency diverges from any journal entry already posted in the period for
   * this tenant (mirroring AR's code-review M1). Runs under RLS (the aspect bound the tenant GUC
   * for this {@code @Transactional} method), so it only sees this tenant's GL.
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
   * Assigns the next per-tenant bill number ({@code BILL-00001}, …). A transaction-scoped advisory
   * lock serialises concurrent posts within the tenant so the count-based sequence is race-free;
   * the {@code UNIQUE(company_id, bill_number)} constraint is the database backstop. The COUNT runs
   * under RLS (the aspect bound the tenant GUC for this {@code @Transactional} method), so it
   * counts only this tenant's numbered bills. Mirrors the AR invoice-numbering precedent.
   */
  private String nextBillNumber(String companyId) {
    jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "ap_bill:" + companyId);
    Long numbered =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM bill WHERE bill_number IS NOT NULL", Long.class);
    long next = (numbered == null ? 0L : numbered) + 1L;
    return "BILL-" + padded(next);
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
