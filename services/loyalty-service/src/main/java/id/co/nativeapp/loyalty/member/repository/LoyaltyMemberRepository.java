package id.co.nativeapp.loyalty.member.repository;

import id.co.nativeapp.loyalty.member.domain.LoyaltyMember;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link LoyaltyMember}. The RLS policy on {@code loyalty_member} scopes
 * every query to the bound tenant automatically — no manual {@code WHERE company_id} is required.
 *
 * <p><strong>Deliberate read-path deviation from the projection convention (documented, per
 * CODE-STRUCTURE.md §3.3).</strong> Every method here returns the FULL {@link LoyaltyMember}
 * entity, including on the {@code findByPhoneHash} READ path — normally a read selects only the
 * columns it needs into a {@code projection} interface. That is not possible here: a lookup
 * response needs the DECRYPTED {@code displayName}/phone-tail (rule 6 PII), and decryption is Java
 * code ({@code PiiCipher}) that a native-query projection cannot invoke — a projection interface
 * can only alias raw SQL columns, never run application logic over them. So the encrypted-PII read
 * path legitimately loads the whole aggregate (exactly as the WRITE path always does) and decrypts
 * via the JPA {@code AttributeConverter} on access. This mirrors employee-service's identical
 * constraint on {@code Employee.nik}/{@code bankAccount}. Only the {@link #findByPhoneHash(String)}
 * derived-query method exists — no PII-bearing native {@code @Query} was written, so the {@code
 * repositoryQueriesAreNative} ArchUnit rule (which only inspects {@code @Query}-annotated methods)
 * is unaffected.
 */
public interface LoyaltyMemberRepository extends JpaRepository<LoyaltyMember, UUID> {

  /** Exact-match lookup by the deterministic HMAC-SHA256 phone hash (see {@code PhoneHasher}). */
  Optional<LoyaltyMember> findByPhoneHash(String phoneHash);

  /** Fast existence pre-check before an enroll write (the DB unique constraint is the backstop). */
  boolean existsByPhoneHash(String phoneHash);
}
