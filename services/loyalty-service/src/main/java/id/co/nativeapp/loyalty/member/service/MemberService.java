package id.co.nativeapp.loyalty.member.service;

import id.co.nativeapp.loyalty.member.domain.DuplicateMemberException;
import id.co.nativeapp.loyalty.member.dto.EnrollMemberRequest;
import id.co.nativeapp.loyalty.member.dto.MemberResponse;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates member enroll/lookup/get. Not itself {@code @Transactional} — writes delegate to
 * {@link MemberEnrollWriter}, reads delegate to {@link MemberReader}, each invoked through the
 * Spring proxy so the tx advice and the RLS auto-apply aspect engage (the {@code TicketService}
 * pattern).
 */
@Service
public class MemberService {

  private final MemberEnrollWriter writer;
  private final MemberReader reader;

  public MemberService(MemberEnrollWriter writer, MemberReader reader) {
    this.writer = writer;
    this.reader = reader;
  }

  /**
   * Enrolls a new member.
   *
   * @throws DuplicateMemberException if the phone is already enrolled for this tenant (→ 409),
   *     whether caught by the writer's fast pre-check or (on a concurrent race) translated here
   *     from the writer's now-aborted-transaction {@link DataIntegrityViolationException}
   */
  public MemberResponse enroll(EnrollMemberRequest request) {
    try {
      return writer.enroll(request);
    } catch (DataIntegrityViolationException race) {
      // A concurrent racer won the (company_id, phone_hash) unique constraint after the writer's
      // own fast pre-check passed. The writer's transaction is now aborted; translate to the same
      // friendly conflict the pre-check would have raised (no re-read needed — enrollment, unlike
      // an idempotent checkout retry, is not "the same request twice", it is a genuine conflict).
      throw new DuplicateMemberException();
    }
  }

  public MemberResponse lookupByPhone(String phone) {
    return reader.lookupByPhone(phone);
  }

  public MemberResponse getById(UUID memberId) {
    return reader.getById(memberId);
  }
}
