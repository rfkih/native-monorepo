package id.co.nativeapp.employee.expense.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ExpenseReceipt;
import id.co.nativeapp.employee.expense.domain.ReceiptNotFoundException;
import id.co.nativeapp.employee.expense.projection.ReceiptMetaView;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.expense.repository.ExpenseReceiptRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side for a claim's receipt: the caller's own claim on {@code /me} (rule 5, the {@code
 * /me} idiom) and the manager-facing any-tenant-claim read (ADR 0030 §8, Phase E3).
 *
 * <p><strong>The blob loads only on this, the serve path (CODE-STRUCTURE §3.3).</strong> Both
 * methods first resolve the current receipt's id via {@link
 * ExpenseReceiptRepository#findMetaByClaimId} — a projection that never selects {@code data} — then
 * load the FULL entity (with the blob) via the inherited {@code findById}, exactly the "full entity
 * loads only on `findById`/`save`" convention. There is no shortcut that fetches the blob via a
 * custom {@code SELECT *} query.
 */
@Service
public class ReceiptReader {

  private final ExpenseClaimRepository claimRepository;
  private final ExpenseReceiptRepository receiptRepository;
  private final EmployeeRepository employeeRepository;

  public ReceiptReader(
      ExpenseClaimRepository claimRepository,
      ExpenseReceiptRepository receiptRepository,
      EmployeeRepository employeeRepository) {
    this.claimRepository = claimRepository;
    this.receiptRepository = receiptRepository;
    this.employeeRepository = employeeRepository;
  }

  /**
   * The CALLER's own claim's current receipt.
   *
   * @throws ClaimNotFoundException if the claim is unknown, or not the caller's own (→ 404,
   *     anti-enumeration)
   * @throws ReceiptNotFoundException if the claim has no receipt on file (→ 404)
   */
  @Transactional(readOnly = true)
  public ExpenseReceipt myReceipt(UUID claimId) {
    Employee me = resolveMe();
    claimRepository
        .findById(claimId)
        .filter(c -> c.getEmployeeId().equals(me.getId()))
        .orElseThrow(() -> new ClaimNotFoundException(claimId));
    return loadCurrent(claimId);
  }

  /**
   * Any claim's current receipt visible in the bound tenant (the manager surface).
   *
   * @throws ClaimNotFoundException if the claim is unknown in this tenant (→ 404)
   * @throws ReceiptNotFoundException if the claim has no receipt on file (→ 404)
   */
  @Transactional(readOnly = true)
  public ExpenseReceipt receiptForManager(UUID claimId) {
    claimRepository.findById(claimId).orElseThrow(() -> new ClaimNotFoundException(claimId));
    return loadCurrent(claimId);
  }

  private ExpenseReceipt loadCurrent(UUID claimId) {
    ReceiptMetaView meta =
        receiptRepository
            .findMetaByClaimId(claimId)
            .orElseThrow(() -> new ReceiptNotFoundException(claimId));
    return receiptRepository
        .findById(meta.getId())
        .orElseThrow(() -> new ReceiptNotFoundException(claimId));
  }

  private Employee resolveMe() {
    String actor = TenantContext.require().actor();
    return employeeRepository.findByUserId(actor).orElseThrow(EmployeeNotLinkedException::new);
  }
}
