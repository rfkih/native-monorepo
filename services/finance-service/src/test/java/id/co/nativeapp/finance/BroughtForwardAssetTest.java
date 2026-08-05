package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.assets.domain.AssetSealedPeriodException;
import id.co.nativeapp.finance.assets.dto.AssetResponse;
import id.co.nativeapp.finance.assets.service.AcquireAssetResult;
import id.co.nativeapp.finance.assets.service.AmortizationRunWriter;
import id.co.nativeapp.finance.assets.service.AssetDisposalWriter;
import id.co.nativeapp.finance.assets.service.AssetReader;
import id.co.nativeapp.finance.assets.service.FixedAssetWriter;
import id.co.nativeapp.finance.gl.service.GlTrialBalanceReader;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Brought-forward (pre-owned) fixed assets for business migration (ADR 0037). Registering posts a
 * CASH-FREE entry ({@code Dr 1500 gross / Cr 1590 opening / Cr 3900 net}), the register's book
 * value reflects the opening accumulated depreciation, the monthly run depreciates only the
 * REMAINING base over the REMAINING life, and disposal derecognizes the combined (opening + run)
 * accumulated. A fresh tenant per test keeps the asset register + run periods isolated across
 * methods.
 */
@SpringBootTest
class BroughtForwardAssetTest extends PostgresRlsTestBase {

  private static final String ACTOR = "broughtforward@integration.co.id";
  private final String tenant = UUID.randomUUID().toString();

  @Autowired private FixedAssetWriter fixedAssetWriter;
  @Autowired private AmortizationRunWriter amortizationRunWriter;
  @Autowired private AssetDisposalWriter assetDisposalWriter;
  @Autowired private AssetReader assetReader;
  @Autowired private GlTrialBalanceReader glTrialBalanceReader;
  @Autowired private Clock clock;

  @Test
  void registerIsCashFreeAndDepreciatesRemainingBaseOverRemainingLife() throws Exception {
    String thisPeriod = LedgerPosting.periodOf(clock.instant());
    String nextPeriod =
        YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).plusMonths(1).toString();
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

    // A 20,000,000 machine, already depreciated 8,000,000, with 24 months of life left.
    UUID assetId =
        TenantContext.callAs(
            tenant,
            ACTOR,
            () ->
                fixedAssetWriter
                    .registerBroughtForward(
                        "Old oven", today, 20_000_000L, 0L, 8_000_000L, 24, "IDR", "bf-1")
                    .assetId());

    // The opening entry is cash-free: Dr 1500 gross / Cr 1590 opening / Cr 3900 net book value.
    TenantContext.callAs(
        tenant,
        ACTOR,
        () -> {
          assertThat(debitNet(thisPeriod, "1500")).isEqualTo(20_000_000L);
          assertThat(debitNet(thisPeriod, "1590")).isEqualTo(-8_000_000L);
          assertThat(debitNet(thisPeriod, "3900")).isEqualTo(-12_000_000L);
          assertThat(debitNet(thisPeriod, "1900")).as("no cash moved").isZero();
          return null;
        });

    // The register reflects the opening accumulated depreciation in the book value.
    List<AssetResponse> register =
        TenantContext.callAs(tenant, ACTOR, () -> assetReader.register());
    assertThat(register).hasSize(1);
    assertThat(register.getFirst().accumulatedMinor()).isEqualTo(8_000_000L);
    assertThat(register.getFirst().bookValueMinor()).isEqualTo(12_000_000L);

    // Run NEXT month: depreciableBase = 20,000,000 − 0 − 8,000,000 = 12,000,000 over 24 = 500,000.
    TenantContext.callAs(tenant, ACTOR, () -> amortizationRunWriter.run(nextPeriod));
    TenantContext.callAs(
        tenant,
        ACTOR,
        () -> {
          assertThat(debitNet(nextPeriod, "5500")).isEqualTo(500_000L); // Dr depreciation expense
          assertThat(debitNet(nextPeriod, "1590")).isEqualTo(-500_000L); // Cr accumulated
          return null;
        });

