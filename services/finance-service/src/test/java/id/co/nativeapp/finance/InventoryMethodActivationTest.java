package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.inventory.domain.InventoryMethodAlreadyActiveException;
import id.co.nativeapp.finance.inventory.domain.InventoryMethodSealedPeriodException;
import id.co.nativeapp.finance.inventory.dto.ActivationResult;
import id.co.nativeapp.finance.inventory.dto.InventoryMethodStatusResponse;
import id.co.nativeapp.finance.inventory.service.InventoryMethodActivationWriter;
import id.co.nativeapp.finance.inventory.service.InventoryMethodStatusReader;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0067 Phase D4/D5 — the perpetual-inventory ACTIVATION matrix: {@link
 * InventoryMethodActivationWriter} writes the once-only election row and (when the opening value is
 * non-zero) a balanced {@code Dr 1100 / Cr 3900} opening entry, in one transaction; {@link
 * InventoryMethodStatusReader} reads it back plus the LIVE {@code 1100} balance and the D5
 * negative-inventory monitor flag. Every test uses its OWN fresh tenant so the once-only backstop
 * ({@code uq_inventory_method_config_company}) never collides across methods — the {@code
 * OpeningBalanceWriterTest} idiom.
 *
 * <p><strong>The safety invariant this class exists to prove.</strong> A company reads {@code
 * active=false} and an untouched {@code 1100} balance unless AND UNTIL this writer is called
 * explicitly — there is no {@code CompanyCreated} auto-activation and no default-active path
 * anywhere in this slice (see {@link #aNonActivatedCompanyStatusReadsActiveFalse}).
 */
@SpringBootTest
class InventoryMethodActivationTest extends PostgresRlsTestBase {

  private static final String ACTOR = "owner@inventory-method.co.id";

  @Autowired private InventoryMethodActivationWriter writer;
  @Autowired private InventoryMethodStatusReader reader;

  @Test
  void activatingAnInactiveCompanyWritesTheConfigRowAndABalancedOpeningEntry() throws Exception {
    String tenant = UUID.randomUUID().toString();

    ActivationResult result =
        TenantContext.callAs(
            tenant, ACTOR, () -> writer.activate("2026-08", 5_000_000L, "IDR", "activate-1"));

    assertThat(result.created()).isTrue();
    assertThat(result.config().isPerpetualActive()).isTrue();
    assertThat(result.config().getMethod()).isEqualTo("PERPETUAL");
    assertThat(result.config().getCutoverPeriod()).isEqualTo("2026-08");
    assertThat(result.config().getCurrency()).isEqualTo("IDR");
    assertThat(result.config().getOpeningEntryId()).isNotNull();
    assertThat(result.config().getActivatedAt()).isNotNull();

    long[] legs = entryLegsAsAdmin(result.config().getOpeningEntryId());
    assertThat(legs[0]).as("Dr 1100").isEqualTo(5_000_000L);
    assertThat(legs[1]).as("Cr 3900").isEqualTo(5_000_000L);
  }

  @Test
  void theActivatedCompanyReadsBackActiveWithInventoryAssetMinorEqualToTheOpeningValue()
      throws Exception {
    String tenant = UUID.randomUUID().toString();
    TenantContext.callAs(
        tenant, ACTOR, () -> writer.activate("2026-08", 3_500_000L, "IDR", "activate-2"));

    InventoryMethodStatusResponse status = TenantContext.callAs(tenant, ACTOR, () -> reader.read());

    assertThat(status.active()).isTrue();
    assertThat(status.method()).isEqualTo("PERPETUAL");
    assertThat(status.cutoverPeriod()).isEqualTo("2026-08");
    assertThat(status.activatedAt()).isNotNull();
    assertThat(status.inventoryAssetMinor()).isEqualTo(3_500_000L);
    assertThat(status.inventoryAssetNegative()).isFalse();
    assertThat(status.currency()).isEqualTo("IDR");
  }

  @Test
  void reactivatingAnAlreadyActiveCompanyIs409AndBooksNoSecondOpeningEntry() throws Exception {
    String tenant = UUID.randomUUID().toString();
    ActivationResult first =
        TenantContext.callAs(
            tenant, ACTOR, () -> writer.activate("2026-08", 1_000_000L, "IDR", "activate-3"));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    tenant,
                    ACTOR,
                    () -> writer.activate("2026-09", 2_000_000L, "IDR", "activate-3-retry")))
        .isInstanceOf(InventoryMethodAlreadyActiveException.class);

    // Exactly the FIRST activation's journal entry exists — no second opening posting.
    assertThat(journalEntryCountAsAdmin(tenant)).isEqualTo(1L);
    assertThat(first.config().getOpeningInventoryValueMinor()).isEqualTo(1_000_000L);
  }

  @Test
  void aSealedCutoverPeriodIsRejected() throws Exception {
    String tenant = UUID.randomUUID().toString();
    sealPeriod(tenant, "2026-06");

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    tenant,
                    ACTOR,
                    () -> writer.activate("2026-06", 1_000_000L, "IDR", "activate-4")))
        .isInstanceOf(InventoryMethodSealedPeriodException.class);

    assertThat(journalEntryCountAsAdmin(tenant)).isZero();
    assertThat(configRowExistsAsAdmin(tenant)).isFalse();
  }

  @Test
  void activationIsTenantIsolatedByRls() throws Exception {
    String tenantA = UUID.randomUUID().toString();
    String tenantB = UUID.randomUUID().toString();

    TenantContext.callAs(
        tenantA, ACTOR, () -> writer.activate("2026-08", 4_000_000L, "IDR", "rls-a-1"));

    // Tenant B sees NOTHING of tenant A's activation.
    InventoryMethodStatusResponse statusB =
        TenantContext.callAs(tenantB, ACTOR, () -> reader.read());
    assertThat(statusB.active()).isFalse();
    assertThat(statusB.inventoryAssetMinor()).isZero();

    // Tenant B activates its OWN — the once-only backstop is per company, not global.
    ActivationResult resultB =
        TenantContext.callAs(
            tenantB, ACTOR, () -> writer.activate("2026-08", 1_500_000L, "IDR", "rls-b-1"));
    assertThat(resultB.created()).isTrue();

    // Tenant A is unaffected by B's activation.
    InventoryMethodStatusResponse statusAAfter =
        TenantContext.callAs(tenantA, ACTOR, () -> reader.read());
    assertThat(statusAAfter.inventoryAssetMinor()).isEqualTo(4_000_000L);
  }

  @Test
  void zeroOpeningValueActivatesWithoutAJournal() throws Exception {
    String tenant = UUID.randomUUID().toString();

    ActivationResult result =
        TenantContext.callAs(
            tenant, ACTOR, () -> writer.activate("2026-08", 0L, "IDR", "activate-5"));

    assertThat(result.created()).isTrue();
    assertThat(result.config().getOpeningEntryId()).isNull();
    assertThat(journalEntryCountAsAdmin(tenant)).isZero();

    InventoryMethodStatusResponse status = TenantContext.callAs(tenant, ACTOR, () -> reader.read());
    assertThat(status.active()).isTrue();
    assertThat(status.inventoryAssetMinor()).isZero();
    assertThat(status.inventoryAssetNegative()).isFalse();
    assertThat(status.currency()).isEqualTo("IDR");
  }

  @Test
  void statusInventoryAssetNegativeFlipsTrueWhen1100GoesNegative() throws Exception {
    String tenant = UUID.randomUUID().toString();
    TenantContext.callAs(
        tenant, ACTOR, () -> writer.activate("2026-08", 100_000L, "IDR", "activate-6"));

    // Drive 1100 net negative directly over the admin (BYPASSRLS) connection — a raw single line,
    // purely to observe the D5 monitor; no production writer ever posts an unbalanced entry
    // (JournalEntry.balanced forbids it), so this deliberately bypasses the domain factory.
    creditInventoryDirectlyAsAdmin(tenant, 250_000L);

    InventoryMethodStatusResponse status = TenantContext.callAs(tenant, ACTOR, () -> reader.read());
    assertThat(status.inventoryAssetMinor()).isEqualTo(-150_000L);
    assertThat(status.inventoryAssetNegative()).isTrue();
  }

  @Test
  void aNonActivatedCompanyStatusReadsActiveFalse() throws Exception {
    String tenant = UUID.randomUUID().toString();

    InventoryMethodStatusResponse status = TenantContext.callAs(tenant, ACTOR, () -> reader.read());

    assertThat(status.active()).isFalse();
    assertThat(status.method()).isNull();
    assertThat(status.cutoverPeriod()).isNull();
    assertThat(status.activatedAt()).isNull();
    assertThat(status.inventoryAssetMinor()).isZero();
    assertThat(status.inventoryAssetNegative()).isFalse();
    assertThat(status.currency()).isNull();
  }

  @Test
  void sameKeyReplayReturnsTheOriginalWithoutPostingAgain() throws Exception {
    String tenant = UUID.randomUUID().toString();
    ActivationResult first =
        TenantContext.callAs(
            tenant, ACTOR, () -> writer.activate("2026-08", 750_000L, "IDR", "replay-1"));

    ActivationResult replay =
        TenantContext.callAs(
            tenant, ACTOR, () -> writer.activate("2026-08", 750_000L, "IDR", "replay-1"));

    assertThat(replay.created()).isFalse();
    assertThat(replay.config().getId()).isEqualTo(first.config().getId());
    assertThat(journalEntryCountAsAdmin(tenant)).isEqualTo(1L);
  }

  @Test
  void malformedCutoverPeriodIsRejected() throws Exception {
    String tenant = UUID.randomUUID().toString();
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    tenant, ACTOR, () -> writer.activate("2026/08", 1_000L, "IDR", "bad-period")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void negativeOpeningValueIsRejected() throws Exception {
    String tenant = UUID.randomUUID().toString();
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    tenant, ACTOR, () -> writer.activate("2026-08", -1L, "IDR", "bad-value")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void twoConcurrentFirstTimeActivationsBookExactlyOneOpeningEntry() throws Exception {
    // The money once-only property under concurrency (ENGINEERING-STANDARDS §3.2): two threads race
    // to first-activate the SAME company with the SAME key + payload. The per-company advisory lock
    // serializes them, so exactly ONE books the config row + the Dr 1100 / Cr 3900 opening entry;
    // the other, once the lock frees, sees the committed row + a matching key and REPLAYS (no
    // second
    // entry). The load-bearing invariant — exactly one opening entry, never two — holds regardless
    // of
    // interleaving, so tolerate the loser being a replay (created=false) OR a same-key 409.
    String tenant = UUID.randomUUID().toString();
    long openingValue = 5_000_000L;

    CyclicBarrier startLine = new CyclicBarrier(2);
    List<ActivationResult> results = new CopyOnWriteArrayList<>();
    List<Throwable> errors = new CopyOnWriteArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Runnable activate =
          () -> {
            try {
              startLine.await(5, TimeUnit.SECONDS);
              results.add(
                  TenantContext.callAs(
                      tenant,
                      ACTOR,
                      () -> writer.activate("2026-08", openingValue, "IDR", "race-activate")));
            } catch (Throwable t) {
              errors.add(t);
            }
          };
      pool.submit(activate);
      pool.submit(activate);
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    // Exactly one opening journal entry + one config row, system-wide for this tenant — the guard.
    assertThat(journalEntryCountAsAdmin(tenant))
        .as("the once-only guard must book exactly ONE Dr 1100 / Cr 3900 opening entry")
        .isEqualTo(1L);
    assertThat(configRowExistsAsAdmin(tenant)).isTrue();

    // Exactly one thread created it; the other replayed (created=false) or 409'd — never a 2nd
    // create.
    long created = results.stream().filter(ActivationResult::created).count();
    assertThat(created).as("exactly one concurrent caller may be the creator").isEqualTo(1L);
    if (errors.isEmpty()) {
      assertThat(results).as("both returned: one create, one replay").hasSize(2);
    } else {
      assertThat(errors).singleElement().isInstanceOf(InventoryMethodAlreadyActiveException.class);
    }
  }

  // ----------------------------------------------------------------------- admin (BYPASSRLS)
  // helpers

  private long[] entryLegsAsAdmin(UUID entryId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COALESCE(SUM(debit_minor),0), COALESCE(SUM(credit_minor),0)"
                    + " FROM journal_line WHERE entry_id = ?")) {
      ps.setObject(1, entryId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return new long[] {rs.getLong(1), rs.getLong(2)};
      }
    }
  }

  private long journalEntryCountAsAdmin(String tenant) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM journal_entry WHERE company_id = ?")) {
      ps.setString(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private boolean configRowExistsAsAdmin(String tenant) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM inventory_method_config WHERE company_id = ?)")) {
      ps.setString(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  private void creditInventoryDirectlyAsAdmin(String tenant, long amountMinor) throws Exception {
    UUID entryId = UUID.randomUUID();
    try (Connection admin = admin()) {
      try (PreparedStatement ps =
          admin.prepareStatement(
              "INSERT INTO journal_entry (id, period, occurred_at, description, status,"
                  + " posting_role, currency, source_event_id, uses_illustrative_rules,"
                  + " created_at, created_by, updated_at, updated_by, version, company_id)"
                  + " VALUES (?, '2026-08', now(), 'test credit', 'POSTED', 'PRIMARY', 'IDR', ?,"
                  + " false, now(), 'test', now(), 'test', 0, ?)")) {
        ps.setObject(1, entryId);
        ps.setObject(2, UUID.randomUUID());
        ps.setString(3, tenant);
        ps.executeUpdate();
      }
      try (PreparedStatement ps =
          admin.prepareStatement(
              "INSERT INTO journal_line (id, entry_id, line_no, account_code, debit_minor,"
                  + " credit_minor, currency, created_at, created_by, updated_at, updated_by,"
                  + " version, company_id)"
                  + " VALUES (?, ?, 1, '1100', 0, ?, 'IDR', now(), 'test', now(), 'test', 0, ?)")) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, entryId);
        ps.setLong(3, amountMinor);
        ps.setString(4, tenant);
        ps.executeUpdate();
      }
    }
  }

  private void sealPeriod(String tenant, String period) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO tax_filing (id, period, tax_type, status, currency,"
                    + " output_vat_minor, input_vat_minor, net_minor, net_direction,"
                    + " filing_entry_id, filed_at, created_at, created_by, updated_at, updated_by,"
                    + " version, company_id)"
                    + " VALUES (?, ?, 'PPN', 'FILED', 'IDR', 0, 0, 0, 'CREDITABLE', ?, now(),"
                    + " now(), ?, now(), ?, 0, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, period);
      ps.setObject(3, UUID.randomUUID());
      ps.setString(4, ACTOR);
      ps.setString(5, ACTOR);
      ps.setString(6, tenant);
      ps.executeUpdate();
    }
  }

  private Connection admin() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
