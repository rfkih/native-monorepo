package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.loyaltyref.messaging.GiftCardStateChangedEvent;
import id.co.nativeapp.restaurant.loyaltyref.messaging.LoyaltyBalanceChangedEvent;
import id.co.nativeapp.restaurant.loyaltyref.service.GiftCardStateChangedService;
import id.co.nativeapp.restaurant.loyaltyref.service.LoyaltyBalanceChangedService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Consumer-side acceptance tests for the {@code LoyaltyBalanceChanged} / {@code
 * GiftCardStateChanged} listeners (ADR 0027, Phase 4) — mirrors {@code
 * UserOutletAssignmentConsumerAcceptanceTest} verbatim, applied to the {@code member_balance_ref}
 * and {@code gift_card_ref} read models.
 *
 * <p>Tests the service/writer layer directly (no Kafka broker needed — the {@code @KafkaListener}
 * is a thin adapter). Verifies, for BOTH events:
 *
 * <ol>
 *   <li>The first delivery upserts the ABSOLUTE snapshot (set-if-newer).
 *   <li>A STALE {@code balance_seq} (older, delivered after a newer one already applied) is
 *       DROPPED — the cached value is unchanged.
 *   <li>Re-delivering the SAME event id is a no-op (idempotency via {@code processed_event}).
 *   <li>Two-tenant isolation: an event for company A never becomes visible under company B (RLS
 *       proof via the admin/BYPASSRLS connection).
 * </ol>
 */
@SpringBootTest
class LoyaltyRefConsumerAcceptanceTest extends PostgresRlsTestBase {

  private static final String COMPANY_A = "11111111-1111-1111-1111-111111111111";
  private static final String COMPANY_B = "22222222-2222-2222-2222-222222222222";

  @Autowired private LoyaltyBalanceChangedService loyaltyBalanceChangedService;
  @Autowired private GiftCardStateChangedService giftCardStateChangedService;

  // -----------------------------------------------------------------------
  // LoyaltyBalanceChanged / member_balance_ref
  // -----------------------------------------------------------------------

  @Test
  void firstDeliveryUpsertsTheAbsoluteBalance() throws Exception {
    UUID memberId = UUID.randomUUID();
    boolean applied =
        loyaltyBalanceChangedService.apply(
            new LoyaltyBalanceChangedEvent(
                UUID.randomUUID(), memberId, COMPANY_A, 1_200L, 1L, Instant.now()));

    assertThat(applied).isTrue();
    assertThat(memberBalanceAdmin(memberId)).isEqualTo(1_200L);
  }

  @Test
  void staleBalanceSeqIsDroppedLeavingTheCachedValueUnchanged() throws Exception {
    UUID memberId = UUID.randomUUID();
    loyaltyBalanceChangedService.apply(
        new LoyaltyBalanceChangedEvent(
            UUID.randomUUID(), memberId, COMPANY_A, 1_200L, 5L, Instant.now()));

    // A STALE event (lower balanceSeq, e.g. an out-of-order redelivery from a future
    // multi-partition topic) — a NEW event id, so processed_event does not short-circuit it; the
    // set-if-newer SQL guard is what must reject it.
    boolean applied =
        loyaltyBalanceChangedService.apply(
            new LoyaltyBalanceChangedEvent(
                UUID.randomUUID(), memberId, COMPANY_A, 999L, 3L, Instant.now()));

    // The writer's processedEvents.processOnce claims a NEW event id and runs the upsert — "applied
    // = true" reflects "this delivery was processed", but the set-if-newer WHERE clause inside the
    // upsert is what actually protects the value.
    assertThat(applied).isTrue();
    assertThat(memberBalanceAdmin(memberId)).as("stale balanceSeq must not regress the cache").isEqualTo(1_200L);
  }

  @Test
  void redeliveryOfTheSameEventIdIsANoop() throws Exception {
    UUID memberId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    LoyaltyBalanceChangedEvent event =
        new LoyaltyBalanceChangedEvent(eventId, memberId, COMPANY_A, 800L, 1L, Instant.now());

    boolean first = loyaltyBalanceChangedService.apply(event);
    assertThat(first).isTrue();

    // Redelivered with the SAME event id but a (hypothetically) different balance — must be a
    // no-op even though its balanceSeq is "newer", because processed_event dedupes on event id
    // FIRST.
    LoyaltyBalanceChangedEvent duplicate =
        new LoyaltyBalanceChangedEvent(eventId, memberId, COMPANY_A, 50_000L, 99L, Instant.now());
    boolean second = loyaltyBalanceChangedService.apply(duplicate);

    assertThat(second).as("idempotency: skipped").isFalse();
    assertThat(memberBalanceAdmin(memberId)).isEqualTo(800L);
  }

