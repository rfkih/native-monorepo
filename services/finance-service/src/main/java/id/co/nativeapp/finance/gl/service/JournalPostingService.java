package id.co.nativeapp.finance.gl.service;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.EventKind;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.domain.PostingTemplate;
import id.co.nativeapp.finance.gl.domain.TemplateLine;
import id.co.nativeapp.finance.gl.domain.UnbalancedJournalException;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds a balanced {@link JournalEntry} from an {@link EventKind} + gross {@link Money} by
 * resolving the effective {@link PostingTemplate} and its {@link AccountRole} mappings — the
 * "resolve on write" (CQRS) seam between the event consumers and the double-entry GL.
 *
 * <p><strong>SME seam.</strong> The posting rules ({@code posting_template} + {@code
 * role_account_map}) are global-reference DATA, not Java code. An accountant can swap to real COA
 * mappings by inserting higher-version rows; no code change is required. The V13 seeds are all
 * flagged {@code uses_illustrative=true} and carry a loud header comment.
 *
 * <p><strong>Suspense fall-back.</strong> If no template is found for the event kind, or if a role
 * resolves to no account, the service falls back to a two-line suspense entry (Dr SUSPENSE / Cr
 * SUSPENSE for zero net — still balanced but carries the gross on the debit and a matching credit
 * to the same suspense account) with {@code uses_illustrative_rules=true} and logs a warning. Money
 * is never silently dropped (HR-3).
 *
 * <p>Not {@code @Transactional} itself — the caller (the writer bean) owns the transaction
 * boundary. This service assembles the entry in memory; the repository save in the writer persists
 * it inside the writer's {@code @Transactional}.
 */
@Service
public class JournalPostingService {

  private static final Logger log = LoggerFactory.getLogger(JournalPostingService.class);

  private final PostingTemplateResolver templateResolver;
  private final RoleAccountResolver roleResolver;

  public JournalPostingService(
      PostingTemplateResolver templateResolver, RoleAccountResolver roleResolver) {
    this.templateResolver = Objects.requireNonNull(templateResolver, "templateResolver");
    this.roleResolver = Objects.requireNonNull(roleResolver, "roleResolver");
  }

  /**
   * Builds a balanced {@link JournalEntry} for the given event. The entry is NOT yet persisted —
   * the caller must {@code journalEntryRepository.saveAndFlush(entry)} inside a
   * {@code @Transactional} unit of work, then save each line. The caller must also {@code
   * entry.setCompanyId(companyId)} before saving.
   *
   * <p>The entry id is pre-allocated here and shared with the constructed {@link JournalLine}s, so
   * the FK {@code journal_line.entry_id → journal_entry.id} is consistent before any persistence
   * happens. The writer uses {@code saveAndFlush} on the entry to force it to the DB before saving
   * the lines.
   *
   * @param eventKind the event type (drives template resolution)
   * @param gross the gross monetary amount from the event (never a float)
   * @param occurredAt the event's occurred-at instant (drives effective-dated resolution)
   * @param sourceEventId the consumed event UUID (idempotency key; will be UNIQUE in the DB)
   * @param description a human-readable description for the journal header
   * @return a balanced {@link JournalEntry} ready to be saved
   */
  public JournalEntry buildEntry(
      EventKind eventKind,
      Money gross,
      Instant occurredAt,
      UUID sourceEventId,
      String description) {

    Objects.requireNonNull(eventKind, "eventKind");
    Objects.requireNonNull(gross, "gross");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(sourceEventId, "sourceEventId");
    Objects.requireNonNull(description, "description");

    // Pre-allocate the entry id so lines can reference it consistently before persistence.
    UUID entryId = UUID.randomUUID();
    String period = LedgerPosting.periodOf(occurredAt);
    String currency = gross.currency().getCurrencyCode();

    PostingTemplate template = templateResolver.resolve(eventKind, occurredAt);
    if (template == null) {
      log.warn(
          "No posting_template found for eventKind={} occurredAt={}; routing to suspense"
              + " (uses_illustrative=true) — money is NOT dropped, entry will be balanced",
          eventKind,
          occurredAt);
      return buildSuspenseEntry(
          entryId, period, occurredAt, description, currency, sourceEventId, gross);
    }

    List<JournalLine> lines = new ArrayList<>(template.lines().size());

    for (TemplateLine tl : template.lines()) {
      String accountCode = roleResolver.resolve(tl.accountRole(), occurredAt);
      if (accountCode == null) {
        log.warn(
            "No role_account_map found for role={} eventKind={} occurredAt={};"
                + " routing line {} to suspense — money NOT dropped",
            tl.accountRole(),
            eventKind,
            occurredAt,
            tl.lineNo());
        accountCode = RoleAccountResolver.SUSPENSE_ACCOUNT_CODE;
      }

      Money amount = resolveAmount(tl.amountBasis(), gross);
      JournalLine line =
          switch (tl.side()) {
            case DEBIT -> JournalLine.debit(entryId, tl.lineNo(), accountCode, amount);
            case CREDIT -> JournalLine.credit(entryId, tl.lineNo(), accountCode, amount);
          };
      lines.add(line);
    }

    try {
      return JournalEntry.balanced(
          entryId,
          period,
          occurredAt,
          description,
          currency,
          sourceEventId,
          template.usesIllustrative(),
          lines);
    } catch (UnbalancedJournalException e) {
      log.error(
          "Posting template for eventKind={} produced an unbalanced entry (misconfigured seed"
              + " data); falling back to suspense entry — money NOT dropped",
          eventKind,
          e);
      return buildSuspenseEntry(
          entryId, period, occurredAt, description, currency, sourceEventId, gross);
    }
  }

  /**
   * Resolves a line amount from the amount basis. Only {@code "GROSS"} is supported in this phase;
   * {@code NET} / {@code TAX} / {@code RATE:<pct>} are reserved for the SME/tax phase.
   */
  private static Money resolveAmount(String amountBasis, Money gross) {
    if ("GROSS".equalsIgnoreCase(amountBasis)) {
      return gross;
    }
    throw new IllegalStateException(
        "Unsupported amount_basis: '"
            + amountBasis
            + "' — only GROSS is implemented in this phase");
  }

  /**
   * Builds a two-line suspense entry (Dr SUSPENSE / Cr SUSPENSE). Both lines use the same suspense
   * account so the entry is trivially balanced (Σdr == Σcr == gross). Used when no template is
   * seeded or a mis-seeded template is rejected by the balanced invariant. Money is never dropped;
   * the suspense balance is an operator signal to fix the seed data.
   *
   * <p>The caller passes the pre-allocated {@code entryId} so the lines' FK is consistent with the
   * entry id that will be saved.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  private JournalEntry buildSuspenseEntry(
      UUID entryId,
      String period,
      Instant occurredAt,
      String description,
      String currency,
      UUID sourceEventId,
      Money gross) {

    String suspense = RoleAccountResolver.SUSPENSE_ACCOUNT_CODE;
    List<JournalLine> lines =
        List.of(
            JournalLine.debit(entryId, 1, suspense, gross),
            JournalLine.credit(entryId, 2, suspense, gross));

    return JournalEntry.balanced(
        entryId,
        period,
        occurredAt,
        description + " [SUSPENSE — no template]",
        currency,
        sourceEventId,
        true, // always illustrative for a suspense fallback
        lines);
  }
}
