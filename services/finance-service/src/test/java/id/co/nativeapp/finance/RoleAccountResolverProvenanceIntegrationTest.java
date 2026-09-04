package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.gl.domain.AccountRole;
import id.co.nativeapp.finance.gl.service.RoleAccountResolver;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testcontainers proof for Fix 3's provenance read (code-review W3). {@link RoleAccountResolver}
 * reads {@code uses_illustrative} from the SAME winning row it reads the account code from, against
 * the REAL Flyway-seeded {@code role_account_map} (V13 illustrative + V51 OFFICIAL supersession).
 * The role-based writer unit tests only exercise this through a MOCKED resolver, so this is the
 * regression guard for the actual SQL — a wrong column name or a wrong {@code ORDER BY} would slip
 * past every mock-based test but fail here.
 */
@SpringBootTest
class RoleAccountResolverProvenanceIntegrationTest extends PostgresRlsTestBase {

  private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

  @Autowired private RoleAccountResolver resolver;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void officialSeededMappingResolvesNotIllustrative() {
    RoleAccountResolver.ResolvedAccount resolved =
        resolver.resolveWithProvenance(AccountRole.INVENTORY_SHRINKAGE, NOW);

    assertThat(resolved.accountCode())
        .as("V51 seeds an OFFICIAL INVENTORY_SHRINKAGE mapping")
        .isNotNull();
    assertThat(resolved.illustrative())
        .as(
            "the highest-version (OFFICIAL, V51) mapping resolves — a new posting is not provisional")
        .isFalse();
    assertThat(
            resolver.anyIllustrative(NOW, AccountRole.INVENTORY, AccountRole.INVENTORY_SHRINKAGE))
        .as("every stocktake role resolves OFFICIAL, so the entry is not badged illustrative")
        .isFalse();
  }

  @Test
  @Transactional // the inserted higher-version row is rolled back at the end of the test
  void aNewerIllustrativeVersionWinsAndFlagsThePosting() {
    // A newer illustrative version supersedes the OFFICIAL one at resolution time (ORDER BY version
    // DESC), so resolveWithProvenance must report illustrative=true — proving it reads the flag off
    // the SAME winning row, not a fixed assumption. 5800 already exists in chart_of_account (the
    // FK).
    jdbcTemplate.update(
        "INSERT INTO role_account_map"
            + " (id, account_role, gl_account_code, version, uses_illustrative, effective_from,"
            + " effective_to)"
            + " VALUES (?, 'INVENTORY_SHRINKAGE', '5800', 999, TRUE, DATE '2000-01-01',"
            + " DATE '9999-12-31')",
        UUID.randomUUID());

    RoleAccountResolver.ResolvedAccount resolved =
        resolver.resolveWithProvenance(AccountRole.INVENTORY_SHRINKAGE, NOW);

    assertThat(resolved.accountCode()).isEqualTo("5800");
    assertThat(resolved.illustrative())
        .as("the newer illustrative version wins and flags the posting provisional")
        .isTrue();
    assertThat(resolver.anyIllustrative(NOW, AccountRole.INVENTORY_SHRINKAGE)).isTrue();
  }
}
