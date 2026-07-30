package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.carwash.giftcard.domain.GiftCardMintLimitExceededException;
import id.co.nativeapp.carwash.giftcard.dto.GiftCardSaleResponse;
import id.co.nativeapp.carwash.giftcard.dto.SellGiftCardRequest;
import id.co.nativeapp.carwash.giftcard.messaging.GiftCardSoldSchema;
import id.co.nativeapp.carwash.giftcard.service.GiftCardSaleResult;
import id.co.nativeapp.carwash.giftcard.service.GiftCardSaleService;
import id.co.nativeapp.carwash.outletref.domain.OutletNotAssignedException;
import id.co.nativeapp.carwash.outletref.messaging.UserOutletAssignmentEvent;
import id.co.nativeapp.carwash.outletref.service.UserOutletAssignmentRefService;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.tenant.TenantContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 4 (ADR 0027) — the carwash gift-card SALE (mint) write path: {@code POST
 * /api/v1/carwash/gift-cards/sell}, exercised here at the {@link GiftCardSaleService} layer
 * (mirrors {@code OutletAccessGuardTest}'s minimal base — no entitlement gate on this endpoint, so
 * no Kafka/Redis is needed).
 *
 * <p>Covers: the sold row + the decoded {@code GiftCardSold} outbox event; the derived display code
 * matching an INDEPENDENT recomputation of loyalty-service's KEYED derivation scheme (security
 * review W-4); an idempotent replay (one row, one event); {@code OutletAccessGuard} enforcement
 * (cashier w/o assignment at an adopted company → 403); the security review W-3 mint controls (a
 * {@code @NotNull} tenderType, and a request above {@code native.giftcard.max-mint-minor} rejected
 * 422).
 */
@SpringBootTest
class GiftCardSaleIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "44444444-4444-4444-4444-444444444401";
  private static final String OWNER_ACTOR = "owner-gc@example.co.id";
  private static final String CASHIER_ACTOR = "cashier-gc@example.co.id";
  private static final UUID OUTLET = UUID.fromString("44444444-4444-4444-4444-444444444402");

  @Autowired private GiftCardSaleService giftCardSaleService;
  @Autowired private UserOutletAssignmentRefService assignmentService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void sellingAGiftCardPersistsTheSaleAndEmitsGiftCardSoldWithTheDerivedCode() throws Exception {
    String idemKey = "sell-" + UUID.randomUUID();
    SellGiftCardRequest request = new SellGiftCardRequest(OUTLET, idemKey, 100_000L, "IDR", "CASH");

    GiftCardSaleResult result =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> giftCardSaleService.sell(request));

    assertThat(result.created()).isTrue();
    GiftCardSaleResponse sale = result.sale();
    assertThat(sale.amountMinor()).isEqualTo(100_000L);
    assertThat(sale.currency()).isEqualTo("IDR");
    assertThat(sale.tenderType()).isEqualTo("CASH");
    assertThat(sale.businessId()).isEqualTo(OUTLET);

    assertThat(sale.code()).isEqualTo(recomputeDerivedCode(sale.giftCardId()));

    GenericRecord decoded = readGiftCardSoldAdmin(sale.giftCardId());
    assertThat(decoded.get("gift_card_sale_id").toString())
        .isEqualTo(sale.giftCardSaleId().toString());
    assertThat(decoded.get("gift_card_id").toString()).isEqualTo(sale.giftCardId().toString());
    assertThat(decoded.get("company_id").toString()).isEqualTo(TENANT);
    assertThat(decoded.get("business_id").toString()).isEqualTo(OUTLET.toString());
    assertThat(decoded.get("amount_minor")).isEqualTo(100_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("tender_type").toString()).isEqualTo("CASH");
  }

  @Test
  void idempotentReplayOfTheSameKeyProducesOneRowAndOneEvent() throws Exception {
    String idemKey = "sell-replay-" + UUID.randomUUID();
    SellGiftCardRequest request = new SellGiftCardRequest(OUTLET, idemKey, 50_000L, "IDR", "CASH");

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
    UserOutletAssignmentEvent seed =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "other-cashier",
            TENANT,
            OUTLET,
            "ASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(9999, 12, 31).toEpochDay());
    assignmentService.apply(seed);

    SellGiftCardRequest request =
        new SellGiftCardRequest(OUTLET, "sell-403-" + UUID.randomUUID(), 25_000L, "IDR", "CASH");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, CASHIER_ACTOR, () -> giftCardSaleService.sell(request)))
        .isInstanceOf(OutletNotAssignedException.class);
  }

  // -----------------------------------------------------------------------
  // Security review W-3: mint controls — @NotNull tenderType + a max-mint-minor ceiling.
  // -----------------------------------------------------------------------

  @Test
  void nullTenderTypeFailsBeanValidation() {
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    Validator validator = factory.getValidator();

    SellGiftCardRequest request =
        new SellGiftCardRequest(OUTLET, "sell-" + UUID.randomUUID(), 10_000L, "IDR", null);
    Set<ConstraintViolation<SellGiftCardRequest>> violations = validator.validate(request);

    assertThat(violations)
        .isNotEmpty()
        .anyMatch(v -> v.getPropertyPath().toString().equals("tenderType"));
  }

  @Test
  void aMintAboveTheConfiguredCeilingIsRejectedWith422() throws Exception {
    SellGiftCardRequest request =
        new SellGiftCardRequest(
            OUTLET, "sell-over-limit-" + UUID.randomUUID(), 5_000_001L, "IDR", "CASH");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(TENANT, OWNER_ACTOR, () -> giftCardSaleService.sell(request)))
        .isInstanceOf(GiftCardMintLimitExceededException.class);
  }

  @Test
  void aMintExactlyAtTheConfiguredCeilingIsAccepted() throws Exception {
    SellGiftCardRequest request =
        new SellGiftCardRequest(
            OUTLET, "sell-at-limit-" + UUID.randomUUID(), 5_000_000L, "IDR", "CASH");

    GiftCardSaleResult result =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> giftCardSaleService.sell(request));

    assertThat(result.created()).isTrue();
    assertThat(result.sale().amountMinor()).isEqualTo(5_000_000L);
  }

  // -----------------------------------------------------------------------
  // Independent code-derivation recomputation. Security review W-4: the KEYED HMAC-SHA256 scheme,
  // using the SAME dev/test key every service's application.yml / build.gradle.kts commits
  // (NATIVE_GIFTCARD_CODE_KEY) — see GiftCardCodeGenerator's class javadoc for the exact message
  // format.
  // -----------------------------------------------------------------------

  private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  private static final String TEST_CODE_KEY_BASE64 = "Z2lmdC1jYXJkLWNvZGUtaG1hYy1rZXktMDEyMzQ1Njc=";

  private static String recomputeDerivedCode(UUID giftCardId) throws Exception {
    byte[] keyBytes = Base64.getDecoder().decode(TEST_CODE_KEY_BASE64);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
    byte[] digest = mac.doFinal(giftCardId.toString().getBytes(StandardCharsets.UTF_8));
    byte[] truncated = new byte[10];
    System.arraycopy(digest, 0, truncated, 0, 10);
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
   * {@code gift_card_sale} carries FORCE ROW LEVEL SECURITY — reads over the admin/BYPASSRLS
   * connection (the plain RLS-scoped {@code jdbcTemplate} bean has no tenant GUC bound outside an
   * active {@code @Transactional} call).
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
    // outbox carries NO row-level security (Debezium needs unrestricted read access).
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
