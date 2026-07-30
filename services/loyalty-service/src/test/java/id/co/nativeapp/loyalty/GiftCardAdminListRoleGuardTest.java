package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.loyalty.giftcard.domain.GiftCardListForbiddenException;
import id.co.nativeapp.loyalty.giftcard.dto.GiftCardResponse;
import id.co.nativeapp.loyalty.giftcard.service.GiftCardReader;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@code GiftCardReader#list()}'s owner/manager write-role guard (security review W-4) — the admin
 * listing enumerates every card's bearer-credential {@code code} for the tenant, so it is held to
 * the same bar as {@code EarnRuleService#requireWriteRole}. {@code lookupByCode} stays ungated
 * (proven elsewhere, e.g. {@code TenancyIsolationTest}). Mirrors {@code EarnRuleAcceptanceTest}'s
 * {@code setRoles} idiom.
 */
@SpringBootTest
class GiftCardAdminListRoleGuardTest extends KafkaPostgresTestBase {

  private static final String TENANT = "66666666-6666-6666-6666-666666666666";
  private static final String ACTOR = "actor@example.co.id";

  @Autowired private GiftCardReader giftCardReader;

  @BeforeEach
  void bindMockRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  private void setRoles(String roles) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private void clearRoles() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void ownerCanListGiftCards() throws Exception {
    setRoles("owner");
    List<GiftCardResponse> listed =
        TenantContext.callAs(TENANT, ACTOR, () -> giftCardReader.list());
    assertThat(listed).isNotNull();
  }

  @Test
  void managerCanListGiftCards() throws Exception {
    setRoles("manager");
    List<GiftCardResponse> listed =
        TenantContext.callAs(TENANT, ACTOR, () -> giftCardReader.list());
    assertThat(listed).isNotNull();
  }

  @Test
  void headerlessCallerCanListGiftCardsDevRecipeTrust() throws Exception {
    clearRoles();
    List<GiftCardResponse> listed =
        TenantContext.callAs(TENANT, ACTOR, () -> giftCardReader.list());
    assertThat(listed).isNotNull();
  }

  @Test
  void cashierIsRejectedFromListingGiftCards() throws Exception {
    setRoles("cashier");
    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> giftCardReader.list()))
        .isInstanceOf(GiftCardListForbiddenException.class);
  }
}
