package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.loyalty.giftcard.domain.GiftCardCodeGenerator;
import id.co.nativeapp.loyalty.giftcard.domain.GiftCardNotFoundException;
import id.co.nativeapp.loyalty.giftcard.service.GiftCardReader;
import id.co.nativeapp.loyalty.member.domain.MemberNotFoundException;
import id.co.nativeapp.loyalty.member.dto.EnrollMemberRequest;
import id.co.nativeapp.loyalty.member.dto.MemberResponse;
import id.co.nativeapp.loyalty.member.service.MemberService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Two-tenant RLS isolation (rule 5): a member enrolled under tenant A's phone hash is invisible to
 * tenant B's lookup by the SAME phone (a different member, or none at all, per tenant), and a gift
 * card sold under tenant A's code is invisible to tenant B — proving the {@code UNIQUE(company_id,
 * phone_hash)} / {@code UNIQUE(company_id, code)} constraints are genuinely tenant-scoped, not
 * global.
 */
@SpringBootTest
class TenancyIsolationTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "55555555-1111-1111-1111-111111111111";
  private static final String TENANT_B = "55555555-2222-2222-2222-222222222222";
  private static final String ACTOR = "actor@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("55555555-3333-3333-3333-333333333333");

  @Autowired private MemberService memberService;
  @Autowired private GiftCardReader giftCardReader;
  @Autowired private GiftCardCodeGenerator giftCardCodeGenerator;

  @Test
  void aMemberEnrolledUnderOneTenantIsInvisibleToAnotherTenantsLookupByTheSamePhone()
      throws Exception {
    String phone = "0833-9999-0001";
    MemberResponse enrolledInA =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> memberService.enroll(new EnrollMemberRequest(phone, "Tenant A")));

    // The SAME phone under tenant B: not found (tenant B has never enrolled it) — proving the
    // UNIQUE(company_id, phone_hash) constraint scopes uniqueness PER TENANT, and the lookup query
    // itself is RLS-scoped so tenant A's row never leaks into tenant B's result set.
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_B, ACTOR, () -> memberService.lookupByPhone(phone)))
        .isInstanceOf(MemberNotFoundException.class);

    // And tenant B can freely enroll the SAME phone as its own, distinct member — proving the
    // uniqueness constraint is per-tenant, not global.
    MemberResponse enrolledInB =
        TenantContext.callAs(
            TENANT_B,
            ACTOR,
            () -> memberService.enroll(new EnrollMemberRequest(phone, "Tenant B")));
    assertThat(enrolledInB.id()).isNotEqualTo(enrolledInA.id());

    // Tenant A cannot read tenant B's member by id either (RLS on findById).
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A, ACTOR, () -> memberService.getById(enrolledInB.id())))
        .isInstanceOf(MemberNotFoundException.class);
  }

  @Test
  void aGiftCardSoldUnderOneTenantIsInvisibleToAnotherTenantsLookupByCode() throws Exception {
    UUID giftCardId = UUID.randomUUID();
    GenericRecord event =
        EventFixtures.giftCardSold(giftCardId, TENANT_A, BUSINESS, 20_000L, "IDR");
    EventFixtures.publishGiftCardSold(KAFKA.getBootstrapServers(), UUID.randomUUID(), event);

    String code = giftCardCodeGenerator.deriveCode(giftCardId);

    // ignoreException: Awaitility's untilAsserted only auto-retries on AssertionError; the card
    // does not exist yet on the first few polls (async Kafka consumption).
    await()
        .atMost(Duration.ofSeconds(30))
        .ignoreException(GiftCardNotFoundException.class)
        .untilAsserted(
            () ->
                assertThat(
                        TenantContext.callAs(
                                TENANT_A, ACTOR, () -> giftCardReader.lookupByCode(code))
                            .balanceMinor())
                    .isEqualTo(20_000L));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_B, ACTOR, () -> giftCardReader.lookupByCode(code)))
        .isInstanceOf(GiftCardNotFoundException.class);
  }
}
