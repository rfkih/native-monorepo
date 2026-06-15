package id.co.nativeapp.finance.consolidation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Derives the DETERMINISTIC synthetic {@code source_entry_id}s consolidation ledger entries carry
 * (P3d SEAM 3a) — both the PRIMARY elimination ids and the supersession REVERSAL ids. Every id is a
 * type-3 (name-based, MD5) {@link UUID#nameUUIDFromBytes(byte[])} over a DOMAIN-PREFIXED,
 * namespaced string, so a re-close at the same {@code close_run_seq} derives identical ids and the
 * {@code consolidation_ledger.source_entry_id} UNIQUE backstop (plus the {@code
 * ProcessedEventStore} dedupe) makes the whole close idempotent under re-run.
 *
 * <p><strong>Collision-freedom (the design-critique Critical).</strong> The {@code processed_event}
 * store and the synthetic ids share ONE global namespace across the whole finance database — the
 * labor supersession already mints {@code UUID.nameUUIDFromBytes("<postingId>:REVERSAL")} (see
 * {@code id.co.nativeapp.finance.labor.ReversalEventIds}). A consolidation id MUST NOT collide with
 * that, nor with another group's / period's / close-run's consolidation id. Two properties
 * guarantee it:
 *
 * <ol>
 *   <li>a DISTINCT DOMAIN PREFIX ({@code "consolidation-elim:"} / {@code "consolidation-rev:"})
 *       disjoins the entire consolidation namespace from the labor {@code "<postingId>:REVERSAL"}
 *       namespace — no labor string begins with {@code "consolidation-"}; and
 *   <li>the prefix is followed by {@code group_id}, {@code period}, {@code close_run_seq} and, for
 *       a reversal, the GLOBALLY-UNIQUE prior entry id ({@code consolidation_ledger.id} is a {@link
 *       UUID#randomUUID()} primary key). {@code group_id} + the prior entry id make the string
 *       unique across groups and companies; {@code close_run_seq} makes it unique across re-closes.
 * </ol>
 *
 * <p>So {@code nameUUIDFromBytes} over these strings is collision-free in practice (different input
 * bytes -> different MD5 -> different UUID), and the global {@code processed_event} namespace is
 * never shared between a labor reversal and a consolidation entry.
 */
final class ConsolidationEntryIds {

  /** The domain prefix for a PRIMARY elimination entry (disjoint from labor + from reversals). */
  private static final String ELIMINATION_PREFIX = "consolidation-elim:";

  /** The domain prefix for a supersession REVERSAL entry. */
  private static final String REVERSAL_PREFIX = "consolidation-rev:";

  /** The domain prefix for the SEAM-3b simplified CTA / translation-reserve balancing entry. */
  private static final String CTA_PREFIX = "consolidation-cta:";

  /** The domain prefix for a SEAM-3b cross-currency intercompany FX_TRANSLATION_DIFF entry. */
  private static final String FX_DIFF_PREFIX = "consolidation-fxdiff:";

  private ConsolidationEntryIds() {}

  /**
   * The synthetic id for a PRIMARY intercompany elimination, keyed on the {@code (group, period,
   * close_run_seq, intercompany_ref)} that uniquely identifies the eliminated trade in this close
   * run. {@code intercompany_ref} is unique per period within a group (it is the match key), so
   * this is collision-free across the eliminations of one close.
   */
  static UUID forElimination(UUID groupId, String period, int closeRunSeq, String intercompanyRef) {
    String name =
        ELIMINATION_PREFIX + groupId + ":" + period + ":" + closeRunSeq + ":" + intercompanyRef;
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The synthetic id for a supersession REVERSAL of a prior PRIMARY entry. Namespaced by the domain
   * prefix + {@code group_id} + {@code period} + the superseding {@code close_run_seq} + the
   * globally-unique prior entry id — collision-free against the labor reversal namespace and across
   * groups/companies/re-closes (see the class javadoc).
   */
  static UUID forReversal(UUID groupId, String period, int closeRunSeq, UUID priorEntryId) {
    String name =
        REVERSAL_PREFIX
            + groupId
            + ":"
            + period
            + ":"
            + closeRunSeq
            + ":"
            + priorEntryId
            + ":REVERSAL";
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The synthetic id for the SEAM-3b simplified CTA / translation-reserve balancing entry of a
   * close run. There is exactly ONE CTA entry per {@code (group, period, close_run_seq)}, so the
   * key needs no further discriminator; the {@code consolidation-cta:} prefix keeps it disjoint
   * from the elimination/reversal/labor namespaces. Deterministic so a re-run at the same seq is
   * idempotent.
   */
  static UUID forCta(UUID groupId, String period, int closeRunSeq) {
    String name = CTA_PREFIX + groupId + ":" + period + ":" + closeRunSeq;
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The synthetic id for a SEAM-3b cross-currency intercompany FX_TRANSLATION_DIFF entry, keyed on
   * the {@code (group, period, close_run_seq, intercompany_ref)} of the cross-currency pair (one
   * residual per cross-currency IC ref). The {@code consolidation-fxdiff:} prefix keeps it disjoint
   * from every other consolidation/labor namespace.
   */
  static UUID forFxTranslationDiff(
      UUID groupId, String period, int closeRunSeq, String intercompanyRef) {
    String name =
        FX_DIFF_PREFIX + groupId + ":" + period + ":" + closeRunSeq + ":" + intercompanyRef;
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
  }
}
