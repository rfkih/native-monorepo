package id.co.nativeapp.finance.platform.service;

import id.co.nativeapp.finance.platform.domain.SettlementSourceKind;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads which sub-ledger row a sale's receivable accrues to (ADR 0076): its {@link
 * SettlementSourceKind}, the stable {@code channel_code} that keys the row, and the {@code
 * source_code} naming who will pay it out.
 *
 * <p>Marketplace needs no configuration — the payer IS the channel (ShopeeFood is paid by Shopee).
 * QRIS and card do: a merchant's QR is issued by one acquirer, and which one is a fact only the
 * merchant knows. That fact lives in {@code settlement_source_config} (V62), a finance-owned row,
 * because the sales-channel CRUD is restaurant-service's table and finance may neither read it
 * (hard rule 1) nor call for it (hard rule 2).
 *
 * <p><strong>The channel_code for a tender source is STABLE</strong> ({@code TENDER:QRIS}), never
 * derived from the configured payer. Deriving it would mean that re-pointing QRIS from one acquirer
 * to another created a SECOND row and stranded the balance on the first; instead one row exists per
 * tender family and its {@code source_code} moves with the configuration.
 */
@Component
public class SettlementSourceReader {

  /** Sub-ledger bucket for an ONLINE sale that arrived with a null channel — never drop money. */
  public static final String UNKNOWN_CHANNEL = PlatformReceivableWriter.UNKNOWN_CHANNEL;

  /**
   * The stable channel_code for each tender family. The colon cannot collide with a sales-channel
   * code (those are platform names) while keeping V61's unique key
   * {@code (company_id, channel_code, currency)} untouched — which is what makes the whole change
   * safe to roll back.
   */
  private static final String QRIS_CHANNEL = "TENDER:QRIS";

  private static final String CARD_CHANNEL = "TENDER:CARD";

  private static final String SELECT_SOURCE_SQL =
      """
      SELECT source_code
        FROM settlement_source_config
       WHERE company_id = ?
         AND source_kind = ?
      """;

  private final JdbcTemplate jdbcTemplate;

  public SettlementSourceReader(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Where a sale's receivable belongs, or {@code null} when the tender carries none (CASH, an
   * unknown tender, a legacy null-tender sale — those settle in the drawer, not through a payer).
   *
   * <p>Joins the caller's transaction so the RLS aspect has already bound the tenant GUC; a read
   * outside one would match zero rows against the FORCE-RLS policy and silently look "unconfigured".
   */
  @Transactional(readOnly = true)
  public SettlementSourceRef resolve(String companyId, String tenderType, String channel) {
    SettlementSourceKind kind = SettlementSourceKind.forTender(tenderType);
    if (kind == null) {
      return null;
    }
    return switch (kind) {
      case MARKETPLACE -> {
        String channelCode = channel == null || channel.isBlank() ? UNKNOWN_CHANNEL : channel;
        yield new SettlementSourceRef(kind, channelCode, channelCode);
      }
      // Unconfigured is a valid state, not an error: the balance simply settles under its own name
      // until the merchant says who pays it. Configuring a payer MERGES it into that payer's
      // payout; it is never a precondition for the money being booked correctly.
      case QRIS -> new SettlementSourceRef(kind, QRIS_CHANNEL, configuredSource(companyId, kind));
      case CARD -> new SettlementSourceRef(kind, CARD_CHANNEL, configuredSource(companyId, kind));
    };
  }

  /**
   * Every tender family the merchant has named a payer for, alphabetical. Families with no row are
   * simply absent — the console shows them as unconfigured, which is a valid state, not an error.
   */
  @Transactional(readOnly = true)
  public List<SettlementSourceRef> listConfigured() {
    return jdbcTemplate.query(
        """
        SELECT source_kind, source_code
          FROM settlement_source_config
         ORDER BY source_kind
        """,
        (rs, rowNum) -> {
          // The REAL stable channel_code, not the kind's name: a ref built with a fabricated code
          // and later handed to PlatformReceivableWriter would open a second, stranded row.
          SettlementSourceKind kind = SettlementSourceKind.valueOf(rs.getString(1));
          return new SettlementSourceRef(kind, channelCodeFor(kind), rs.getString(2));
        });
  }

  /** The stable sub-ledger key for a tender family — the single source of these constants. */
  private static String channelCodeFor(SettlementSourceKind kind) {
    return switch (kind) {
      case QRIS -> QRIS_CHANNEL;
      case CARD -> CARD_CHANNEL;
      case MARKETPLACE ->
          throw new IllegalArgumentException("a marketplace row is keyed by its own channel code");
    };
  }

  /** The merchant's configured payer for a tender family, defaulting to the family's own name. */
  private String configuredSource(String companyId, SettlementSourceKind kind) {
    try {
      String configured =
          jdbcTemplate.queryForObject(SELECT_SOURCE_SQL, String.class, companyId, kind.name());
      return configured == null || configured.isBlank() ? kind.name() : configured;
    } catch (EmptyResultDataAccessException noRow) {
      return kind.name();
    }
  }

  /**
   * One sub-ledger row's identity: which GL account its balance lives in ({@code kind}), the stable
   * key it accumulates under ({@code channelCode}), and who pays it out ({@code sourceCode}).
   */
  public record SettlementSourceRef(
      SettlementSourceKind kind, String channelCode, String sourceCode) {}
}
