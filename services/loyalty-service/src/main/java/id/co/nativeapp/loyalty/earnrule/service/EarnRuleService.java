package id.co.nativeapp.loyalty.earnrule.service;

import id.co.nativeapp.loyalty.config.ActorRolesProvider;
import id.co.nativeapp.loyalty.earnrule.domain.EarnRuleWriteForbiddenException;
import id.co.nativeapp.loyalty.earnrule.dto.EarnRuleCreateRequest;
import id.co.nativeapp.loyalty.earnrule.dto.EarnRuleResponse;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Orchestrates earn-rule admin CRUD for {@code EarnRuleController}. Ported from
 * barbershop-service's {@code PromotionAdminService} write-role-guard pattern.
 *
 * <p><strong>Writes require {@code owner}/{@code manager}; reads are ungated.</strong> An earn rule
 * is company-wide, money-routing configuration (it decides how many points every future sale
 * earns), so every create is held to the same owner/manager bar as barbershop's {@code
 * ManualDiscountGuard} / {@code requireWriteRole} — empty-roles-pass semantics: a headerless call
 * is the gateway-less dev recipe / a direct service-layer test, not a real cashier token.
 *
 * <p>Not itself {@code @Transactional} — transactional units live in {@link EarnRuleWriter}
 * (writes) and {@link EarnRuleReader} (reads) so the proxy and the RLS aspect engage.
 */
@Service
public class EarnRuleService {

  private final EarnRuleWriter writer;
  private final EarnRuleReader reader;
  private final ActorRolesProvider actorRoles;

  public EarnRuleService(
      EarnRuleWriter writer, EarnRuleReader reader, ActorRolesProvider actorRoles) {
    this.writer = writer;
    this.reader = reader;
    this.actorRoles = actorRoles;
  }

  public EarnRuleResponse create(EarnRuleCreateRequest request) {
    requireWriteRole();
    return writer.create(request);
  }

  public List<EarnRuleResponse> list(boolean activeOnly) {
    return reader.list(activeOnly);
  }

  /**
   * Empty-roles-pass semantics (see class javadoc): an EMPTY role set is let through; a non-empty
   * set that is neither {@code owner} nor {@code manager} is denied.
   */
  private void requireWriteRole() {
    if (!actorRoles.currentRoles().isEmpty() && !actorRoles.isOwnerOrManager()) {
      throw new EarnRuleWriteForbiddenException();
    }
  }
}
