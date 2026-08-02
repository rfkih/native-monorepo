package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.expense.domain.ClaimStateException;
import id.co.nativeapp.employee.expense.domain.ClaimStatus;
import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.domain.RefusalCommentRequiredException;
import id.co.nativeapp.employee.expense.domain.ReimbursementMethod;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure-JUnit domain logic proofs — no Spring context: every legal and illegal transition on {@link
 * ExpenseClaim} (ADR 0030 §5, the AP-Bill idiom), refuse-requires-comment, and that approve stamps
 * the decision fields.
 */
class ExpenseClaimTest {

  private static final UUID EMPLOYEE = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final UUID CATEGORY = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final UUID ORG_UNIT = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final Money AMOUNT = Money.ofMinor(250_000L, "IDR");
  private static final LocalDate EXPENSE_DATE = LocalDate.of(2026, 7, 15);

  private static ExpenseClaim newDraft() {
    return new ExpenseClaim(
        EMPLOYEE, CATEGORY, ORG_UNIT, AMOUNT, EXPENSE_DATE, "Warung Makan", "lunch", null);
  }

  // ---------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------

  @Test
  void aFreshClaimIsDraftWithTheGivenFields() {
    ExpenseClaim claim = newDraft();
    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.DRAFT);
    assertThat(claim.getEmployeeId()).isEqualTo(EMPLOYEE);
    assertThat(claim.getCategoryId()).isEqualTo(CATEGORY);
    assertThat(claim.getOrgUnitId()).isEqualTo(ORG_UNIT);
    assertThat(claim.getAmount()).isEqualTo(AMOUNT);
    assertThat(claim.getExpenseDate()).isEqualTo(EXPENSE_DATE);
    assertThat(claim.getMerchant()).isEqualTo("Warung Makan");
    assertThat(claim.getNote()).isEqualTo("lunch");
  }

  @Test
  void aNullReimbursementMethodDefaultsToPayroll() {
    ExpenseClaim claim = newDraft();
    assertThat(claim.getReimbursementMethod()).isEqualTo(ReimbursementMethod.PAYROLL);
  }

  @Test
  void anExplicitReimbursementMethodIsHonored() {
    ExpenseClaim claim =
        new ExpenseClaim(
            EMPLOYEE,
            CATEGORY,
            ORG_UNIT,
            AMOUNT,
            EXPENSE_DATE,
            null,
            null,
            ReimbursementMethod.DIRECT);
    assertThat(claim.getReimbursementMethod()).isEqualTo(ReimbursementMethod.DIRECT);
  }

  @Test
  void aZeroOrNegativeAmountIsRejected() {
    assertThatThrownBy(
            () ->
                new ExpenseClaim(
                    EMPLOYEE,
                    CATEGORY,
                    ORG_UNIT,
                    Money.ofMinor(0L, "IDR"),
                    EXPENSE_DATE,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blankMerchantAndNoteNormalizeToNull() {
    ExpenseClaim claim =
        new ExpenseClaim(EMPLOYEE, CATEGORY, ORG_UNIT, AMOUNT, EXPENSE_DATE, "  ", "  ", null);
    assertThat(claim.getMerchant()).isNull();
    assertThat(claim.getNote()).isNull();
  }

  // ---------------------------------------------------------------------------
  // submit()
  // ---------------------------------------------------------------------------

  @Test
  void submitFromDraftMovesToSubmitted() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
  }

  @Test
  void submitFromNonDraftIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    assertThatThrownBy(claim::submit).isInstanceOf(ClaimStateException.class);
  }

  // ---------------------------------------------------------------------------
  // approve()
  // ---------------------------------------------------------------------------

  @Test
  void approveFromSubmittedMovesToApprovedAndStampsTheDecision() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    Instant approvedAt = Instant.parse("2026-07-20T09:00:00Z");

    claim.approve("manager-sub", "looks good", approvedAt);

    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
    assertThat(claim.getApprovedAt()).isEqualTo(approvedAt);
    assertThat(claim.getDecidedAt()).isEqualTo(approvedAt);
    assertThat(claim.getDecidedBy()).isEqualTo("manager-sub");
    assertThat(claim.getDecisionComment()).isEqualTo("looks good");
  }

  @Test
  void approveWithANullCommentIsAllowed() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.approve("manager-sub", null, Instant.parse("2026-07-20T09:00:00Z"));
    assertThat(claim.getDecisionComment()).isNull();
  }

  @Test
  void approveFromDraftIsRejected() {
    ExpenseClaim claim = newDraft();
    assertThatThrownBy(
            () -> claim.approve("manager-sub", null, Instant.parse("2026-07-20T09:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
  }

  @Test
  void approveFromAlreadyApprovedIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.approve("manager-sub", null, Instant.parse("2026-07-20T09:00:00Z"));
    assertThatThrownBy(
            () -> claim.approve("manager-sub", null, Instant.parse("2026-07-21T09:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
  }

  // ---------------------------------------------------------------------------
  // refuse()
  // ---------------------------------------------------------------------------

  @Test
  void refuseFromSubmittedMovesToRefusedAndStampsTheDecision() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    Instant decidedAt = Instant.parse("2026-07-20T09:00:00Z");

    claim.refuse("manager-sub", "missing receipt", decidedAt);

    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REFUSED);
    assertThat(claim.getDecidedAt()).isEqualTo(decidedAt);
    assertThat(claim.getDecidedBy()).isEqualTo("manager-sub");
    assertThat(claim.getDecisionComment()).isEqualTo("missing receipt");
    // Refusal never stamps approvedAt.
    assertThat(claim.getApprovedAt()).isNull();
  }

  @Test
  void refuseWithANullCommentIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    assertThatThrownBy(
            () -> claim.refuse("manager-sub", null, Instant.parse("2026-07-20T09:00:00Z")))
        .isInstanceOf(RefusalCommentRequiredException.class);
  }

  @Test
  void refuseWithABlankCommentIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    assertThatThrownBy(
            () -> claim.refuse("manager-sub", "   ", Instant.parse("2026-07-20T09:00:00Z")))
        .isInstanceOf(RefusalCommentRequiredException.class);
  }

  @Test
  void refuseFromDraftIsRejected() {
    ExpenseClaim claim = newDraft();
    assertThatThrownBy(
            () -> claim.refuse("manager-sub", "no", Instant.parse("2026-07-20T09:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
  }

  // ---------------------------------------------------------------------------
  // payDirect() — DIRECT reimbursement (ADR 0030 §6, Phase E4)
  // ---------------------------------------------------------------------------

  @Test
  void payDirectFromApprovedAndUnlinkedMovesToReimbursedAndStampsSettledAt() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.approve("manager-sub", "ok", Instant.parse("2026-07-20T09:00:00Z"));
    Instant settledAt = Instant.parse("2026-07-21T10:00:00Z");

    claim.payDirect(settledAt);

    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REIMBURSED);
    assertThat(claim.getSettledAt()).isEqualTo(settledAt);
  }

  @Test
  void payDirectFromDraftIsRejected() {
    ExpenseClaim claim = newDraft();
    assertThatThrownBy(() -> claim.payDirect(Instant.parse("2026-07-21T10:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
  }

  @Test
  void payDirectFromSubmittedIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    assertThatThrownBy(() -> claim.payDirect(Instant.parse("2026-07-21T10:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
  }

  @Test
  void payDirectFromRefusedIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.refuse("manager-sub", "no receipt", Instant.parse("2026-07-20T09:00:00Z"));
    assertThatThrownBy(() -> claim.payDirect(Instant.parse("2026-07-21T10:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
  }

  @Test
  void payDirectFromCancelledIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.cancel();
    assertThatThrownBy(() -> claim.payDirect(Instant.parse("2026-07-21T10:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
  }

  @Test
  void payDirectFromAlreadyReimbursedIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.approve("manager-sub", "ok", Instant.parse("2026-07-20T09:00:00Z"));
    claim.payDirect(Instant.parse("2026-07-21T10:00:00Z"));
    assertThatThrownBy(() -> claim.payDirect(Instant.parse("2026-07-22T10:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
  }

  @Test
  void payDirectWhenLinkedToAPayrollRunIsRejectedEvenThoughApproved() {
    // The linker (E5) sets reimbursement_run_id via an atomic conditional UPDATE that bypasses
    // the aggregate's own API entirely — simulated here via the JPA field directly (no production
    // setter exists for it yet, deliberately: nothing in E4's scope should add one).
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.approve("manager-sub", "ok", Instant.parse("2026-07-20T09:00:00Z"));
    ReflectionTestUtils.setField(claim, "reimbursementRunId", UUID.randomUUID());

    assertThatThrownBy(() -> claim.payDirect(Instant.parse("2026-07-21T10:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
    // The status is left untouched — a rejected attempt is a pure no-op.
    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
  }

  // ---------------------------------------------------------------------------
  // settleByPayrollRun() — PAYROLL reimbursement (ADR 0030 §6, Phase E5)
  // ---------------------------------------------------------------------------

  @Test
  void settleByPayrollRunFromApprovedAndLinkedMovesToReimbursedAndStampsSettledAt() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.approve("manager-sub", "ok", Instant.parse("2026-07-20T09:00:00Z"));
    UUID runId = UUID.randomUUID();
    ReflectionTestUtils.setField(claim, "reimbursementRunId", runId);
    Instant settledAt = Instant.parse("2026-07-25T10:00:00Z");

    claim.settleByPayrollRun(settledAt);

    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REIMBURSED);
    assertThat(claim.getSettledAt()).isEqualTo(settledAt);
    // The link itself is left untouched — settleByPayrollRun never sets/clears
    // reimbursement_run_id (only the linker's own conditional UPDATEs do, ADR 0030 §6 BINDING).
    assertThat(claim.getReimbursementRunId()).isEqualTo(runId);
  }

  @Test
  void settleByPayrollRunFromApprovedButNotLinkedIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.approve("manager-sub", "ok", Instant.parse("2026-07-20T09:00:00Z"));
    // No reimbursement_run_id set — the linker never claimed this one.
    assertThatThrownBy(() -> claim.settleByPayrollRun(Instant.parse("2026-07-25T10:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.APPROVED);
  }

  @Test
  void settleByPayrollRunFromDraftIsRejected() {
    ExpenseClaim claim = newDraft();
    ReflectionTestUtils.setField(claim, "reimbursementRunId", UUID.randomUUID());
    assertThatThrownBy(() -> claim.settleByPayrollRun(Instant.parse("2026-07-25T10:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
  }

  @Test
  void settleByPayrollRunFromAlreadyReimbursedIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.approve("manager-sub", "ok", Instant.parse("2026-07-20T09:00:00Z"));
    ReflectionTestUtils.setField(claim, "reimbursementRunId", UUID.randomUUID());
    claim.settleByPayrollRun(Instant.parse("2026-07-25T10:00:00Z"));

    assertThatThrownBy(() -> claim.settleByPayrollRun(Instant.parse("2026-07-26T10:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
  }

  @Test
  void settleByPayrollRunFromRefusedIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.refuse("manager-sub", "no receipt", Instant.parse("2026-07-20T09:00:00Z"));
    ReflectionTestUtils.setField(claim, "reimbursementRunId", UUID.randomUUID());
    assertThatThrownBy(() -> claim.settleByPayrollRun(Instant.parse("2026-07-25T10:00:00Z")))
        .isInstanceOf(ClaimStateException.class);
  }

  // ---------------------------------------------------------------------------
  // cancel()
  // ---------------------------------------------------------------------------

  @Test
  void cancelFromDraftMovesToCancelled() {
    ExpenseClaim claim = newDraft();
    claim.cancel();
    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.CANCELLED);
  }

  @Test
  void cancelFromSubmittedMovesToCancelled() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.cancel();
    assertThat(claim.getStatus()).isEqualTo(ClaimStatus.CANCELLED);
  }

  @Test
  void cancelFromApprovedIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.approve("manager-sub", null, Instant.parse("2026-07-20T09:00:00Z"));
    assertThatThrownBy(claim::cancel).isInstanceOf(ClaimStateException.class);
  }

  @Test
  void cancelFromRefusedIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    claim.refuse("manager-sub", "no receipt", Instant.parse("2026-07-20T09:00:00Z"));
    assertThatThrownBy(claim::cancel).isInstanceOf(ClaimStateException.class);
  }

  @Test
  void cancelFromAlreadyCancelledIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.cancel();
    assertThatThrownBy(claim::cancel).isInstanceOf(ClaimStateException.class);
  }

  // ---------------------------------------------------------------------------
  // applyDraftEdit()
  // ---------------------------------------------------------------------------

  @Test
  void applyDraftEditWhileDraftReplacesEveryEditableField() {
    ExpenseClaim claim = newDraft();
    UUID newCategory = UUID.randomUUID();
    UUID newOrgUnit = UUID.randomUUID();
    Money newAmount = Money.ofMinor(500_000L, "IDR");
    LocalDate newDate = LocalDate.of(2026, 7, 16);

    claim.applyDraftEdit(
        newCategory,
        newOrgUnit,
        newAmount,
        newDate,
        "New Merchant",
        "new note",
        ReimbursementMethod.DIRECT);

    assertThat(claim.getCategoryId()).isEqualTo(newCategory);
    assertThat(claim.getOrgUnitId()).isEqualTo(newOrgUnit);
    assertThat(claim.getAmount()).isEqualTo(newAmount);
    assertThat(claim.getExpenseDate()).isEqualTo(newDate);
    assertThat(claim.getMerchant()).isEqualTo("New Merchant");
    assertThat(claim.getNote()).isEqualTo("new note");
    assertThat(claim.getReimbursementMethod()).isEqualTo(ReimbursementMethod.DIRECT);
  }

  @Test
  void applyDraftEditAfterSubmitIsRejected() {
    ExpenseClaim claim = newDraft();
    claim.submit();
    assertThatThrownBy(
            () -> claim.applyDraftEdit(CATEGORY, ORG_UNIT, AMOUNT, EXPENSE_DATE, null, null, null))
        .isInstanceOf(ClaimStateException.class);
  }
}
