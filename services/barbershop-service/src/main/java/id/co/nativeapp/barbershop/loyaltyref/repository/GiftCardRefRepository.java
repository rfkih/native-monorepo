package id.co.nativeapp.barbershop.loyaltyref.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for the {@code gift_card_ref} read model — the LOCAL cache of loyalty-service's
 * gift-card state (ADR 0027, Phase 4), fed by {@code GiftCardStateChanged} and read at checkout to
 * clamp + atomically decrement a gift-card redemption WITHOUT a synchronous call to loyalty-service
 * (rule 2). Mirrors {@link MemberBalanceRefRepository}.
 */
@Repository
public class GiftCardRefRepository {

  private final JdbcTemplate jdbcTemplate;

  public GiftCardRefRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Reads the cached balance + currency for {@code giftCardId} IF it is currently cached as {@code
   * ACTIVE}; empty otherwise (unknown card, not-yet-replicated, or a non-ACTIVE state — all treated
   * identically as "not currently redeemable" by the caller, which maps to {@code 409
   * gift-card-unusable}).
   *
   * <p>Upper-bound read for the checkout-time clamp only; the actual double-spend guard is {@link
   * #decrementIfSufficient}'s atomic {@code WHERE} clause.
   */
  public Optional<GiftCardBalance> findActiveBalance(UUID giftCardId) {
    return jdbcTemplate
        .query(
            "SELECT balance_minor, currency FROM gift_card_ref"
                + " WHERE gift_card_id = ? AND state = 'ACTIVE'",
            (rs, rowNum) ->
                new GiftCardBalance(rs.getLong("balance_minor"), rs.getString("currency").strip()),
            giftCardId)
        .stream()
        .findFirst();
  }

  /**
   * Atomically decrements {@code minorAmount} from the cached balance IF the card is still cached
   * {@code ACTIVE} and its balance still covers it (ADR 0027 decision 3): {@code UPDATE
   * gift_card_ref SET balance_minor = balance_minor - :x WHERE gift_card_id = :id AND state =
   * 'ACTIVE' AND balance_minor >= :x}.
   *
   * @param actor stamped into {@code updated_by} — the checkout caller's bound actor (the cashier)
   * @return the number of rows updated: {@code 1} on success, {@code 0} if the card is unknown, not
   *     ACTIVE, or a concurrent redemption already consumed the balance (the caller maps zero rows
   *     to {@code 409 gift-card-unusable})
   */
  public int decrementIfSufficient(UUID giftCardId, long minorAmount, String actor) {
    Timestamp now = Timestamp.from(Instant.now());
    return jdbcTemplate.update(
        """
        UPDATE gift_card_ref
           SET balance_minor = balance_minor - ?,
               updated_at    = ?,
               updated_by    = ?,
               version       = version + 1
         WHERE gift_card_id  = ?
           AND state         = 'ACTIVE'
           AND balance_minor >= ?
        """,
        minorAmount,
        now,
        actor,
        giftCardId,
        minorAmount);
  }

  /**
   * Set-if-newer upsert of the {@code GiftCardStateChanged} snapshot (ADR 0027 decision 2) —
   * identical discipline to {@link MemberBalanceRefRepository#upsertSetIfNewer}.
   */
  public void upsertSetIfNewer(
      UUID giftCardId,
      String companyId,
      String state,
      long balanceMinor,
      String currency,
      long balanceSeq,
      String actor) {
    Timestamp now = Timestamp.from(Instant.now());
    jdbcTemplate.update(
        """
        INSERT INTO gift_card_ref
          (gift_card_id, state, balance_minor, currency, balance_seq,
           created_at, created_by, updated_at, updated_by, version, company_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
        ON CONFLICT ON CONSTRAINT uq_gift_card_ref_company_card DO UPDATE SET
          state          = EXCLUDED.state,
          balance_minor  = EXCLUDED.balance_minor,
          currency       = EXCLUDED.currency,
          balance_seq    = EXCLUDED.balance_seq,
          updated_at     = EXCLUDED.updated_at,
          updated_by     = EXCLUDED.updated_by,
          version        = gift_card_ref.version + 1
        WHERE gift_card_ref.balance_seq < EXCLUDED.balance_seq
        """,
        giftCardId,
        state,
        balanceMinor,
        currency,
        balanceSeq,
        now,
        actor,
        now,
        actor,
        companyId);
  }
}
