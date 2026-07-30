package id.co.nativeapp.restaurant.giftcard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.giftcard.dto.GiftCardSaleResponse;
import id.co.nativeapp.restaurant.giftcard.dto.SellGiftCardRequest;
import id.co.nativeapp.restaurant.giftcard.messaging.GiftCardSoldSchema;
import id.co.nativeapp.restaurant.giftcard.service.GiftCardSaleResult;
import id.co.nativeapp.restaurant.giftcard.service.GiftCardSaleService;
import id.co.nativeapp.restaurant.outletref.domain.OutletNotAssignedException;
import id.co.nativeapp.restaurant.outletref.messaging.UserOutletAssignmentEvent;
import id.co.nativeapp.restaurant.outletref.service.UserOutletAssignmentRefService;
import id.co.nativeapp.tenant.TenantContext;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 4 (ADR 0027) — the gift-card SALE (mint) write path: {@code POST /api/v1/gift-card-sales}
 * (exercised here at the {@link GiftCardSaleService} layer, mirroring every other feature's {@code
 * *IntegrationTest} idiom).
 *
 * <p>Covers: the sold row + the decoded {@code GiftCardSold} outbox event (card id, amount, tender,
 * company); the derived display code matching an INDEPENDENT recomputation of loyalty-service's
 * derivation scheme (Base32 of the first 10 UUID bytes, no padding — ADR 0027 decision 5); an
 * idempotent replay (one row, one event); and {@link
 * id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard} enforcement — a cashier with no
 * outlet assignment at a company that HAS adopted scoping is rejected 403.
 */