  @Test
  void eventForCompanyADoesNotBecomeVisibleUnderCompanyB() throws Exception {
    UUID memberId = UUID.randomUUID();
    loyaltyBalanceChangedService.apply(
        new LoyaltyBalanceChangedEvent(
            UUID.randomUUID(), memberId, COMPANY_A, 300L, 1L, Instant.now()));

    assertThat(countMemberBalanceRowsAdmin(COMPANY_A)).isOne();
    assertThat(countMemberBalanceRowsAdmin(COMPANY_B)).isZero();
  }

  // -----------------------------------------------------------------------
  // GiftCardStateChanged / gift_card_ref
  // -----------------------------------------------------------------------

  @Test
  void firstDeliveryUpsertsTheAbsoluteGiftCardState() throws Exception {
    UUID cardId = UUID.randomUUID();
    boolean applied =
        giftCardStateChangedService.apply(
            new GiftCardStateChangedEvent(
                UUID.randomUUID(), cardId, COMPANY_A, "ACTIVE", 40_000L, "IDR", 1L, Instant.now()));

    assertThat(applied).isTrue();
    assertThat(giftCardBalanceAdmin(cardId)).isEqualTo(40_000L);
    assertThat(giftCardStateAdmin(cardId)).isEqualTo("ACTIVE");
  }

  @Test
  void staleGiftCardBalanceSeqIsDroppedLeavingTheCachedValueUnchanged() throws Exception {
    UUID cardId = UUID.randomUUID();
    giftCardStateChangedService.apply(
        new GiftCardStateChangedEvent(
            UUID.randomUUID(), cardId, COMPANY_A, "ACTIVE", 40_000L, "IDR", 5L, Instant.now()));

    boolean applied =
        giftCardStateChangedService.apply(
            new GiftCardStateChangedEvent(
                UUID.randomUUID(), cardId, COMPANY_A, "DEPLETED", 0L, "IDR", 2L, Instant.now()));

    assertThat(applied).isTrue(); // this delivery was processed...
    // ...but the set-if-newer guard rejected the stale content: state/balance unchanged.
    assertThat(giftCardBalanceAdmin(cardId)).as("stale balanceSeq must not regress the cache").isEqualTo(40_000L);
    assertThat(giftCardStateAdmin(cardId)).isEqualTo("ACTIVE");
  }

  @Test
  void redeliveryOfTheSameGiftCardEventIdIsANoop() throws Exception {
    UUID cardId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    GiftCardStateChangedEvent event =
        new GiftCardStateChangedEvent(
            eventId, cardId, COMPANY_A, "ACTIVE", 10_000L, "IDR", 1L, Instant.now());

    boolean first = giftCardStateChangedService.apply(event);
    assertThat(first).isTrue();

    GiftCardStateChangedEvent duplicate =
        new GiftCardStateChangedEvent(
            eventId, cardId, COMPANY_A, "VOID", 0L, "IDR", 99L, Instant.now());
    boolean second = giftCardStateChangedService.apply(duplicate);

    assertThat(second).as("idempotency: skipped").isFalse();
    assertThat(giftCardBalanceAdmin(cardId)).isEqualTo(10_000L);
    assertThat(giftCardStateAdmin(cardId)).isEqualTo("ACTIVE");
  }

  @Test
  void giftCardEventForCompanyADoesNotBecomeVisibleUnderCompanyB() throws Exception {
    UUID cardId = UUID.randomUUID();
    giftCardStateChangedService.apply(
        new GiftCardStateChangedEvent(
            UUID.randomUUID(), cardId, COMPANY_A, "ACTIVE", 5_000L, "IDR", 1L, Instant.now()));

    assertThat(countGiftCardRefRowsAdmin(COMPANY_A)).isOne();
    assertThat(countGiftCardRefRowsAdmin(COMPANY_B)).isZero();
  }

  // -----------------------------------------------------------------------
  // Admin helpers: direct DB queries bypassing RLS (BYPASSRLS superuser conn)
  // -----------------------------------------------------------------------

  private long memberBalanceAdmin(UUID memberId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT points_balance FROM member_balance_ref WHERE member_id = ?")) {
      ps.setObject(1, memberId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long countMemberBalanceRowsAdmin(String companyId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT COUNT(*) FROM member_balance_ref WHERE company_id = ?")) {
      ps.setString(1, companyId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long giftCardBalanceAdmin(UUID cardId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT balance_minor FROM gift_card_ref WHERE gift_card_id = ?")) {
      ps.setObject(1, cardId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String giftCardStateAdmin(UUID cardId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT state FROM gift_card_ref WHERE gift_card_id = ?")) {
      ps.setObject(1, cardId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private long countGiftCardRefRowsAdmin(String companyId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT COUNT(*) FROM gift_card_ref WHERE company_id = ?")) {
      ps.setString(1, companyId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
