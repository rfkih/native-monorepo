package id.co.nativeapp.finance.companyexpense.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpense;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseKind;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseLine;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseNotFoundException;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseSealedPeriodException;
import id.co.nativeapp.finance.companyexpense.domain.InvalidCompanyExpenseException;
import id.co.nativeapp.finance.companyexpense.domain.InvalidGlHintException;
import id.co.nativeapp.finance.companyexpense.domain.UnknownBusinessUnitException;
import id.co.nativeapp.finance.companyexpense.messaging.InventoryPurchaseRecordedSchema;
import id.co.nativeapp.finance.companyexpense.repository.CompanyExpenseLineRepository;
import id.co.nativeapp.finance.companyexpense.repository.CompanyExpenseRepository;
import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.projection.JournalLineReversalView;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.inventory.service.PerpetualInventoryReader;
import id.co.nativeapp.finance.mapping.domain.GlAccountResolution;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.orgref.repository.OrgUnitRefRepository;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.pnl.service.PnlReadModelWriter;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.finance.revenue.repository.LedgerPostingRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code @Transactional} unit of work for the company-expense lifecycle (ADR 0072): record
 * (money posted at input, an INVENTORY submit also writes the {@code InventoryPurchaseRecorded}
 * outbox row — one commit, money and the stock instruction together, rule 3) and void (the exact
 * contra of the STORED journal, money-side only).
 *
 * <p><strong>Money legs.</strong> GENERAL: {@code Dr resolveExpense(gl_hint) / Cr CASH_CLEARING}.
 * INVENTORY under the periodic default: {@code Dr COGS / Cr CASH_CLEARING} (owner decision — HPP is
 * meaningful in the periodic P&amp;L). INVENTORY when perpetual-active ({@link
 * PerpetualInventoryReader#isActiveFor}): {@code Dr GRNI_CLEARING / Cr CASH_CLEARING} — mirroring
 * {@code BillWriter}'s split, so the downstream {@code StockReceived} ({@code Dr 1100 / Cr 2050})
 * clears GRNI. GENERAL and INVENTORY-periodic also append the dimensional {@code
 * LedgerPosting(EXPENSE)} and accumulate the P&amp;L read model (the {@code ExpensePostingWriter}
 * recipe); INVENTORY-perpetual writes neither (balance-sheet only — the {@code StockReceivedWriter}
 * stance: the expense arrives later as per-sale COGS).
 *
 * <p><strong>Void = mirror the STORED entry.</strong> The contra re-reads the original journal's
 * lines and swaps debit ↔ credit ({@code JournalLineReversalView} — "finance NEVER recomputes
 * amounts for reversals"), so a perpetual activation between post and void can never mismatch the
 * contra's accounts. The dimensional/P&amp;L legs are negated only if the original wrote them
 * (probed by {@code ledger_posting.source_event_id = expense id}), into the VOID's own period (the
 * {@code ExpenseClaimVoidWriter} cross-period precedent). Stock is NOT auto-reversed (ADR 0072 §4,
 * fix-forward — the UI directs the operator to adjust stock via opname/Atur jumlah).
 *
 * <p><strong>Illustrative provenance is DERIVED, never hardcoded</strong> ({@link
 * RoleAccountResolver#anyIllustrative}): this path runs for every tenant from day one, and a
 * hardcoded {@code true} would permanently flip the audit badge fleet-wide.
 */
@Component
public class CompanyExpenseWriter {

  /**
   * The whitelisted {@code gl_hint} values, mirroring employee-service's {@code
   * ExpenseCategory.GL_HINT_WHITELIST}. A user-facing form fails at input (422) rather than landing
   * on the 9999 suspense account.
   */
  static final Set<String> GL_HINT_WHITELIST = Set.of("", "cogs", "supplies", "utilities");

  /** Sanity cap on ingredient lines per submit (one market run, not a data import). */
  static final int MAX_LINES = 100;

  private final CompanyExpenseRepository expenseRepository;
  private final CompanyExpenseLineRepository lineRepository;
  private final OrgUnitRefRepository orgUnitRefRepository;
  private final LedgerPostingRepository ledgerPostingRepository;
  private final JournalLineRepository journalLineRepository;
  private final GeneralLedgerWriter generalLedgerWriter;
  private final RoleAccountResolver roleAccountResolver;
  private final GlAccountResolver glAccountResolver;
  private final PerpetualInventoryReader perpetualInventoryReader;
  private final PnlReadModelWriter pnlReadModel;
  private final OutboxWriter outboxWriter;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public CompanyExpenseWriter(
      CompanyExpenseRepository expenseRepository,
      CompanyExpenseLineRepository lineRepository,
      OrgUnitRefRepository orgUnitRefRepository,
      LedgerPostingRepository ledgerPostingRepository,
      JournalLineRepository journalLineRepository,
      GeneralLedgerWriter generalLedgerWriter,
      RoleAccountResolver roleAccountResolver,
      GlAccountResolver glAccountResolver,
      PerpetualInventoryReader perpetualInventoryReader,
      PnlReadModelWriter pnlReadModel,
      OutboxWriter outboxWriter,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.expenseRepository = expenseRepository;
    this.lineRepository = lineRepository;
    this.orgUnitRefRepository = orgUnitRefRepository;
    this.ledgerPostingRepository = ledgerPostingRepository;
    this.journalLineRepository = journalLineRepository;
    this.generalLedgerWriter = generalLedgerWriter;
    this.roleAccountResolver = roleAccountResolver;
    this.glAccountResolver = glAccountResolver;
    this.perpetualInventoryReader = perpetualInventoryReader;
    this.pnlReadModel = pnlReadModel;
    this.outboxWriter = outboxWriter;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  /** A validated, normalized submit (the service layer parses the raw DTO into this). */
  public record RecordCommand(
      CompanyExpenseKind kind,
      UUID businessId,
      String glHint,
      String description,
      Money amount,
      Instant occurredAt,
      List<LineCommand> lines,
      String idempotencyKey) {

    /** One validated ingredient line. */
    public record LineCommand(
        UUID ingredientId, String ingredientName, long qtyBase, Money value) {}
  }

  /**
   * Records a POSTED company expense: validates, posts the money journal (+ dimensional/P&amp;L
   * legs per the matrix above), persists the aggregate, and — for INVENTORY — writes the {@code
   * InventoryPurchaseRecorded} outbox row. One transaction.
   *
   * @return the new expense id
   */
  @Transactional
  public UUID record(RecordCommand command) {
    validate(command);
    String companyId = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    UUID businessId = command.businessId();
    if (orgUnitRefRepository.findById(businessId).isEmpty()) {
      throw new UnknownBusinessUnitException(businessId);
    }

    Money amount = command.amount();
    Instant occurredAt = command.occurredAt();
    String period = LedgerPosting.periodOf(occurredAt);
    if (ledgerPostingRepository.sealedPeriodExists(period)) {
      throw new CompanyExpenseSealedPeriodException(period);
    }
    requireConsistentGlCurrency(period, amount);

    UUID expenseId = UUID.randomUUID();
    boolean perpetual =
        command.kind() == CompanyExpenseKind.INVENTORY
            && perpetualInventoryReader.isActiveFor(period);

    // 1) The money journal — Dr <debit account> / Cr CASH_CLEARING, source_event_id = expense id
    //    (UNIQUE — the database backstop behind the idempotency-key replay).
    DebitResolution debit = resolveDebit(command, occurredAt, perpetual);
    String cashClearingCode = requireMapped(AccountRole.CASH_CLEARING, occurredAt);
    boolean usesIllustrative =
        debit.roleResolved()
            ? roleAccountResolver.anyIllustrative(
                occurredAt, debit.role(), AccountRole.CASH_CLEARING)
            : roleAccountResolver.anyIllustrative(occurredAt, AccountRole.CASH_CLEARING);
    UUID entryId = UUID.randomUUID();
    JournalEntry entry =
        JournalEntry.balanced(
            entryId,
            period,
            occurredAt,
            "Company expense recorded",
            amount.currency().getCurrencyCode(),
            expenseId,
            usesIllustrative,
            List.of(
                JournalLine.debit(entryId, 1, debit.accountCode(), amount),
                JournalLine.credit(entryId, 2, cashClearingCode, amount)));
    generalLedgerWriter.post(entry, companyId);

    // 2) Dimensional + P&L legs — GENERAL and INVENTORY-periodic only (INVENTORY-perpetual is
    //    balance-sheet only; the expense arrives later as per-sale COGS).
    if (!perpetual) {
      LedgerPosting posting =
          new LedgerPosting(
              PostingType.EXPENSE, businessId, period, amount, debit.accountCode(), expenseId);
      posting.setCompanyId(companyId);
      ledgerPostingRepository.save(posting);
      pnlReadModel.addExpense(period, amount, companyId, actor);
    }

    // 3) The aggregate + its lines.
    String expenseNo = nextExpenseNumber(companyId);
    CompanyExpense expense =
        CompanyExpense.record(
            expenseId,
            expenseNo,
            command.kind(),
            businessId,
            command.glHint(),
            command.description(),
            amount,
            occurredAt,
            entryId,
            command.idempotencyKey());
    expense.setCompanyId(companyId);
    expenseRepository.save(expense);

    List<CompanyExpenseLine> lines = new ArrayList<>();
    int lineNo = 1;
    for (RecordCommand.LineCommand lineCommand : command.lines()) {
      CompanyExpenseLine line =
          CompanyExpenseLine.of(
              expenseId,
              lineNo++,
              lineCommand.ingredientId(),
              lineCommand.ingredientName(),
              lineCommand.qtyBase(),
              lineCommand.value());
      line.setCompanyId(companyId);
      lineRepository.save(line);
      lines.add(line);
    }

    // 4) INVENTORY: the stock instruction rides the SAME commit as the money (rule 3). line_id is
    //    the consumer's goods_receipt.idempotency_key — the per-line replay anchor.
    if (command.kind() == CompanyExpenseKind.INVENTORY) {
      List<InventoryPurchaseRecordedSchema.Line> wireLines = new ArrayList<>(lines.size());
      for (CompanyExpenseLine line : lines) {
        wireLines.add(
            new InventoryPurchaseRecordedSchema.Line(
                line.getId(), line.getIngredientId(), line.getQtyBase(), line.getValueMinor()));
      }
      outboxWriter.write(
          InventoryPurchaseRecordedSchema.AGGREGATE_TYPE,
          expenseId.toString(),
          InventoryPurchaseRecordedSchema.EVENT_TYPE,
          AvroSerde.serialize(
              InventoryPurchaseRecordedSchema.toRecord(
                  expenseId,
                  InventoryPurchaseRecordedSchema.SOURCE_EXPENSE,
                  companyId,
                  amount.currency().getCurrencyCode(),
                  occurredAt,
                  wireLines)),
          null,
          UUID.fromString(companyId),
          clock.instant());
    }
    return expenseId;
  }

  /**
   * Voids a POSTED expense: posts the exact mirror of the STORED journal (swap debit ↔ credit,
   * fresh source event id), negates the dimensional/P&amp;L legs iff the original wrote them, and
   * transitions the aggregate. Money-side only — stock stays (fix-forward).
   */
  @Transactional
  public UUID voidExpense(UUID expenseId) {
    CompanyExpense expense =
        expenseRepository
            .findById(expenseId)
            .orElseThrow(() -> new CompanyExpenseNotFoundException(expenseId));
    String companyId = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    Instant now = clock.instant();
    String voidPeriod = LedgerPosting.periodOf(now);
    if (ledgerPostingRepository.sealedPeriodExists(voidPeriod)) {
      throw new CompanyExpenseSealedPeriodException(voidPeriod);
    }
    Money amount = expense.amount();
    requireConsistentGlCurrency(voidPeriod, amount);

    // The exact mirror of the stored entry — never recomputed (the reversal idiom).
    List<JournalLineReversalView> originalLines =
        journalLineRepository.findLinesByEntryId(expense.getJournalEntryId());
    if (originalLines.isEmpty()) {
      throw new IllegalStateException(
          "stored journal entry has no lines: " + expense.getJournalEntryId());
    }
    UUID contraEntryId = UUID.randomUUID();
    Currency currency = Currency.getInstance(expense.getCurrency());
    List<JournalLine> contraLines = new ArrayList<>(originalLines.size());
    for (JournalLineReversalView original : originalLines) {
      if (original.getDebitMinor() > 0) {
        contraLines.add(
            JournalLine.credit(
                contraEntryId,
                original.getLineNo(),
                original.getAccountCode(),
                Money.ofMinor(original.getDebitMinor(), currency)));
      } else {
        contraLines.add(
            JournalLine.debit(
                contraEntryId,
                original.getLineNo(),
                original.getAccountCode(),
                Money.ofMinor(original.getCreditMinor(), currency)));
      }
    }
    JournalEntry contra =
        JournalEntry.reversal(
            contraEntryId,
            voidPeriod,
            now,
            "Company expense voided",
            expense.getCurrency(),
            UUID.randomUUID(),
            false,
            contraLines);
    generalLedgerWriter.post(contra, companyId);

    // Negate the dimensional/P&L legs iff the original wrote them (INVENTORY-perpetual did not),
    // into the VOID's own period (the cross-period ExpenseClaimVoidWriter precedent).
    ledgerPostingRepository
        .findBySourceEventId(expense.getId())
        .ifPresent(
            original -> {
              LedgerPosting reversal =
                  new LedgerPosting(
                      PostingType.EXPENSE,
                      expense.getBusinessId(),
                      voidPeriod,
                      amount.negate(),
                      original.getGlAccountCode(),
                      UUID.randomUUID());
              reversal.markAsReversal();
              reversal.setCompanyId(companyId);
              ledgerPostingRepository.save(reversal);
              pnlReadModel.addExpense(voidPeriod, amount.negate(), companyId, actor);
            });

    expense.voidExpense(contraEntryId);
    expenseRepository.save(expense);
    return expense.getId();
  }

  private record DebitResolution(String accountCode, AccountRole role, boolean roleResolved) {}

  private DebitResolution resolveDebit(
      RecordCommand command, Instant occurredAt, boolean perpetual) {
    if (command.kind() == CompanyExpenseKind.GENERAL) {
      GlAccountResolution resolution =
          glAccountResolver.resolveExpense(command.glHint(), occurredAt);
      // The whitelist guarantees mapped=true here; keep the check as a belt for a future hint
      // whose mapping_rule seed is missing (fail loud at input, never suspense on a form).
      if (!resolution.mapped()) {
        throw new InvalidGlHintException(command.glHint());
      }
      return new DebitResolution(resolution.accountCode(), null, false);
    }
    AccountRole role = perpetual ? AccountRole.GRNI_CLEARING : AccountRole.COGS;
    return new DebitResolution(requireMapped(role, occurredAt), role, true);
  }

  private void validate(RecordCommand command) {
    Objects.requireNonNull(command, "command");
    if (command.description() == null || command.description().isBlank()) {
      throw new InvalidCompanyExpenseException("description is required");
    }
    if (command.kind() == CompanyExpenseKind.GENERAL) {
      if (!GL_HINT_WHITELIST.contains(command.glHint())) {
        throw new InvalidGlHintException(command.glHint());
      }
      if (!command.lines().isEmpty()) {
        throw new InvalidCompanyExpenseException("a GENERAL expense carries no ingredient lines");
      }
      if (command.amount().amountMinor() <= 0) {
        throw new InvalidCompanyExpenseException("amount must be strictly positive");
      }
      return;
    }
    // INVENTORY
    if (command.lines().isEmpty()) {
      throw new InvalidCompanyExpenseException(
          "an INVENTORY expense needs at least one ingredient line");
    }
    if (command.lines().size() > MAX_LINES) {
      throw new InvalidCompanyExpenseException("too many lines (max " + MAX_LINES + ")");
    }
    long sum = 0;
    for (RecordCommand.LineCommand line : command.lines()) {
      if (line.qtyBase() <= 0) {
        throw new InvalidCompanyExpenseException("every line qty must be strictly positive");
      }
      if (line.value().amountMinor() < 0) {
        throw new InvalidCompanyExpenseException("a line value cannot be negative");
      }
      if (!line.value().currency().equals(command.amount().currency())) {
        throw new InvalidCompanyExpenseException("every line must be in the expense currency");
      }
      if (line.ingredientName() == null || line.ingredientName().isBlank()) {
        throw new InvalidCompanyExpenseException("every line needs the ingredient name snapshot");
      }
      sum += line.value().amountMinor();
    }
    if (sum <= 0) {
      throw new InvalidCompanyExpenseException("the lines must sum to a positive amount");
    }
    if (sum != command.amount().amountMinor()) {
      throw new InvalidCompanyExpenseException("amount must equal the sum of the line values");
    }
  }

  /** Fail loud on an unmapped role (V13/V50/V53 seed every role used here). */
  private String requireMapped(AccountRole role, Instant occurredAt) {
    String accountCode = roleAccountResolver.resolve(role, occurredAt);
    if (accountCode == null) {
      throw new IllegalStateException(
          "no role_account_map mapping for " + role + " at " + occurredAt);
    }
    return accountCode;
  }

  /**
   * Rejects a post whose currency diverges from any journal entry already posted in the period for
   * this tenant (the BillWriter guard, verbatim). Runs under RLS.
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
   * Assigns the next per-tenant expense number ({@code EXP-00001}, …) under a transaction-scoped
   * advisory lock (the bill-number idiom); {@code UNIQUE(company_id, expense_no)} is the backstop.
   */
  private String nextExpenseNumber(String companyId) {
    jdbcTemplate.queryForList(
        "SELECT pg_advisory_xact_lock(hashtext(?))", "company_expense:" + companyId);
    Long numbered = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM company_expense", Long.class);
    long next = (numbered == null ? 0L : numbered) + 1L;
    return "EXP-" + padded(next);
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
