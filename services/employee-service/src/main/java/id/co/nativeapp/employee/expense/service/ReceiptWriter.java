package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ClaimStateException;
import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.domain.ExpenseReceipt;
import id.co.nativeapp.employee.expense.domain.InvalidReceiptContentTypeException;
import id.co.nativeapp.employee.expense.projection.ReceiptMetaView;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.expense.repository.ExpenseReceiptRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.mediastorage.ImageContentTypeValidator;
import id.co.nativeapp.mediastorage.MediaKeys;
import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.mediastorage.MediaStorageProperties;
import id.co.nativeapp.mediastorage.Sha256;
import id.co.nativeapp.mediastorage.UnsupportedImageTypeException;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Owns the {@code @Transactional} unit of work for uploading (and replacing) a claim's receipt
 * photo — the first multipart/binary-storage writer in the repo (ADR 0030 §8, Phase E3). A distinct
 * bean (the {@code *Writer} pattern) so the method is invoked through the Spring proxy and the
 * {@link RlsAutoApplyAspect} sets the tenant GUC (rule 5).
 *
 * <p><strong>Own-DRAFT-claim only.</strong> The caller is resolved EXCLUSIVELY from {@link
 * TenantContext} (the {@code /me} idiom, mirroring {@code ExpenseClaimWriter#resolveMe}) — there is
 * no employee-id parameter, so an upload can only ever land on the caller's own claim. Upload (and
 * re-upload) is allowed only while the claim is {@code DRAFT}: once submitted the amount/ category
 * are frozen for the approval workflow, and a receipt swapped in after submission could
 * misrepresent what a manager is approving.
 *
 * <p><strong>Size cap — defense in depth.</strong> {@code spring.servlet.multipart.max-file-size}
 * (5 MB) already bounds the multipart PART Spring hands the controller, but this writer re-checks
 * {@link ExpenseReceipt#MAX_BYTES} against the actual byte count it received — a config drift or a
 * caller that bypasses the declared {@code Content-Length} must not slip a larger blob past the DB
 * {@code CHECK} constraint with a confusing 500; it fails the SAME way (a {@link
 * MaxUploadSizeExceededException}, mapped to 413 by {@code EmployeeApiAdvice}) either path takes.
 *
 * <p><strong>Content-type — untrusted declared header.</strong> The shared magic-byte validator
 * (libs/media-storage {@link ImageContentTypeValidator} — the fleet's one copy since ADR 0048)
 * verifies the ACTUAL bytes against the DECLARED multipart {@code Content-Type} before anything is
 * persisted; a mismatch is translated to this feature's {@link InvalidReceiptContentTypeException}
 * ({@code 422}), never silently accepted.
 *
 * <p><strong>Payload home (ADR 0048).</strong> The validated bytes go to the OBJECT STORE
 * (content-addressed under {@code employee/{tenant}/receipt/{sha256}.{ext}}, put inside the write
 * transaction — a rollback orphans one harmless content-addressed object); the row keeps metadata
 * only. A replaced receipt's old object is deleted best-effort AFTER COMMIT (never before — a
 * rollback must not lose the object the surviving row still points to).
 *
 * <p><strong>Replace on re-upload.</strong> A claim carries at most one live receipt in v1: this
 * method locks the claim row ({@code SELECT … FOR UPDATE} — E3 review W1), deletes any existing
 * receipt row(s) for the claim, then inserts the freshly-validated one, in the SAME transaction.
 * The row lock serializes concurrent uploads to the same claim (a double-tap / PWA retry), so the
 * delete always sees the winner's committed row and the post-state is exactly one receipt; each
 * swap is atomic to concurrent readers either way.
 *
 * <p><strong>Retention (v1, documented — not enforced here).</strong> Cancelling or refusing a
 * claim does NOT delete its receipt row; only a re-upload (replace) or (a later phase) an explicit
 * purge would. This keeps the receipt available for audit/dispute even after the claim itself is no
 * longer active.
 *
 * <p><strong>Idempotency-Key — deliberately NOT required (ADR 0030 §8 / this phase).</strong>
 * Upload is replace-idempotent by nature: re-POSTing the identical file re-derives the identical
 * sha256 and simply re-replaces the one row with an equivalent one — there is no double-apply risk
 * the way there is for a guarded state transition (submit/approve/refuse), so no {@code
 * Idempotency-Key} header or {@code expense_claim_event} audit row is involved here.
 */
@Component
public class ReceiptWriter {

  private static final Logger log = LoggerFactory.getLogger(ReceiptWriter.class);

  /** The media family segment in this service's object keys. */
  static final String MEDIA_DOMAIN = "receipt";

  private final ExpenseClaimRepository claimRepository;
  private final ExpenseReceiptRepository receiptRepository;
  private final EmployeeRepository employeeRepository;
  private final MediaStorage mediaStorage;
  private final MediaStorageProperties mediaProperties;

  public ReceiptWriter(
      ExpenseClaimRepository claimRepository,
      ExpenseReceiptRepository receiptRepository,
      EmployeeRepository employeeRepository,
      MediaStorage mediaStorage,
      MediaStorageProperties mediaProperties) {
    this.claimRepository = claimRepository;
    this.receiptRepository = receiptRepository;
    this.employeeRepository = employeeRepository;
    this.mediaStorage = mediaStorage;
    this.mediaProperties = mediaProperties;
  }

  /**
   * Uploads (or replaces) the receipt for the CALLER's own DRAFT claim.
   *
   * @param claimId the target claim
   * @param declaredContentType the multipart part's declared {@code Content-Type} (untrusted —
   *     verified against the actual bytes)
   * @param data the file bytes
   * @throws ClaimNotFoundException if the claim is unknown, or not the caller's own (→ 404)
   * @throws ClaimStateException if the claim is not DRAFT (→ 409)
   * @throws MaxUploadSizeExceededException if {@code data} exceeds {@link ExpenseReceipt#MAX_BYTES}
   *     (→ 413)
   * @throws id.co.nativeapp.employee.expense.domain.InvalidReceiptContentTypeException if the
   *     actual bytes disagree with {@code declaredContentType}, or match no whitelisted format (→
   *     422)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ExpenseReceipt upload(UUID claimId, String declaredContentType, byte[] data) {
    String tenant = TenantContext.require().companyId();
    Employee me = resolveMe();

    // Fail fast on the bytes BEFORE touching any row lock — no DB work for garbage input.
    Objects.requireNonNull(data, "data");
    if (data.length > ExpenseReceipt.MAX_BYTES) {
      throw new MaxUploadSizeExceededException(ExpenseReceipt.MAX_BYTES);
    }
    // The CANONICAL detected type is what gets stored (and later served) — never the raw declared
    // header, which may be a case/whitespace variant or absent entirely (E3 review S1/S2). The
    // check itself is the fleet-shared magic-byte validator (ADR 0048); its exception is
    // translated to this feature's own so the API contract (422 shape) is unchanged.
    String canonicalContentType;
    try {
      canonicalContentType = ImageContentTypeValidator.validate(declaredContentType, data);
    } catch (UnsupportedImageTypeException e) {
      throw new InvalidReceiptContentTypeException(
          e.getDeclaredContentType(), e.getDetectedContentType());
    }
    String sha256 = Sha256.hex(data);

    // Serialize concurrent uploads to the same claim on the claim ROW (E3 review W1): without
    // this, two READ COMMITTED transactions could each delete-then-insert and commit TWO rows.
    // Lock FIRST, then check ownership/DRAFT — the check must see post-lock committed state, or a
    // concurrent submit between check and lock could sneak a receipt onto a SUBMITTED claim.
    claimRepository.lockForReceiptSwap(claimId);
    requireOwnDraftClaim(claimId, me.getId());

    // The replaced receipt's object (if any) — deletable only AFTER this transaction commits.
    String previousObjectKey =
        receiptRepository
            .findMetaByClaimId(claimId)
            .map(ReceiptMetaView::getObjectKey)
            .orElse(null);

    // Payload to the object store (ADR 0048), metadata row to Postgres — put BEFORE the row so a
    // committed row can never dangle; the reverse failure (stored object, rolled-back row) is a
    // harmless content-addressed orphan.
    String objectKey =
        MediaKeys.imageKey(
            mediaProperties.servicePrefix(), tenant, MEDIA_DOMAIN, sha256, canonicalContentType);
    mediaStorage.put(objectKey, data, canonicalContentType);

    // Replace-on-reupload, atomically: delete the claim's existing receipt(s), then insert the
    // freshly-validated one in the same transaction (class Javadoc).
    receiptRepository.deleteByClaimId(claimId);
    ExpenseReceipt receipt =
        new ExpenseReceipt(claimId, canonicalContentType, data.length, sha256, objectKey);
    receipt.setCompanyId(tenant);
    ExpenseReceipt saved = receiptRepository.saveAndFlush(receipt);
    scheduleReplacedObjectCleanup(previousObjectKey, objectKey);
    return saved;
  }

  /**
   * Best-effort delete of a REPLACED receipt's object, registered to run only {@code afterCommit}:
   * deleting before commit could lose the object a rolled-back transaction's surviving row still
   * references. A failed delete is an accepted orphan (ADR 0048), never an error. No-ops when the
   * old and new keys are equal (re-upload of the identical file — content addressing) or outside a
   * transaction (plain unit tests).
   */
  private void scheduleReplacedObjectCleanup(String previousObjectKey, String newObjectKey) {
    if (previousObjectKey == null
        || previousObjectKey.equals(newObjectKey)
        || !TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              mediaStorage.delete(previousObjectKey);
            } catch (RuntimeException e) {
              log.warn("replaced receipt object not deleted (accepted orphan): {}", e.getMessage());
            }
          }
        });
  }

  private ExpenseClaim requireOwnDraftClaim(UUID claimId, UUID employeeId) {
    ExpenseClaim claim =
        claimRepository
            .findById(claimId)
            .filter(c -> c.getEmployeeId().equals(employeeId))
            .orElseThrow(() -> new ClaimNotFoundException(claimId));
    if (claim.getStatus() != ClaimStatus.DRAFT) {
      throw new ClaimStateException(claim.getStatus(), "attach a receipt to");
    }
    return claim;
  }

  private Employee resolveMe() {
    String actor = TenantContext.require().actor();
    return employeeRepository.findByUserId(actor).orElseThrow(EmployeeNotLinkedException::new);
  }
}
