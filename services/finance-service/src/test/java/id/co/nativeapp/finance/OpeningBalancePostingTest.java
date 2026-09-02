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
import id.co.nativeapp.finance.gl.service.GeneralLedgerWriter;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import id.co.nativeapp.finance.opening.domain.OpeningBalanceSide;
import id.co.nativeapp.finance.opening.dto.OpeningBalanceLine;
import id.co.nativeapp.finance.opening.repository.CompanyOpeningBalanceRepository;
import id.co.nativeapp.finance.opening.service.OpeningBalanceWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit pins for {@link OpeningBalanceWriter#buildOpeningEntry} (ADR 0037) — the pure, mocked-
 * resolver shape ({@code ReconcilePostingTest}). Unlike the role-resolved writers, every
 * user-supplied line targets its OWN account code directly (no role resolution); only the auto-plug
 * OPENING_BALANCE_EQUITY leg is role-resolved, so {@code uses_illustrative_rules} is derived from
 * THAT role alone — and stays {@code false} (no role was resolved at all) when the sheet already
 * balances and no plug is posted.
 */
class OpeningBalancePostingTest {

  private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");
  private static final String PERIOD = "2026-08";

  private OpeningBalanceWriter writer(RoleAccountResolver resolver) {
    return new OpeningBalanceWriter(
        mock(CompanyOpeningBalanceRepository.class),
        new GeneralLedgerWriter(
            mock(JournalEntryRepository.class), mock(JournalLineRepository.class)),
        resolver,
        mock(JdbcTemplate.class));
  }

  @Test
  void aPlugToOfficialObeIsNotBadgedProvisional() {
    RoleAccountResolver resolver = mock(RoleAccountResolver.class);
    when(resolver.resolve(eq(AccountRole.OPENING_BALANCE_EQUITY), any())).thenReturn("3900");

    List<OpeningBalanceLine> lines =
        List.of(new OpeningBalanceLine("1900", 5_000_000L, OpeningBalanceSide.DEBIT));
    JournalEntry entry =
        writer(resolver)
            .buildOpeningEntry(lines, "IDR", 5_000_000L, PERIOD, NOW, UUID.randomUUID());

    List<JournalLine> journalLines = entry.getLines();
    assertThat(journalLines).hasSize(2);
    assertThat(journalLines.get(1).getAccountCode()).isEqualTo("3900"); // the auto-plug
    // Provenance-derived (was hardcoded true): OPENING_BALANCE_EQUITY resolves OFFICIAL.
    assertThat(entry.isUsesIllustrativeRules()).isFalse();
  }

  @Test
  void anIllustrativeObeMappingBadgesThePlugEntryProvisional() {
    RoleAccountResolver resolver = mock(RoleAccountResolver.class);
    when(resolver.resolve(eq(AccountRole.OPENING_BALANCE_EQUITY), any())).thenReturn("3900");
    when(resolver.anyIllustrative(any(), any())).thenReturn(true);

    List<OpeningBalanceLine> lines =
        List.of(new OpeningBalanceLine("1900", 5_000_000L, OpeningBalanceSide.DEBIT));
    JournalEntry entry =
        writer(resolver)
            .buildOpeningEntry(lines, "IDR", 5_000_000L, PERIOD, NOW, UUID.randomUUID());

    assertThat(entry.isUsesIllustrativeRules())
        .as("an illustrative OPENING_BALANCE_EQUITY mapping flags the plugged entry provisional")
        .isTrue();
  }

  @Test
  void aBalancedSheetWithNoPlugResolvesNoRoleAndIsNeverBadgedProvisional() {
    // plug == 0 → the OBE leg is never added, so NO role is resolved for this entry at all; the
    // resolver must not even be consulted (it stays a bare, unstubbed mock — any interaction would
    // be an unexpected call on a strict-return-null mock and fail the balance assertion downstream
    // if a code path accidentally required a mapping here).
    RoleAccountResolver resolver = mock(RoleAccountResolver.class);

    List<OpeningBalanceLine> lines =
        List.of(
            new OpeningBalanceLine("1900", 5_000_000L, OpeningBalanceSide.DEBIT),
            new OpeningBalanceLine("3000", 5_000_000L, OpeningBalanceSide.CREDIT));
    JournalEntry entry =
        writer(resolver).buildOpeningEntry(lines, "IDR", 0L, PERIOD, NOW, UUID.randomUUID());

    assertThat(entry.getLines()).hasSize(2);
    assertThat(entry.isUsesIllustrativeRules()).isFalse();
  }
}
