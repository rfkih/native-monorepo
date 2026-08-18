package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.repository.JournalEntryRepository;
import id.co.nativeapp.finance.gl.repository.JournalLineRepository;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.platform.repository.PlatformSettlementRepository;
import id.co.nativeapp.finance.platform.service.PlatformSettlementWriter;
import id.co.nativeapp.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit pins for {@link PlatformSettlementWriter#buildSettlementEntry} (ADR 0036 Phase C) — the
 * pure, mocked-resolver shape ({@code ReconcilePostingTest}). Covers the exact Dr CASH_CLEARING
 * (net) + Dr PLATFORM_FEE_EXPENSE (fee) / Cr PLATFORM_RECEIVABLE (gross) legs, the fee-free 2-leg
 * omission, and the {@code uses_illustrative_rules} provenance derivation.
 */
class PlatformSettlementPostingTest {

  private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
  private static final String PERIOD = "2026-08";
  private static final String CHANNEL = "GOFOOD";

  private RoleAccountResolver resolver;
  private PlatformSettlementWriter writer;

  @BeforeEach
  void setUp() {
    resolver = mock(RoleAccountResolver.class);
    when(resolver.resolve(eq(AccountRole.CASH_CLEARING), any())).thenReturn("1900");
    when(resolver.resolve(eq(AccountRole.PLATFORM_FEE_EXPENSE), any())).thenReturn("5710");
    when(resolver.resolve(eq(AccountRole.PLATFORM_RECEIVABLE), any())).thenReturn("1250");

    writer =
        new PlatformSettlementWriter(
            mock(PlatformSettlementRepository.class),
            mock(JournalEntryRepository.class),
            mock(JournalLineRepository.class),
            resolver,
            mock(JdbcTemplate.class),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void feeBearingSettlementDebitsCashAndFeeCreditsGrossToReceivable() {
    JournalEntry entry =
        writer.buildSettlementEntry(
            CHANNEL,
            Money.ofMinor(300_000L, "IDR"),
            Money.ofMinor(240_000L, "IDR"),
            PERIOD,
            NOW,
            UUID.randomUUID());

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(3);
    assertThat(lineFor(lines, "1900").getDebitMinor()).isEqualTo(240_000L); // Dr CASH_CLEARING net
    assertThat(lineFor(lines, "5710").getDebitMinor())
        .isEqualTo(60_000L); // Dr PLATFORM_FEE_EXPENSE
    assertThat(lineFor(lines, "1250").getCreditMinor())
        .isEqualTo(300_000L); // Cr PLATFORM_RECEIVABLE gross
    // Provenance-derived (was hardcoded true): every resolved role above is OFFICIAL, so the entry
    // is not badged provisional.
    assertThat(entry.isUsesIllustrativeRules()).isFalse();
  }

  @Test
  void feeFreeSettlementOmitsTheFeeLeg() {
    JournalEntry entry =
        writer.buildSettlementEntry(
            CHANNEL,
            Money.ofMinor(100_000L, "IDR"),
            Money.ofMinor(100_000L, "IDR"),
            PERIOD,
            NOW,
            UUID.randomUUID());

    List<JournalLine> lines = entry.getLines();
    assertThat(lines).hasSize(2);
    assertThat(lineFor(lines, "1900").getDebitMinor()).isEqualTo(100_000L);
    assertThat(lineFor(lines, "1250").getCreditMinor()).isEqualTo(100_000L);
    assertThat(entry.isUsesIllustrativeRules())
        .isFalse(); // provenance-derived (was hardcoded true)
  }

  @Test
  void anIllustrativeMappingBadgesTheSettlementEntryProvisional() {
    when(resolver.anyIllustrative(any(), any(), any(), any())).thenReturn(true);

    JournalEntry entry =
        writer.buildSettlementEntry(
            CHANNEL,
            Money.ofMinor(300_000L, "IDR"),
            Money.ofMinor(240_000L, "IDR"),
            PERIOD,
            NOW,
            UUID.randomUUID());

    assertThat(entry.isUsesIllustrativeRules())
        .as("an illustrative mapping among the roles posted flags the entry provisional")
        .isTrue();
  }

  private static JournalLine lineFor(List<JournalLine> lines, String accountCode) {
    return lines.stream()
        .filter(l -> l.getAccountCode().equals(accountCode))
        .findFirst()
        .orElseThrow();
  }
}
