package id.co.nativeapp.finance.platform.projection;

/**
 * Read projection for the per-channel platform-settlement summary ({@code GET
 * /api/v1/platform-settlements/summary}) — one row per {@code (channel_code, currency)} bucket with
 * the period's settled gross, fee, and net totals plus the settlement count (ADR 0036 Phase C).
 *
 * <p>Backs {@code PlatformSettlementRepository.findSummary}. Lives in its own {@code projection}
 * package — a read model is neither the write-side {@code domain} entity nor a request/response
 * {@code dto} (CODE-STRUCTURE.md §3.3).
 */
public interface PlatformSettlementSummaryView {

  String getChannelCode();

  long getSettledGrossMinor();

  long getFeeMinor();

  long getNetMinor();

  long getSettlementCount();

  String getCurrency();
}