@SpringBootTest
class GiftCardSaleIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "44444444-4444-4444-4444-444444444401";
  private static final String OWNER_ACTOR = "owner-gc@example.co.id";
  private static final String CASHIER_ACTOR = "cashier-gc@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("44444444-4444-4444-4444-444444444402");

  @Autowired private GiftCardSaleService giftCardSaleService;
  @Autowired private UserOutletAssignmentRefService assignmentService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void sellingAGiftCardPersistsTheSaleAndEmitsGiftCardSoldWithTheDerivedCode() throws Exception {
    String idemKey = "sell-" + UUID.randomUUID();
    SellGiftCardRequest request =
        new SellGiftCardRequest(BUSINESS_ID, idemKey, 100_000L, "IDR", "CASH");

    GiftCardSaleResult result =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> giftCardSaleService.sell(request));

    assertThat(result.created()).isTrue();
    GiftCardSaleResponse sale = result.sale();
    assertThat(sale.amountMinor()).isEqualTo(100_000L);
    assertThat(sale.currency()).isEqualTo("IDR");
    assertThat(sale.tenderType()).isEqualTo("CASH");
    assertThat(sale.businessId()).isEqualTo(BUSINESS_ID);

    // The derived code matches an INDEPENDENT recomputation of loyalty-service's scheme — proving
    // the two services agree on the code WITHOUT a cross-service call (ADR 0027 decision 5).
    assertThat(sale.code()).isEqualTo(recomputeDerivedCode(sale.giftCardId()));

    // GiftCardSold outbox decode.
    GenericRecord decoded = readGiftCardSoldAdmin(sale.giftCardId());
    assertThat(decoded.get("gift_card_sale_id").toString())
        .isEqualTo(sale.giftCardSaleId().toString());
    assertThat(decoded.get("gift_card_id").toString()).isEqualTo(sale.giftCardId().toString());
    assertThat(decoded.get("company_id").toString()).isEqualTo(TENANT);
    assertThat(decoded.get("business_id").toString()).isEqualTo(BUSINESS_ID.toString());
    assertThat(decoded.get("amount_minor")).isEqualTo(100_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("tender_type").toString()).isEqualTo("CASH");
  }

  @Test
  void idempotentReplayOfTheSameKeyProducesOneRowAndOneEvent() throws Exception {
    String idemKey = "sell-replay-" + UUID.randomUUID();
    SellGiftCardRequest request =
        new SellGiftCardRequest(BUSINESS_ID, idemKey, 50_000L, "IDR", "CASH");

    GiftCardSaleResult first =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> giftCardSaleService.sell(request));
    assertThat(first.created()).isTrue();
    UUID cardId = first.sale().giftCardId();

    GiftCardSaleResult replay =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> giftCardSaleService.sell(request));

    assertThat(replay.created()).isFalse();
    assertThat(replay.sale().giftCardId()).isEqualTo(cardId);
    assertThat(countGiftCardSaleRowsAdmin(cardId)).isEqualTo(1);
    assertThat(countGiftCardSoldEventsAdmin(cardId)).isEqualTo(1);
  }

  @Test
  void cashierWithoutOutletAssignmentIsRejectedWith403WhenScopingIsAdopted() throws Exception {
    // Seed a row for a DIFFERENT user so the company has adopted scoping (no grandfather).
    UserOutletAssignmentEvent seed =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "other-cashier",
            TENANT,
            BUSINESS_ID,
            "ASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(9999, 12, 31).toEpochDay());
    assignmentService.apply(seed);

    SellGiftCardRequest request =
        new SellGiftCardRequest(
            BUSINESS_ID, "sell-403-" + UUID.randomUUID(), 25_000L, "IDR", "CASH");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, CASHIER_ACTOR, () -> giftCardSaleService.sell(request)))
        .isInstanceOf(OutletNotAssignedException.class);
  }

  // -----------------------------------------------------------------------
  // Independent code-derivation recomputation (does NOT call production
  // GiftCardCodeGenerator — a true cross-check against a copy-paste bug).
  // -----------------------------------------------------------------------

  private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  private static String recomputeDerivedCode(UUID giftCardId) {
    ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * 2);
    buffer.putLong(giftCardId.getMostSignificantBits());
    buffer.putLong(giftCardId.getLeastSignificantBits());
    byte[] truncated = new byte[10];
    System.arraycopy(buffer.array(), 0, truncated, 0, 10);
    StringBuilder out = new StringBuilder();
    int bitBuffer = 0;
    int bitsLeft = 0;
    for (byte b : truncated) {
      bitBuffer = (bitBuffer << 8) | (b & 0xFF);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        int index = (bitBuffer >> (bitsLeft - 5)) & 0x1F;
        out.append(BASE32_ALPHABET[index]);
        bitsLeft -= 5;
      }
    }
    if (bitsLeft > 0) {
      int index = (bitBuffer << (5 - bitsLeft)) & 0x1F;
      out.append(BASE32_ALPHABET[index]);
    }
    return out.toString();
  }

  // -----------------------------------------------------------------------
  // Fixtures / helpers
  // -----------------------------------------------------------------------

  private GenericRecord readGiftCardSoldAdmin(UUID cardId) {
    Map<String, Object> outboxRow =
        jdbcTemplate.queryForMap(
            "SELECT payload FROM outbox WHERE event_type = 'GiftCardSold' AND aggregate_id = ?"
                + " ORDER BY occurred_at DESC LIMIT 1",
            cardId.toString());
    return AvroSerde.deserialize((byte[]) outboxRow.get("payload"), GiftCardSoldSchema.schema());
  }

  /**
   * {@code gift_card_sale} carries FORCE ROW LEVEL SECURITY (V17) — the plain RLS-scoped {@code
   * jdbcTemplate} bean (no tenant GUC bound outside an active {@code @Transactional} call) would
   * see zero rows here, so this reads over the admin/BYPASSRLS connection, mirroring every other
   * feature's {@code *RowsAdmin} helper.
   */
  private int countGiftCardSaleRowsAdmin(UUID cardId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT COUNT(*) FROM gift_card_sale WHERE gift_card_id = ?")) {
      ps.setObject(1, cardId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private int countGiftCardSoldEventsAdmin(UUID cardId) {
    // aggregate_id keys the CARD (not the sale row) — every event for one card orders together.
    // outbox carries NO row-level security (Debezium needs unrestricted read access), so the plain
    // jdbcTemplate bean is fine here (see PromotionCheckoutIntegrationTest precedent).
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM outbox WHERE event_type = 'GiftCardSold' AND aggregate_id = ?",
        Integer.class,
        cardId.toString());
  }

  private Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
