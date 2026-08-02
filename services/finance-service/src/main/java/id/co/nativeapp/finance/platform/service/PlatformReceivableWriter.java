package id.co.nativeapp.finance.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The per-channel platform-receivable sub-ledger accumulator (ADR 0036 Phase B). GL account 1250
 * (PLATFORM_RECEIVABLE) is one shared control account; this table carries the per-channel
 * granularity the GL doesn't have — what GoFood vs GrabFood still owes — consumed by the Phase C
 * settlement's outstanding read and its {@code outstanding >= gross} guard.
 *
 * <p>Called INSIDE the caller's {@code @Transactional} unit of work (RevenuePostingWriter accrues
 * on an ONLINE sale, ReversalPostingWriter decrements on void/refund), so the accumulate commits or
 * rolls back atomically with the GL posting and the {@code ProcessedEventStore} claim. The upsert
 * is a single atomic {@code INSERT … ON CONFLICT … DO UPDATE} (the consolidated_revenue idiom) — no
 * read-modify-write window under listener concurrency. RLS applies: the tenant GUC is bound by the
 * aspect on the calling transaction and the policy's {@code WITH CHECK} binds {@code company_id}.
 *
 * <p><strong>Negative balances are TOLERATED by design</strong> (no CHECK constraint, V44): a
 * refund clawback landing after the channel was settled in full legitimately drives the channel
 * negative — the next settlement nets it. Money rule 8: integer minor units + ISO-4217.
 */
@Component
public class PlatformReceivableWriter {

  /** Sub-ledger bucket for an ONLINE sale that arrived with a null channel — never drop money. */
  public static final String UNKNOWN_CHANNEL = "UNKNOWN";

  private static final Logger log = LoggerFactory.getLogger(PlatformReceivableWriter.class);

  private static final String UPSERT_SQL =
      """
      INSERT INTO platform_receivable
          (id, channel_code, currency, outstanding_minor,
           created_at, created_by, updated_at, updated_by, version, company_id)
      VALUES (?, ?, ?, ?, now(), ?, now(), ?, 0, ?)
      ON CONFLICT (company_id, channel_code, currency) DO UPDATE SET
          outstanding_minor = platform_receivable.outstanding_minor + EXCLUDED.outstanding_minor,
          updated_at        = now(),
          updated_by        = EXCLUDED.updated_by,
          version           = platform_receivable.version + 1
      """;

  private final JdbcTemplate jdbcTemplate;

  public PlatformReceivableWriter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Atomically adds {@code deltaMinor} (positive = ONLINE sale accrual, negative = void/refund
   * clawback) to the channel's outstanding balance. A {@code null}/blank channel accumulates under
   * {@link #UNKNOWN_CHANNEL} with a warning — an ONLINE amount must never be dropped just because
   * the producer omitted the channel code.
   *
   * <p>{@code @Transactional(REQUIRED)}: joins the calling writer's transaction (the normal path —
   * atomic with the GL posting) and, when invoked standalone, opens its own so the RLS aspect
   * binds the tenant GUC — a raw unadvised write would fail the {@code WITH CHECK} closed.
   */
  @org.springframework.transaction.annotation.Transactional
  public void accumulate(
      String companyId, String channel, String currency, long deltaMinor, String actor) {
    String channelCode = channel == null || channel.isBlank() ? UNKNOWN_CHANNEL : channel;
    if (UNKNOWN_CHANNEL.equals(channelCode) && !UNKNOWN_CHANNEL.equals(channel)) {
      log.warn(
          "ONLINE amount {} {} arrived with no channel — accumulating under {}",
          deltaMinor,
          currency,
          UNKNOWN_CHANNEL);
    }
    jdbcTemplate.update(
        UPSERT_SQL,
        java.util.UUID.randomUUID(),
        channelCode,
        currency,
        deltaMinor,
        actor,
        actor,
        companyId);
  }
}
