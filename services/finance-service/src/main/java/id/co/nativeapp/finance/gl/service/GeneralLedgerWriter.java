package id.co.nativeapp.finance.gl.service;

import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single sanctioned way to persist a {@link JournalEntry} — the GL's one door.
 *
 * <p><strong>Why this exists.</strong> Before it, ~29 call sites across 28 writer classes each
 * repeated the identical five-line incantation: stamp the tenant on the entry, {@code saveAndFlush}
 * it (forcing the FK target to the DB), then stamp and save every line. {@link
 * JournalPostingService} centralised how an entry is *built*; nothing centralised how it is
 * *written*. That is the {@link <a
 * href="../../../../../../../../../../docs/adr/0065-gl-derived-dashboard-pnl.md">ADR 0065</a>}
 * shape one level down — "every new posting writer must remember to also …" — and it is the reason
 * a per-entry GL event could not be emitted reliably: the emit would have to be copy-pasted 29
 * times, and the 30th writer would forget.
 *
 * <p><strong>The guarantee.</strong> An ArchUnit rule ({@code
 * onlyTheGeneralLedgerWriterPersistsJournals} in {@code config/LayeredArchitectureTest}) forbids
 * any other class from calling a WRITE method ({@code save*}/{@code delete*}/{@code flush}) on
 * {@link JournalEntryRepository} / {@link JournalLineRepository}. It is deliberately a write-side
 * rule, not a no-dependency rule: {@code GlTrialBalanceReader}, {@code BalanceSheetReader}, {@code
 * RegisterCloseWriter}, {@code ReversalPostingWriter}, {@code PayrollLiabilityWriter} and {@code
 * PayrollSettlementWriter} all legitimately READ those repositories (prior-entry lookups for
 * supersession, trial-balance aggregation), and forbidding that would be wrong. So "a posting
 * writer that does not go through this door" is not merely detected — it does not survive the test
 * gate. That structural guarantee, not a numeric assertion, is what makes a future {@code
 * JournalEntryPosted} emission (ADR 0071) complete by construction.
 *
 * <p><strong>Transaction.</strong> {@link Propagation#MANDATORY} — this never opens its own
 * transaction; it joins the calling {@code *Writer}'s, which is where {@code RlsAutoApplyAspect}
 * has bound the tenant GUC. Calling it outside a transaction is a programming error and fails
 * loudly rather than writing rows with an unbound tenant (which FORCE RLS would reject anyway, but
 * late and obscurely).
 */
@Component
public class GeneralLedgerWriter {

  private final JournalEntryRepository journalEntryRepository;
  private final JournalLineRepository journalLineRepository;

  public GeneralLedgerWriter(
      JournalEntryRepository journalEntryRepository, JournalLineRepository journalLineRepository) {
    this.journalEntryRepository =
        Objects.requireNonNull(journalEntryRepository, "journalEntryRepository");
    this.journalLineRepository =
        Objects.requireNonNull(journalLineRepository, "journalLineRepository");
  }

  /**
   * Stamps the tenant onto the entry and its lines and persists both, in the caller's transaction.
   *
   * <p>The entry is {@code saveAndFlush}ed before the lines so the FK {@code journal_line.entry_id
   * → journal_entry.id} has its target in the database — {@link JournalEntry#getLines()} is a
   * {@code @Transient} list assembled in memory by {@link JournalPostingService}, so the lines are
   * not cascaded and must be saved explicitly.
   *
   * @param entry the balanced entry to persist (typically from {@link JournalPostingService})
   * @param companyId the bound tenant, stamped on the entry and every line
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void post(JournalEntry entry, String companyId) {
    Objects.requireNonNull(entry, "entry");
    Objects.requireNonNull(companyId, "companyId");

    entry.setCompanyId(companyId);
    journalEntryRepository.saveAndFlush(entry);
    for (JournalLine line : entry.getLines()) {
      line.setCompanyId(companyId);
      journalLineRepository.save(line);
    }
  }
}
