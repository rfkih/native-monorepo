package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ExpenseReceipt;
import id.co.nativeapp.employee.expense.domain.ReceiptNotFoundException;
import id.co.nativeapp.employee.expense.dto.ReceiptContentResponse;
import id.co.nativeapp.employee.expense.projection.ReceiptMetaView;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.expense.repository.ExpenseReceiptRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side for a claim's receipt: the caller's own claim on {@code /me} (rule 5, the {@code
 * /me} idiom) and the manager-facing any-tenant-claim read (ADR 0030 §8, Phase E3).
 *
 * <p><strong>Payload homes (ADR 0048).</strong> Both methods first resolve the current receipt's id
 * via {@link ExpenseReceiptRepository#findMetaByClaimId} — a projection that never selects {@code
 * data} — then load the FULL entity via the inherited {@code findById}. An object-backed row's
 * bytes come from the object store; a legacy row serves its inline bytea AND opportunistically
 * flips to object-backed via {@link ReceiptObjectMigrationWriter} (read-through migration — never
 * on the critical path, failures are swallowed there). The controller receives the finished {@link
 * ReceiptContentResponse}, never the entity.
 */
@Service
public class ReceiptReader {

  private final ExpenseClaimRepository claimRepository;
  private final ExpenseReceiptRepository receiptRepository;
  private final EmployeeRepository employeeRepository;
  private final MediaStorage mediaStorage;
  private final ReceiptObjectMigrationWriter migrator;

  public ReceiptReader(
      ExpenseClaimRepository claimRepository,
      ExpenseReceiptRepository receiptRepository,
      EmployeeRepository employeeRepository,
      MediaStorage mediaStorage,
      ReceiptObjectMigrationWriter migrator) {
    this.claimRepository = claimRepository;
    this.receiptRepository = receiptRepository;
    this.employeeRepository = employeeRepository;
    this.mediaStorage = mediaStorage;
    this.migrator = migrator;
  }

  /**
   * The CALLER's own claim's current receipt content.
   *
   * @throws ClaimNotFoundException if the claim is unknown, or not the caller's own (→ 404,
   *     anti-enumeration)
   * @throws ReceiptNotFoundException if the claim has no receipt on file (→ 404)
   */
  @Transactional(readOnly = true)
  public ReceiptContentResponse myReceipt(UUID claimId) {
    Employee me = resolveMe();
    claimRepository
        .findById(claimId)
        .filter(c -> c.getEmployeeId().equals(me.getId()))
        .orElseThrow(() -> new ClaimNotFoundException(claimId));
    return serveCurrent(claimId);
  }

  /**
   * Any claim's current receipt content visible in the bound tenant (the manager surface).
   *
   * @throws ClaimNotFoundException if the claim is unknown in this tenant (→ 404)
   * @throws ReceiptNotFoundException if the claim has no receipt on file (→ 404)
   */
  @Transactional(readOnly = true)
  public ReceiptContentResponse receiptForManager(UUID claimId) {
    claimRepository.findById(claimId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    return serveCurrent(claimId);
  }

  private ReceiptContentResponse serveCurrent(UUID claimId) {
    ReceiptMetaView meta =
        receiptRepository
            .findMetaByClaimId(claimId)
            .orElseThrow(() -> new ReceiptNotFoundException(claimId));
    ExpenseReceipt receipt =
        receiptRepository
            .findById(meta.getId())
            .orElseThrow(() -> new ReceiptNotFoundException(claimId));

    if (receipt.getObjectKey() != null) {
      // Object-backed (ADR 0048): bytes from the store; content type stays the row's canonical
      // value (the DB is the metadata authority). A store outage fails the serve loudly (500 with
      // an error reference) — there is no silent fallback to invent bytes from.
      MediaStorage.StoredObject stored = mediaStorage.get(receipt.getObjectKey());
      return new ReceiptContentResponse(
          receipt.getContentType(), stored.data(), receipt.getSha256());
    }

    // Legacy inline row: serve the bytea in hand, then opportunistically flip the row to
    // object-backed (read-through migration — REQUIRES_NEW, failures swallowed inside).
    byte[] data = receipt.getData();
    migrator.migrateOpportunistically(receipt.getId());
    return new ReceiptContentResponse(receipt.getContentType(), data, receipt.getSha256());
  }

  private Employee resolveMe() {
    String actor = TenantContext.require().actor();
    return employeeRepository.findByUserId(actor).orElseThrow(EmployeeNotLinkedException::new);
  }
}
