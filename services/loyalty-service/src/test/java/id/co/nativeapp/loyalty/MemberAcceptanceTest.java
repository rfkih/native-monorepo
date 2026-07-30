package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import id.co.nativeapp.loyalty.member.domain.DuplicateMemberException;
import id.co.nativeapp.loyalty.member.domain.MemberNotFoundException;
import id.co.nativeapp.loyalty.member.dto.EnrollMemberRequest;
import id.co.nativeapp.loyalty.member.dto.MemberResponse;
import id.co.nativeapp.loyalty.member.service.MemberService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Member enroll/lookup round trip, duplicate-enrollment 409, unknown-id/phone 404, and a
 * log-capture proof that the raw phone digits never reach a log line (rule 6).
 */
@SpringBootTest
class MemberAcceptanceTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "owner-a@example.co.id";
  private static final String RAW_PHONE = "0812-3456-7890";
  private static final String NORMALIZED_PHONE = "081234567890";

  @Autowired private MemberService memberService;

  @Test
  void enrollingThenLookingUpByPhoneReturnsTheSameMemberMaskedAndPlainNameForConfirmation()
      throws Exception {
    MemberResponse enrolled =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> memberService.enroll(new EnrollMemberRequest(RAW_PHONE, "Budi")));

    assertThat(enrolled.pointsBalance()).isZero();
    assertThat(enrolled.phoneTail()).isEqualTo("****7890");
    assertThat(enrolled.displayName()).isEqualTo("Budi");

    // A different spelling of the SAME phone (spaces instead of dashes) normalizes identically and
    // resolves the same member.
    MemberResponse looked =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> memberService.lookupByPhone("0812 3456 7890"));
    assertThat(looked.id()).isEqualTo(enrolled.id());
    assertThat(looked.displayName()).isEqualTo("Budi");
    assertThat(looked.phoneTail()).isEqualTo("****7890");
  }

  @Test
  void enrollingTheSamePhoneTwiceIsRejectedWithDuplicateMemberException() throws Exception {
    TenantContext.callAs(
        TENANT_A, ACTOR_A, () -> memberService.enroll(new EnrollMemberRequest(RAW_PHONE, "Budi")));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () -> memberService.enroll(new EnrollMemberRequest(RAW_PHONE, "Someone Else"))))
        .isInstanceOf(DuplicateMemberException.class);
  }

  @Test
  void lookingUpAnUnknownPhoneIsNotFound() throws Exception {
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A, ACTOR_A, () -> memberService.lookupByPhone("0899-0000-0000")))
        .isInstanceOf(MemberNotFoundException.class);
  }

  @Test
  void gettingAnUnknownMemberIdIsNotFound() throws Exception {
    UUID unknown = UUID.randomUUID();
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> memberService.getById(unknown)))
        .isInstanceOf(MemberNotFoundException.class);
  }

  @Test
  void theRawPhoneNeverReachesAnyLogLineDuringEnrollOrLookup() throws Exception {
    Logger rootLoyaltyLogger = (Logger) LoggerFactory.getLogger("id.co.nativeapp.loyalty");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    rootLoyaltyLogger.addAppender(appender);
    rootLoyaltyLogger.setLevel(Level.TRACE);

    try {
      TenantContext.callAs(
          TENANT_A, ACTOR_A, () -> memberService.enroll(new EnrollMemberRequest(RAW_PHONE, "Budi")));
      TenantContext.callAs(TENANT_A, ACTOR_A, () -> memberService.lookupByPhone(RAW_PHONE));
    } finally {
      rootLoyaltyLogger.detachAppender(appender);
    }

    for (ILoggingEvent event : appender.list) {
      assertThat(event.getFormattedMessage())
          .as("no log line may contain the raw or normalized phone (rule 6)")
          .doesNotContain(RAW_PHONE)
          .doesNotContain(NORMALIZED_PHONE);
    }
  }
}
