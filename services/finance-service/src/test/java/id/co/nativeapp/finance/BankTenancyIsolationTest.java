package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.bank.domain.BankAccountNotFoundException;
import id.co.nativeapp.finance.bank.domain.ReconciliationCategory;
import id.co.nativeapp.finance.bank.dto.BankAccountResponse;
import id.co.nativeapp.finance.bank.dto.ReconciliationReportResponse;
import id.co.nativeapp.finance.bank.dto.StatementLineResponse;
import id.co.nativeapp.finance.bank.service.BankAccountReader;
import id.co.nativeapp.finance.bank.service.BankAccountWriter;
import id.co.nativeapp.finance.bank.service.ReconciliationReader;
import id.co.nativeapp.finance.bank.service.ReconciliationWriter;
import id.co.nativeapp.finance.bank.service.StatementLineInput;
import id.co.nativeapp.finance.bank.service.StatementLineReader;
import id.co.nativeapp.finance.bank.service.StatementLineWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * End-to-end bank-reconciliation test against real PostgreSQL 16 as the unprivileged {@code
 * app_user} — it drives the full writer → double-entry GL path (create a bank account → import a
 * +5,000,000 deposit line → reconcile CLEARING → import a −25,000 withdrawal line → reconcile
 * BANK_FEE) and proves cross-tenant isolation via AUTO-applied RLS: everything tenant A creates is
 * invisible to tenant B. The writers bind the tenant from {@link TenantContext}; the reads carry NO
 * {@code WHERE company_id} — only the RLS aspect scopes them (rule 5). {@code FORCE ROW LEVEL
 * SECURITY} on the bank tables binds even the owning role. Mirrors {@link ApTenancyIsolationTest}.
 */
@SpringBootTest
class BankTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "ffffffff-ffff-ffff-ffff-eeeeeeeeeee1";
  private static final String TENANT_B = "ffffffff-ffff-ffff-ffff-eeeeeeeeeee2";
  private static final String ACTOR_A = "bank-a@isolation.co.id";
  private static final String ACTOR_B = "viewer-b@isolation.co.id";

  @Autowired private BankAccountWriter bankAccountWriter;
  @Autowired private BankAccountReader bankAccountReader;
  @Autowired private StatementLineWriter statementLineWriter;
  @Autowired private StatementLineReader statementLineReader;
  @Autowired private ReconciliationWriter reconciliationWriter;
  @Autowired private ReconciliationReader reconciliationReader;

  @Test
  void bankDataUnderTenantAIsInvisibleToTenantBAndTheGlBalancesMoveOnReconcile() throws Exception {
    // Tenant A: create a bank account, import + reconcile a deposit (CLEARING sweep), then import
    // + reconcile a withdrawal (BANK_FEE).
    UUID bankAccountId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> {
              BankAccountResponse account =
                  bankAccountWriter.create("BCA Operating", "BCA", "1234567890", "IDR");

              List<StatementLineResponse> deposit =
                  statementLineWriter.importLines(
                      account.id(),
                      List.of(
                          new StatementLineInput(
                              LocalDate.parse("2026-06-14"),
                              5_000_000L,
                              "Customer sweep",
                              "REF-DEP-1")));
              reconciliationWriter.reconcile(deposit.get(0).id(), ReconciliationCategory.CLEARING);

              List<StatementLineResponse> fee =
                  statementLineWriter.importLines(
                      account.id(),
                      List.of(
                          new StatementLineInput(
                              LocalDate.parse("2026-06-15"),
                              -25_000L,
                              "Monthly admin fee",
                              "REF-FEE-1")));
              reconciliationWriter.reconcile(fee.get(0).id(), ReconciliationCategory.BANK_FEE);

              return account.id();
            });

    // Tenant A's reconciliation report: bank balance nets the two RECONCILED lines; the
    // CASH_CLEARING (1900) GL balance moved by exactly the CLEARING sweep (the BANK_FEE leg never
    // touches 1900); nothing left UNRECONCILED.
    ReconciliationReportResponse report =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> reconciliationReader.report(bankAccountId));
    assertThat(report.bankBalanceMinor()).isEqualTo(4_975_000L); // 5,000,000 - 25,000
    assertThat(report.cashClearingBalanceMinor()).isEqualTo(-5_000_000L); // swept OUT of clearing
    assertThat(report.unreconciledCount()).isZero();
    assertThat(report.unreconciledLines()).isEmpty();

    List<StatementLineResponse> aLines =
        TenantContext.callAs(
            TENANT_A, ACTOR_A, () -> statementLineReader.list(bankAccountId, null));
    assertThat(aLines).hasSize(2);
    assertThat(aLines).allMatch(l -> l.status().equals("RECONCILED"));

    // Tenant B sees nothing: bank-account list is empty, and a detail/report read of A's account
    // is a 404 (RLS-invisible — anti-enumeration).
    List<BankAccountResponse> bAccounts =
        TenantContext.callAs(TENANT_B, ACTOR_B, bankAccountReader::list);
    assertThat(bAccounts).as("tenant B must see none of A's bank accounts (RLS)").isEmpty();

    assertThatThrownBy(
            () ->
                TenantContext.callAs(TENANT_B, ACTOR_B, () -> bankAccountReader.get(bankAccountId)))
        .as("tenant B reading A's bank account is a 404 (RLS-invisible)")
        .isInstanceOf(BankAccountNotFoundException.class);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_B, ACTOR_B, () -> reconciliationReader.report(bankAccountId)))
        .as("tenant B reading A's reconciliation report is a 404 (RLS-invisible)")
        .isInstanceOf(BankAccountNotFoundException.class);
  }
}
