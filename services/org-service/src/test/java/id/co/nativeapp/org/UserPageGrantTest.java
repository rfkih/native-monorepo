package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.user.dto.MyPagesResponse;
import id.co.nativeapp.org.user.service.UserPageGrantReader;
import id.co.nativeapp.org.user.service.UserPageGrantWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Page-grant replace-set + grandfather semantics + cross-tenant RLS isolation, exercised directly
 * through the writer/reader beans (RLS engages via the auto-apply aspect on the
 * {@code @Transactional} methods). Runs as the unprivileged {@code app_user} with {@code FORCE ROW
 * LEVEL SECURITY} (see {@link PostgresRlsTestBase}).
 */
@SpringBootTest
class UserPageGrantTest extends PostgresRlsTestBase {

  @Autowired private CompanyService companyService;
  @Autowired private UserPageGrantWriter writer;
  @Autowired private UserPageGrantReader reader;

  private static final String USER = "kc-sub-page-grant-user";

  @Test
  void replaceSetGrandfatherAndReplaceNarrowingBehaveCorrectly() throws Exception {
    UUID company = createCompany("PageGrantCo", "pg-owner");

    TenantContext.runAs(
        company.toString(),
        "pg-owner",
        () -> {
          // Grandfather: zero rows = ALL (full role surface).
          assertThat(MyPagesResponse.of(reader.activePageKeys(USER)).mode()).isEqualTo("ALL");

          // Restrict to two pages.
          writer.replaceGrants(USER, List.of("pos", "me"));
          assertThat(reader.activePageKeys(USER)).containsExactly("me", "pos");

          // Narrow to one page — the dropped one is deactivated, not duplicated on re-add later.
          writer.replaceGrants(USER, List.of("me"));
          assertThat(reader.activePageKeys(USER)).containsExactly("me");

          // Re-add the dropped page — reopens the existing row (no unique-constraint violation).
          writer.replaceGrants(USER, List.of("me", "pos"));
          assertThat(reader.activePageKeys(USER)).containsExactly("me", "pos");

          // Empty set clears back to the full surface (ALL).
          writer.replaceGrants(USER, List.of());
          assertThat(MyPagesResponse.of(reader.activePageKeys(USER)).mode()).isEqualTo("ALL");
        });
  }

  @Test
  void grantsAreInvisibleToADifferentTenant() throws Exception {
    UUID companyA = createCompany("PgRlsA", "a");
    UUID companyB = createCompany("PgRlsB", "b");

    TenantContext.runAs(
        companyA.toString(), "a", () -> writer.replaceGrants(USER, List.of("kitchen")));

    List<String> visibleToB =
        TenantContext.callAs(companyB.toString(), "b", () -> reader.activePageKeys(USER));
    assertThat(visibleToB).as("RLS must hide company A's grants from company B").isEmpty();

    List<String> visibleToA =
        TenantContext.callAs(companyA.toString(), "a", () -> reader.activePageKeys(USER));
    assertThat(visibleToA).containsExactly("kitchen");
  }

  private UUID createCompany(String name, String actor) {
    return companyService
        .createCompany(new CreateCompanyCommand(name, "IDR", "id", "restaurant", actor))
        .company()
        .getId();
  }
}
