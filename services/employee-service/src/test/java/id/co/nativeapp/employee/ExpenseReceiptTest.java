package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ExpenseCategory;
import id.co.nativeapp.employee.expense.domain.ExpenseReceipt;
import id.co.nativeapp.employee.expense.domain.ReceiptNotFoundException;
import id.co.nativeapp.employee.expense.dto.CreateClaimCommand;
import id.co.nativeapp.employee.expense.service.ExpenseCategoryWriter;
import id.co.nativeapp.employee.expense.service.ExpenseClaimService;
import id.co.nativeapp.employee.expense.service.ReceiptReader;
import id.co.nativeapp.employee.expense.service.ReceiptWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers (real, non-superuser Postgres) coverage for receipts (ADR 0030 §8, Phase E3): RLS
 * isolation, an exact upload→serve round trip, and that replace-on-reupload leaves exactly one row.
 * Uses {@link PostgresRlsTestBase#countAsTenant} for the row-level asserts (Spring Data repository
 * proxies do not get the RLS GUC as the FIRST transactional hop from a bare test call — see the
 * {@code rls-guc-transactional-only} project note).
 */
@SpringBootTest
class ExpenseReceiptTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR_A = "aaaaaaaa-1111-1111-1111-111111111111";
  private static final String ACTOR_B = "bbbbbbbb-2222-2222-2222-222222222222";

  private static final byte[] JPEG_BYTES = {
    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02, 0x03, 0x04
  };
  private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

  @Autowired private EmployeeService employeeService;
  @Autowired private ExpenseCategoryWriter categoryWriter;
  @Autowired private ExpenseClaimService claimService;
  @Autowired private ReceiptWriter receiptWriter;
  @Autowired private ReceiptReader receiptReader;

  private UUID createOwnDraftClaim(String tenant, String actor) throws Exception {
    return TenantContext.callAs(
        tenant,
        actor,
        () -> {
          UUID employeeId =
              employeeService
                  .create(
                      new CreateEmployeeCommand(
                          "Budi", "TK0", "3201234567890123", "1234567890123456"))
                  .getId();
          employeeService.linkUser(employeeId, actor, null);
          ExpenseCategory category = categoryWriter.create("Supplies", "supplies", false);
          return claimService
              .create(
                  new CreateClaimCommand(
                      category.getId(),
                      250_000L,
                      "IDR",
                      LocalDate.of(2026, 7, 15),
                      "Warung Makan",
                      "lunch",
                      null))
              .getId();
        });
  }

  @Test
  void uploadAndServeRoundTripIsByteIdentical() throws Exception {
    UUID claimId = createOwnDraftClaim(TENANT_A, ACTOR_A);

    ExpenseReceipt uploaded =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> receiptWriter.upload(claimId, "image/jpeg", JPEG_BYTES.clone()));

    ExpenseReceipt served =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> receiptReader.myReceipt(claimId));

    assertThat(served.getData()).isEqualTo(JPEG_BYTES);
    assertThat(served.getContentType()).isEqualTo("image/jpeg");
    assertThat(served.getByteSize()).isEqualTo(JPEG_BYTES.length);
    assertThat(served.getSha256()).isEqualTo(uploaded.getSha256());
    assertThat(served.getId()).isEqualTo(uploaded.getId());
  }

  @Test
  void replaceOnReuploadLeavesExactlyOneRow() throws Exception {
    UUID claimId = createOwnDraftClaim(TENANT_A, ACTOR_A);

    TenantContext.callAs(
        TENANT_A, ACTOR_A, () -> receiptWriter.upload(claimId, "image/jpeg", JPEG_BYTES.clone()));
    TenantContext.callAs(
        TENANT_A, ACTOR_A, () -> receiptWriter.upload(claimId, "image/png", PNG_BYTES.clone()));

    assertThat(
            countAsTenant(
                TENANT_A, "SELECT count(*) FROM expense_receipt WHERE claim_id = ?", claimId))
        .isEqualTo(1L);

    ExpenseReceipt current =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> receiptReader.myReceipt(claimId));
    assertThat(current.getContentType()).isEqualTo("image/png");
    assertThat(current.getData()).isEqualTo(PNG_BYTES);
  }

  @Test
  void tenantBCannotSeeTenantAsReceiptRlsFailClosed() throws Exception {
    UUID claimId = createOwnDraftClaim(TENANT_A, ACTOR_A);
    TenantContext.callAs(
        TENANT_A, ACTOR_A, () -> receiptWriter.upload(claimId, "image/jpeg", JPEG_BYTES.clone()));

    assertThat(
            countAsTenant(
                TENANT_A, "SELECT count(*) FROM expense_receipt WHERE claim_id = ?", claimId))
        .isEqualTo(1L);
    assertThat(
            countAsTenant(
                TENANT_B, "SELECT count(*) FROM expense_receipt WHERE claim_id = ?", claimId))
        .isZero();

    // Tenant B cannot even see the CLAIM (RLS on expense_claim itself) — the receipt read fails
    // closed one layer earlier, exactly like every other cross-tenant expense-claims read.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B, ACTOR_B, () -> receiptReader.receiptForManager(claimId)))
        .isInstanceOf(ClaimNotFoundException.class);
  }

  @Test
  void aClaimWithNoUploadHasNoReceiptOnServe() throws Exception {
    UUID claimId = createOwnDraftClaim(TENANT_A, ACTOR_A);

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> receiptReader.myReceipt(claimId)))
        .isInstanceOf(ReceiptNotFoundException.class);
  }
}
