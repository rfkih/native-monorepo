package id.co.nativeapp.loyalty.member.service;

import id.co.nativeapp.loyalty.config.PhoneHasher;
import id.co.nativeapp.loyalty.ledger.service.LoyaltyEventEmitter;
import id.co.nativeapp.loyalty.member.domain.DuplicateMemberException;
import id.co.nativeapp.loyalty.member.domain.LoyaltyMember;
import id.co.nativeapp.loyalty.member.domain.PhoneNormalizer;
import id.co.nativeapp.loyalty.member.dto.EnrollMemberRequest;
import id.co.nativeapp.loyalty.member.dto.MemberResponse;
import id.co.nativeapp.loyalty.member.repository.LoyaltyMemberRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write unit of work for member enrollment — the barbershop {@code
 * TicketWriter}/{@code CatalogWriter} pattern applied to enrollment (a distinct bean so the
 * {@code @Transactional} advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets
 * the tenant GUC engage through the Spring proxy).
 *
 * <p><strong>Phone normalization + hashing happen here</strong> (see {@link PhoneNormalizer} /
 * {@link PhoneHasher}), immediately before the entity is built, so both the fast-path {@code
 * existsByPhoneHash} pre-check and the persisted row use the SAME normalized/hashed value.
 *
 * <p><strong>Duplicate detection is two-layered.</strong> A fast pre-check ({@code
 * existsByPhoneHash}) rejects the common case with a friendly {@link DuplicateMemberException}
 * before any write; the DB-level {@code UNIQUE(company_id, phone_hash)} constraint is the race-safe
 * backstop for two concurrent enrollments of the same phone — {@link
 * id.co.nativeapp.loyalty.member.service.MemberService#enroll} catches the resulting {@link
 * DataIntegrityViolationException} and translates it to the same {@link DuplicateMemberException}
 * (→ 409), since that catch happens OUTSIDE this method's now-aborted transaction.
 */
@Component
public class MemberEnrollWriter {

  private final LoyaltyMemberRepository repository;
  private final PhoneHasher phoneHasher;
  private final LoyaltyEventEmitter eventEmitter;

  public MemberEnrollWriter(
      LoyaltyMemberRepository repository,
      PhoneHasher phoneHasher,
      LoyaltyEventEmitter eventEmitter) {
    this.repository = repository;
    this.phoneHasher = phoneHasher;
    this.eventEmitter = eventEmitter;
  }

  /**
   * Enrolls a member, in its own transaction.
   *
   * @throws DuplicateMemberException if the fast pre-check finds an existing member for this phone
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer wins the
   *     {@code (company_id, phone_hash)} unique constraint (caught by the caller — see class
   *     javadoc)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public MemberResponse enroll(EnrollMemberRequest request) {
    String companyId = TenantContext.require().companyId();
    String normalizedPhone = PhoneNormalizer.normalize(request.phone());
    String phoneHash = phoneHasher.hash(companyId, normalizedPhone);

    if (repository.existsByPhoneHash(phoneHash)) {
      throw new DuplicateMemberException();
    }

    LoyaltyMember member = new LoyaltyMember(normalizedPhone, phoneHash, request.displayName());
    member.setCompanyId(companyId);
    LoyaltyMember saved = repository.saveAndFlush(member);

    Instant now = Instant.now();
    eventEmitter.emitBalanceChanged(
        saved.getId(), companyId, saved.getPointsBalance(), saved.getBalanceSeq(), "ENROLLED", now);

    return MemberResponse.from(saved);
  }
}
