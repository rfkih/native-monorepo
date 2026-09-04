package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.expense.domain.ClaimNotFoundException;
import id.co.nativeapp.employee.expense.domain.ClaimStateException;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.domain.ExpenseReceipt;
import id.co.nativeapp.employee.expense.domain.InvalidReceiptContentTypeException;
import id.co.nativeapp.employee.expense.repository.ExpenseClaimRepository;
import id.co.nativeapp.employee.expense.repository.ExpenseReceiptRepository;
import id.co.nativeapp.employee.expense.service.ReceiptWriter;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.mediastorage.MediaStorageProperties;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Writer unit tests with mocked repositories — no Spring context, no database. Proves the
 * own-DRAFT-claim guard, the oversize/spoofed-content-type guards, and that a valid upload replaces
 * (delete-then-insert) atomically.
 */
class ReceiptWriterTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "employee-sub";
  private static final UUID CATEGORY = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
  private static final UUID ORG_UNIT = UUID.fromString("dddddddd-0000-0000-0000-00000000000d");
  private static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02};

  private final ExpenseClaimRepository claimRepository = mock(ExpenseClaimRepository.class);
  private final ExpenseReceiptRepository receiptRepository = mock(ExpenseReceiptRepository.class);
  private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
  private final MediaStorage mediaStorage = mock(MediaStorage.class);

  private final ReceiptWriter writer =
      new ReceiptWriter(
          claimRepository,
          receiptRepository,
          employeeRepository,
          mediaStorage,
          new MediaStorageProperties(
              URI.create("http://localhost:9000"),
              "employee-media",
              "employee-media-secret",
              "native-media",
              "employee",
              null,
              "us-east-1",
              Duration.ofSeconds(2),
              Duration.ofSeconds(10),
              Duration.ofSeconds(15)));

  private static Employee employee() {
    return new Employee("Budi", PtkpStatus.TK0, "3201234567890123", "1234567890123456");
  }

  private static ExpenseClaim draftClaim(UUID employeeId) {
    return new ExpenseClaim(
        employeeId,
        CATEGORY,
        ORG_UNIT,
        Money.ofMinor(250_000L, "IDR"),
        LocalDate.of(2026, 7, 15),
        "Warung Makan",
        "lunch",
        null);
  }

  @Test
  void uploadReplacesTheClaimsExistingReceiptAtomically() throws Exception {
    Employee me = employee();
    ExpenseClaim claim = draftClaim(me.getId());
    when(employeeRepository.findByUserId(ACTOR)).thenReturn(Optional.of(me));
    when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
    when(receiptRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    ExpenseReceipt result =
        TenantContext.callAs(
            TENANT, ACTOR, () -> writer.upload(claim.getId(), "image/jpeg", JPEG_BYTES.clone()));

    assertThat(result.getClaimId()).isEqualTo(claim.getId());
    assertThat(result.getContentType()).isEqualTo("image/jpeg");
    assertThat(result.getByteSize()).isEqualTo(JPEG_BYTES.length);
    assertThat(result.getCompanyId()).isEqualTo(TENANT);
    // ADR 0048: the row is object-backed — payload in the store under the tenant-scoped
    // content-addressed key, no inline bytea.
    assertThat(result.getObjectKey())
        .startsWith("employee/" + TENANT + "/receipt/")
        .endsWith(".jpg");
    assertThat(result.getData()).isNull();
    verify(mediaStorage, times(1)).put(eq(result.getObjectKey()), any(), any());
    verify(receiptRepository, times(1)).deleteByClaimId(claim.getId());
    verify(receiptRepository, times(1)).saveAndFlush(any());
  }

  @Test
  void uploadToSomeoneElsesClaimThrowsClaimNotFound() throws Exception {
    Employee me = employee();
    ExpenseClaim othersClaim = draftClaim(UUID.randomUUID());
    when(employeeRepository.findByUserId(ACTOR)).thenReturn(Optional.of(me));
    when(claimRepository.findById(othersClaim.getId())).thenReturn(Optional.of(othersClaim));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () -> writer.upload(othersClaim.getId(), "image/jpeg", JPEG_BYTES.clone())))
        .isInstanceOf(ClaimNotFoundException.class);

    verify(receiptRepository, never()).deleteByClaimId(any());
    verify(receiptRepository, never()).saveAndFlush(any());
  }

  @Test
  void uploadToANonDraftClaimThrowsClaimStateException() throws Exception {
    Employee me = employee();
    ExpenseClaim claim = draftClaim(me.getId());
    claim.submit();
    when(employeeRepository.findByUserId(ACTOR)).thenReturn(Optional.of(me));
    when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () -> writer.upload(claim.getId(), "image/jpeg", JPEG_BYTES.clone())))
        .isInstanceOf(ClaimStateException.class);

    verify(receiptRepository, never()).deleteByClaimId(any());
  }

  @Test
  void uploadAnOversizeFileThrowsMaxUploadSizeExceeded() throws Exception {
    Employee me = employee();
    ExpenseClaim claim = draftClaim(me.getId());
    when(employeeRepository.findByUserId(ACTOR)).thenReturn(Optional.of(me));
    when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
    byte[] tooBig = new byte[ExpenseReceipt.MAX_BYTES + 1];

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> writer.upload(claim.getId(), "image/jpeg", tooBig)))
        .isInstanceOf(MaxUploadSizeExceededException.class);

    verify(receiptRepository, never()).deleteByClaimId(any());
  }

  @Test
  void uploadWithASpoofedContentTypeThrowsInvalidReceiptContentType() throws Exception {
    Employee me = employee();
    ExpenseClaim claim = draftClaim(me.getId());
    when(employeeRepository.findByUserId(ACTOR)).thenReturn(Optional.of(me));
    when(claimRepository.findById(claim.getId())).thenReturn(Optional.of(claim));
    // PNG magic bytes, declared as jpeg.
    byte[] pngBytes = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> writer.upload(claim.getId(), "image/jpeg", pngBytes)))
        .isInstanceOf(InvalidReceiptContentTypeException.class);

    verify(receiptRepository, never()).deleteByClaimId(any());
  }

  @Test
  void uploadWithNoEmployeeLinkThrowsEmployeeNotLinked() throws Exception {
    when(employeeRepository.findByUserId(ACTOR)).thenReturn(Optional.empty());
    UUID claimId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> writer.upload(claimId, "image/jpeg", JPEG_BYTES.clone())))
        .isInstanceOf(EmployeeNotLinkedException.class);
  }
}
