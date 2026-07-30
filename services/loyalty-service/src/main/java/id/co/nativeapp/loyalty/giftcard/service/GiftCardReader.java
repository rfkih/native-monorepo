package id.co.nativeapp.loyalty.giftcard.service;

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
 */
@Service
public class GiftCardReader {

  private final GiftCardRepository repository;

  public GiftCardReader(GiftCardRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public GiftCardResponse lookupByCode(String code) {
    GiftCardView view =
        repository.findViewByCode(code).orElseThrow(() -> new GiftCardNotFoundException(code));
    return toResponse(view);
  }

  @Transactional(readOnly = true)
  public List<GiftCardResponse> list() {
    return repository.findAllViews().stream().map(GiftCardReader::toResponse).toList();
  }

  private static GiftCardResponse toResponse(GiftCardView v) {
    return new GiftCardResponse(
        v.getId(), v.getCode(), v.getState(), v.getBalanceMinor(), v.getCurrency().strip());
  }
}
