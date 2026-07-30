package id.co.nativeapp.loyalty.giftcard.repository;

import id.co.nativeapp.loyalty.giftcard.domain.GiftCard;
import id.co.nativeapp.loyalty.giftcard.projection.GiftCardView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link GiftCard}. All read queries are native (rule 10 /
 * CODE-STRUCTURE.md §3.3). The RLS policy on {@code gift_card} scopes every query to the bound
 * tenant automatically — no manual {@code WHERE company_id} is required.
 */
public interface GiftCardRepository extends JpaRepository<GiftCard, UUID> {

  /** POS lookup by code (case-sensitive — the derived code is already uppercase). */
  @Query(
      value =
          """
          SELECT id            AS id,
                 code          AS code,
                 state         AS state,
                 balance_minor AS balanceMinor,
                 currency      AS currency
            FROM gift_card
           WHERE code = :code
          """,
      nativeQuery = true)
  Optional<GiftCardView> findViewByCode(@Param("code") String code);

  /** The admin listing endpoint. */
  @Query(
      value =
          """
          SELECT id            AS id,
                 code          AS code,
                 state         AS state,
                 balance_minor AS balanceMinor,
                 currency      AS currency
            FROM gift_card
           ORDER BY sold_at DESC
          """,
      nativeQuery = true)
  List<GiftCardView> findAllViews();
}
