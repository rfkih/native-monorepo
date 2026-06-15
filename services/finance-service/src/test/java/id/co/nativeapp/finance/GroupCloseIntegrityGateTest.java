package id.co.nativeapp.finance;

import static id.co.nativeapp.finance.GroupCloseFixtures.asset;
import static id.co.nativeapp.finance.GroupCloseFixtures.expense;
import static id.co.nativeapp.finance.GroupCloseFixtures.revenue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;

import id.co.nativeapp.finance.consolidation.domain.ConsolidationEntryType;
import id.co.nativeapp.finance.consolidation.domain.ConsolidationLedgerEntry;
import id.co.nativeapp.finance.consolidation.domain.ConsolidationPostingRole;
import id.co.nativeapp.finance.consolidation.repository.ConsolidationLedgerRepository;
import id.co.nativeapp.finance.consolidation.service.GroupCloseService;
import id.co.nativeapp.finance.group.service.GroupReadModelService;
import id.co.nativeapp.finance.grouptb.service.GroupTrialBalanceIngestService;
import id.co.nativeapp.money.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * P3d SEAM 3b (#41) — the post-CTA INTEGRITY GATE, the one real invariant the gate protects. The
 * gate recomputes, along a STRUCTURALLY DIFFERENT path, {@code signed(translated TB) + sum(CTA that
 * LANDED in 3950)} and asserts zero. On a valid close it is silent; this test forces the one
 * failure it exists to catch — a CTA magnitude that does NOT cancel the translated residual — and
 * asserts the gate THROWS and the whole close rolls back atomically (no summary, no partial
 * ledger).
 *
 * <p>To inject the corruption WITHOUT weakening production code, the {@link
 * ConsolidationLedgerRepository} is spied and its {@code findCtaPrimaries} (the read the gate uses
 * to sum what landed in 3950) is stubbed to return a CTA entry whose magnitude is OFF by 1,000
 * minor. The real CTA still posts to the ledger; only the gate's re-read of it is corrupted, so the
 * gate sees {@code residual + wrongCta != 0} and fires — exactly the detection it is there for.
 */
@SpringBootTest
class GroupCloseIntegrityGateTest extends PostgresRlsTestBase {

  private static final String PERIOD = "2026-06";

  @Autowired private GroupReadModelService groupReadModel;
  @Autowired private GroupTrialBalanceIngestService ingestService;
  @Autowired private GroupCloseService closeService;
  @MockitoSpyBean private ConsolidationLedgerRepository ledgerRepository;

  private GroupCloseFixtures fx;

  @BeforeEach
  void setUpFixtures() {
    fx = new GroupCloseFixtures(groupReadModel, ingestService);
  }

  @Test
  void aCorruptedCtaMagnitudeMakesTheIntegrityGateThrowAndRollsTheCloseBack() {
    UUID groupId = UUID.randomUUID();
    UUID lead = UUID.randomUUID();
    UUID memberIdr = UUID.randomUUID();
    UUID memberUsd = UUID.randomUUID();

    fx.defineGroup(groupId, lead, "IDR");
    fx.addMember(groupId, memberIdr, LocalDate.of(2026, 1, 1));
    fx.addMember(groupId, memberUsd, LocalDate.of(2026, 1, 1));

    // The standard multi-currency scenario: the only translated residual is the FX spread (+30,000
    // IDR), so the genuine CTA is -30,000. Both members balance natively (the gate before this one
    // passes), so the close reaches the post-CTA integrity gate.
    fx.ingestTrialBalance(
        groupId,
        memberIdr,
        PERIOD,
        "IDR",
        List.of(asset(6_000_000L, "IDR"), revenue(10_000_000L, "IDR"), expense(4_000_000L, "IDR")));
    fx.ingestTrialBalance(
        groupId,
        memberUsd,
        PERIOD,
        "USD",
        List.of(asset(300_00L, "USD"), revenue(500_00L, "USD"), expense(200_00L, "USD")));

    // Corrupt the gate's re-read of the posted CTA: return a CTA entry whose magnitude is off by
    // 1,000 minor (-29,000 instead of the real -30,000), so signed(translated TB)=+30,000 plus the
    // wrong CTA = +1,000 != 0 -> the integrity gate must throw.
    ConsolidationLedgerEntry corruptedCta =
        new ConsolidationLedgerEntry(
            groupId,
            PERIOD,
            ConsolidationEntryType.CTA,
            "3950-CTA-TRANSLATION-RESERVE",
            Money.ofMinor(-29_000L, "IDR"),
            null,
            null,
            ConsolidationPostingRole.PRIMARY,
            1,
            UUID.randomUUID());
    doReturn(List.of(corruptedCta)).when(ledgerRepository).findCtaPrimaries(any(), any(), anyInt());

    // The gate throws IllegalStateException ("did not balance after the CTA residual").
    assertThatThrownBy(() -> closeService.close(groupId, PERIOD, 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not balance");

    // ATOMIC: the throw rolled the whole @Transactional close back — NO summary, NO partial ledger
    // (not even the real CTA that posted before the gate ran).
    assertThat(anySummaryCountAsAdmin(groupId)).isZero();
    assertThat(ledgerCountAsAdmin(groupId)).isZero();
  }

  // ----------------------------------------------------------------------- admin inspection

  private long anySummaryCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_summary WHERE group_id = '" + groupId + "'");
  }

  private long ledgerCountAsAdmin(UUID groupId) {
    return longQueryAsAdmin(
        "SELECT count(*) FROM consolidation_ledger WHERE group_id = '" + groupId + "'");
  }

  private long longQueryAsAdmin(String sql) {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
