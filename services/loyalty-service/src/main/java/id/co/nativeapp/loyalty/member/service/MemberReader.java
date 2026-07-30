package id.co.nativeapp.loyalty.member.service;

import id.co.nativeapp.loyalty.config.PhoneHasher;
import id.co.nativeapp.loyalty.member.domain.LoyaltyMember;
import id.co.nativeapp.loyalty.member.domain.MemberNotFoundException;
import id.co.nativeapp.loyalty.member.domain.PhoneNormalizer;
import id.co.nativeapp.loyalty.member.dto.MemberResponse;
import id.co.nativeapp.loyalty.member.repository.LoyaltyMemberRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional(readOnly = true)} member reads. A distinct bean from {@link
 * MemberEnrollWriter} and {@link MemberService} so it is invoked through the Spring proxy (the RLS
 * auto-apply aspect only engages through the proxy).
 *
 * <p>See {@link id.co.nativeapp.loyalty.member.repository.LoyaltyMemberRepository} class javadoc
 * for why this loads the full entity rather than a native-query projection (PII decryption is Java
 * code a projection cannot invoke).
 */
@Component
public class MemberReader {

  private final LoyaltyMemberRepository repository;
  private final PhoneHasher phoneHasher;

  public MemberReader(LoyaltyMemberRepository repository, PhoneHasher phoneHasher) {
    this.repository = repository;
    this.phoneHasher = phoneHasher;
  }

  /**
   * Exact-match lookup by phone (the POS "attach an existing member" flow).
   *
   * @throws MemberNotFoundException if no member matches (→ 404) — deliberately the SAME not-found
   *     signal whether the phone was never enrolled or belongs to another tenant (RLS makes the two
   *     indistinguishable; no existence disclosure)
   */
  @Transactional(readOnly = true)
  public MemberResponse lookupByPhone(String rawPhone) {
    String normalized = PhoneNormalizer.normalize(rawPhone);
    String hash = phoneHasher.hash(normalized);
    LoyaltyMember member =
        repository.findByPhoneHash(hash).orElseThrow(MemberNotFoundException::new);
    return MemberResponse.from(member);
  }

  @Transactional(readOnly = true)
  public MemberResponse getById(UUID memberId) {
    LoyaltyMember member =
        repository.findById(memberId).orElseThrow(() -> new MemberNotFoundException(memberId));
    return MemberResponse.from(member);
  }
}
