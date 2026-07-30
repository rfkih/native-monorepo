package id.co.nativeapp.loyalty.giftcard.service;

import id.co.nativeapp.loyalty.config.ActorRolesProvider;
import id.co.nativeapp.loyalty.giftcard.domain.GiftCardListForbiddenException;
import id.co.nativeapp.loyalty.giftcard.domain.GiftCardNotFoundException;
import id.co.nativeapp.loyalty.giftcard.dto.GiftCardResponse;
import id.co.nativeapp.loyalty.giftcard.projection.GiftCardView;
import id.co.nativeapp.loyalty.giftcard.repository.GiftCardRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates gift-card reads for {@code GiftCardController} — the POS lookup-by-code (state +
 * balance + currency for redemption validation) and the admin listing. {@code @Transactional
 * (readOnly = true)} directly (no separate reader/writer split needed — this feature has no write
 * path of its own; gift cards are created only by the ingest {@code GiftCardSoldWriter}).
 *
 * <p><strong>{@link #list()} requires {@code owner}/{@code manager} (security review W-4); {@link
 * #lookupByCode} stays ungated for POS.</strong> The admin listing returns EVERY card for the
 * tenant, including its bearer-credential {@code code} — unrestricted access would let any POS
 * token enumerate every valid redemption code in the company, not just the one on the card in hand.
 * A per-code lookup carries no such risk (the caller already has to know/scan the code).
 * Empty-roles-pass semantics mirror {@code EarnRuleService.requireWriteRole} — the same guard
 * pattern.
 */
@Service
public class GiftCardReader {

  private final GiftCardRepository repository;
  private final ActorRolesProvider actorRoles;

  public GiftCardReader(GiftCardRepository repository, ActorRolesProvider actorRoles) {
    this.repository = repository;
    this.actorRoles = actorRoles;
  }

  @Transactional(readOnly = true)
  public GiftCardResponse lookupByCode(String code) {
    GiftCardView view =
        repository.findViewByCode(code).orElseThrow(() -> new GiftCardNotFoundException(code));
    return toResponse(view);
  }

  /**
   * @throws GiftCardListForbiddenException if the caller has a non-empty role set that is neither
   *     {@code owner} nor {@code manager} (→ 403)
   */
  @Transactional(readOnly = true)
  public List<GiftCardResponse> list() {
    requireWriteRole();
    return repository.findAllViews().stream().map(GiftCardReader::toResponse).toList();
  }

  /**
   * Empty-roles-pass semantics (see class javadoc): an EMPTY role set is let through (the
   * gateway-less dev recipe / a direct service-layer test, not a real cashier token); a non-empty
   * set that is neither {@code owner} nor {@code manager} is denied.
   */
  private void requireWriteRole() {
    if (!actorRoles.currentRoles().isEmpty() && !actorRoles.isOwnerOrManager()) {
      throw new GiftCardListForbiddenException();
    }
  }

  private static GiftCardResponse toResponse(GiftCardView v) {
    return new GiftCardResponse(
        v.getId(), v.getCode(), v.getState(), v.getBalanceMinor(), v.getCurrency().strip());
  }
}
