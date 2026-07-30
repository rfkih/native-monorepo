package id.co.nativeapp.carwash.loyaltyref.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for the {@code member_balance_ref} read model — the LOCAL cache of loyalty-service's
 * member points balance (ADR 0027, Phase 4), fed by {@code LoyaltyBalanceChanged} and read at
 * checkout to clamp + atomically decrement a points redemption WITHOUT a synchronous call to
 * loyalty-service (rule 2).
 *
 * <p>Implemented as a plain {@link JdbcTemplate} wrapper (no Spring Data JPA entity), mirroring
 * {@code outletref.repository.UserOutletAssignmentRefRepository}: this is a read model, not an
 * aggregate. Every method runs under an active {@link id.co.nativeapp.tenant.TenantContext} so the
 * RLS GUC is set by the auto-RLS aspect on the caller's enclosing {@code @Transactional} (rule 5).
 *
 * <p>Queries are native SQL (house convention — no JPQL); no {@code SELECT *}.
 */
@Repository
public class MemberBalanceRefRepository {

  private final JdbcTemplate jdbcTemplate;

  public MemberBalanceRefRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Reads the cached points balance for {@code memberId}, or empty if this member has never been
   * seen by this outlet's cache (not-yet-replicated or invalid — the caller treats this as 409).
   *
   * <p>This is an UPPER-BOUND read for the checkout-time clamp only; the actual double-spend guard
   * is {@link #decrementIfSufficient}'s atomic {@code WHERE} clause, not this read.
   */
  public Optional<Long> findBalance(UUID memberId) {
    return jdbcTemplate
        .query(
            "SELECT points_balance FROM member_balance_ref WHERE member_id = ?",
            (rs, rowNum) -> rs.getLong("points_balance"),
            memberId)
        .stream()
        .findFirst();
  }

  /**
   * Atomically decrements {@code points} from the cached balance IF the cached balance still covers
   * it (ADR 0027 decision 3 — the within-service double-spend guard): {@code UPDATE
   * member_balance_ref SET points_balance = points_balance - :points WHERE member_id = :memberId
   * AND points_balance >= :points}.
   *
   * @param actor stamped into {@code updated_by} — the checkout caller's bound actor (the cashier),
   *     since this decrement happens synchronously inside an authenticated checkout request
   * @return the number of rows updated: {@code 1} on success, {@code 0} if the member is unknown or
   *     a concurrent redemption already consumed the balance this decrement needed (the caller maps
   *     zero rows to {@code 409 loyalty-balance-insufficient})
   */
  public int decrementIfSufficient(UUID memberId, long points, String actor) {
    Timestamp now = Timestamp.from(Instant.now());
    return jdbcTemplate.update(
        """
        UPDATE member_balance_ref
           SET points_balance = points_balance - ?,
               updated_at     = ?,
               updated_by     = ?,
               version        = version + 1
         WHERE member_id      = ?
           AND points_balance >= ?
        """,
        points,
        now,
        actor,
        memberId,
        points);
  }

  /**
   * Set-if-newer upsert of the {@code LoyaltyBalanceChanged} snapshot (ADR 0027 decision 2): a
   * fresh row is inserted for a never-before-seen member; an existing row is updated ONLY when the
   * incoming {@code balanceSeq} is strictly newer than the cached one — an atomic {@code INSERT ...
   * ON CONFLICT ... DO UPDATE ... WHERE} so a stale/out-of-order redelivery is a no-op, never a
   * regression. Conflict target is the tenant-composite unique constraint (belt-and-suspenders — a
   * cross-tenant {@code member_id} collision fails closed on the bare PK instead), mirroring {@code
   * UserOutletAssignmentRefWriter}.
   */
  public void upsertSetIfNewer(
      UUID memberId, String companyId, long pointsBalance, long balanceSeq, String actor) {
    Timestamp now = Timestamp.from(Instant.now());
    jdbcTemplate.update(
        """
        INSERT INTO member_balance_ref
          (member_id, points_balance, balance_seq,
           created_at, created_by, updated_at, updated_by, version, company_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?)
        ON CONFLICT ON CONSTRAINT uq_member_balance_ref_company_member DO UPDATE SET
          points_balance = EXCLUDED.points_balance,
          balance_seq    = EXCLUDED.balance_seq,
          updated_at     = EXCLUDED.updated_at,
          updated_by     = EXCLUDED.updated_by,
          version        = member_balance_ref.version + 1
        WHERE member_balance_ref.balance_seq < EXCLUDED.balance_seq
        """,
        memberId,
        pointsBalance,
        balanceSeq,
        now,
        actor,
        now,
        actor,
        companyId);
  }
}