    List<AssetResponse> afterRun =
        TenantContext.callAs(tenant, ACTOR, () -> assetReader.register());
    assertThat(afterRun.getFirst().accumulatedMinor()).isEqualTo(8_500_000L); // opening + one month
    assertThat(afterRun.getFirst().bookValueMinor()).isEqualTo(11_500_000L);
  }

  @Test
  void disposalDerecognizesTheOpeningAccumulatedDepreciation() throws Exception {
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

    UUID assetId =
        TenantContext.callAs(
            tenant,
            ACTOR,
            () ->
                fixedAssetWriter
                    .registerBroughtForward(
                        "Old oven", today, 20_000_000L, 0L, 8_000_000L, 24, "IDR", "bf-disp")
                    .assetId());

    // Dispose immediately (no runs yet; start_period is next month, so depreciation is in step).
    TenantContext.callAs(
        tenant,
        ACTOR,
        () -> assetDisposalWriter.dispose(assetId, today, 15_000_000L, "IDR", "bf-disp-key"));

    // The disposal entry derecognizes the OPENING accumulated (8,000,000), not zero: Dr 1900 15M +
    // Dr 1590 8M / Cr 1500 20M + Cr 4200 gain 3M (15M + 8M − 20M).
    UUID entryId = entryIdBySourceAsAdmin(AssetDisposalWriter.disposalEventId(assetId));
    Map<String, long[]> legs = entryLegsAsAdmin(entryId);
    assertThat(legs.get("1590")[0]).isEqualTo(8_000_000L); // Dr accumulated = opening
    assertThat(legs.get("1500")[1]).isEqualTo(20_000_000L); // Cr cost
    assertThat(legs.get("1900")[0]).isEqualTo(15_000_000L); // Dr proceeds
    assertThat(legs.get("4200")[1]).isEqualTo(3_000_000L); // Cr gain on disposal
  }

  @Test
  void registerBroughtForwardRejectsATaxSealedPeriod() throws Exception {
    // The as-of month already has a tax_filing row (sealed), mirroring OpeningBalanceWriter's
    // requireNotSealed guard. Console posts brought-forward assets BEFORE the main opening entry
    // (ADR 0037): without this guard a sealed as-of date would silently restate the sealed month.
    LocalDate asOfDate = LocalDate.of(2024, 3, 15);
    String period = LedgerPosting.periodOf(asOfDate.atStartOfDay(ZoneOffset.UTC).toInstant());
    seedSealedPeriod(tenant, period);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    tenant,
                    ACTOR,
                    () ->
                        fixedAssetWriter.registerBroughtForward(
                            "Sealed oven",
                            asOfDate,
                            20_000_000L,
                            0L,
                            8_000_000L,
                            24,
                            "IDR",
                            "bf-sealed")))
        .isInstanceOf(AssetSealedPeriodException.class);

    assertThat(journalEntryCountAsAdmin(tenant))
        .as("no journal entry may post into a tax-sealed period")
        .isZero();
    assertThat(fixedAssetCountAsAdmin(tenant))
        .as("no fixed_asset row may be registered against a rejected, sealed posting")
        .isZero();
  }

  @Test
  void sealedRejectionDoesNotBurnTheIdempotencyKeyAndARetryWithAnOpenPeriodSucceeds()
      throws Exception {
    LocalDate sealedAsOfDate = LocalDate.of(2024, 3, 15);
    String sealedPeriod =
        LedgerPosting.periodOf(sealedAsOfDate.atStartOfDay(ZoneOffset.UTC).toInstant());
    seedSealedPeriod(tenant, sealedPeriod);
    String key = "bf-sealed-retry";

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    tenant,
                    ACTOR,
                    () ->
                        fixedAssetWriter.registerBroughtForward(
                            "Retry oven",
                            sealedAsOfDate,
                            20_000_000L,
                            0L,
                            8_000_000L,
                            24,
                            "IDR",
                            key)))
        .isInstanceOf(AssetSealedPeriodException.class);

    // The rejected attempt never persisted a fixed_asset row under this key, so retrying the SAME
    // key with a LATER, open (unsealed) as-of date is a genuine fresh registration — NOT a replay
    // of the failed attempt — and must succeed.
    LocalDate openAsOfDate = LocalDate.of(2024, 6, 15);
    AcquireAssetResult result =
        TenantContext.callAs(
            tenant,
            ACTOR,
            () ->
                fixedAssetWriter.registerBroughtForward(
                    "Retry oven", openAsOfDate, 20_000_000L, 0L, 8_000_000L, 24, "IDR", key));

    assertThat(result.created()).isTrue();
    assertThat(journalEntryCountAsAdmin(tenant)).isEqualTo(1L);
    assertThat(fixedAssetCountAsAdmin(tenant)).isEqualTo(1L);
  }

  /** The debit-net ({@code Σdebit − Σcredit}) of {@code accountCode} in {@code period}'s GL. */
  private long debitNet(String period, String accountCode) {
    return glTrialBalanceReader.read(period).stream()
        .filter(l -> l.getAccountCode().equals(accountCode))
        .findFirst()
        .map(l -> l.getTotalDebitMinor() - l.getTotalCreditMinor())
        .orElse(0L);
  }

  /**
   * Seeds a {@code tax_filing} row directly (BYPASSRLS admin connection, the same idiom {@link
   * PostgresRlsTestBase}'s own admin helpers use) so {@code period} reads as sealed for {@code
   * tenant} — the minimal valid row shape per the {@code tax_filing} CHECK constraints; the
   * FIXED_ASSET guard only probes existence, not these values.
   */
  private void seedSealedPeriod(String tenant, String period) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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

  /** The total {@code journal_entry} row count for {@code tenant}, over the BYPASSRLS admin. */
  private long journalEntryCountAsAdmin(String tenant) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM journal_entry WHERE company_id = ?")) {
      ps.setString(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** The total {@code fixed_asset} row count for {@code tenant}, over the BYPASSRLS admin. */
  private long fixedAssetCountAsAdmin(String tenant) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM fixed_asset WHERE company_id = ?")) {
      ps.setString(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private UUID entryIdBySourceAsAdmin(UUID sourceEventId) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement("SELECT id FROM journal_entry WHERE source_event_id = ?")) {
      ps.setObject(1, sourceEventId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private Map<String, long[]> entryLegsAsAdmin(UUID entryId) throws Exception {
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT account_code, debit_minor, credit_minor FROM journal_line"
                    + " WHERE entry_id = ?")) {
      ps.setObject(1, entryId);
      try (ResultSet rs = ps.executeQuery()) {
        Map<String, long[]> legs = new HashMap<>();
        while (rs.next()) {
          legs.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
        }
        return legs;
      }
    }
  }
}
