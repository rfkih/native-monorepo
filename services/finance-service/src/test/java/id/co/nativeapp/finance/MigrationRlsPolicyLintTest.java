package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * MIGRATION LINT (#39, SEAM-2 conjunction-policy invariant) — a plain text-parsing JUnit test (NO
 * Spring, NO Testcontainers) over the finance {@code src/main/resources/db/migration/*.sql} files.
 *
 * <p><strong>Invariant.</strong> A two-GUC GROUP table is RLS-scoped by the CONJUNCTION of the
 * group dimension AND the tenant dimension: every {@code CREATE POLICY} that references {@code
 * current_setting('app.current_group'...)} MUST also conjoin {@code company_id =
 * current_setting('app.current_tenant'...)} in the SAME policy (both the {@code USING} and the
 * {@code WITH CHECK} clauses). A group table can therefore NEVER regress to a {@code group_id}-only
 * policy: such a row would be visible to any tenant that merely binds the group, defeating the
 * member-dimension-inert isolation proven by {@code GroupTrialBalanceConjunctionIsolationTest}.
 *
 * <p>This codifies that invariant statically so a future group table added with a group_id-only
 * policy FAILS THE BUILD here — long before it could ever ship — rather than being caught only in a
 * (slow, Testcontainers) RLS proof or a manual review.
 *
 * <p><strong>How it parses.</strong> It splits each migration into {@code CREATE POLICY ... ;}
 * statements (a policy body cannot itself contain a {@code ;}, since the clauses are boolean
 * expressions), and for every statement that mentions {@code app.current_group} it asserts the SAME
 * statement also mentions {@code app.current_tenant}. The failure message names the offending
 * migration file and policy.
 */
class MigrationRlsPolicyLintTest {

  /**
   * A {@code CREATE POLICY <name> ON <table> ... ;} statement, captured up to its terminating ;.
   */
  private static final Pattern CREATE_POLICY =
      Pattern.compile(
          "CREATE\\s+POLICY\\s+(\\S+)\\s+ON\\s+(\\S+)[^;]*;",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  /** Marks a policy that scopes on the group dimension (the two-GUC group tables). */
  private static final Pattern GROUP_GUC =
      Pattern.compile("current_setting\\(\\s*'app\\.current_group'", Pattern.CASE_INSENSITIVE);

  /** The tenant-dimension conjunct that a group policy MUST also carry. */
  private static final Pattern TENANT_GUC =
      Pattern.compile("current_setting\\(\\s*'app\\.current_tenant'", Pattern.CASE_INSENSITIVE);

  @Test
  void everyGroupGucPolicyAlsoConjoinsTheTenantGuc() {
    List<Path> migrations = migrationFiles();
    // Guard against a broken path / empty glob silently passing the invariant.
    assertThat(migrations).as("finance migration *.sql files must be discoverable").isNotEmpty();

    int groupPoliciesChecked = 0;
    List<String> violations = new ArrayList<>();

    for (Path migration : migrations) {
      String sql = read(migration);
      Matcher policy = CREATE_POLICY.matcher(sql);
      while (policy.find()) {
        String statement = policy.group(); // the whole CREATE POLICY ... ; text
        if (!GROUP_GUC.matcher(statement).find()) {
          continue; // not a group-scoped policy — out of scope for this invariant
        }
        groupPoliciesChecked++;
        if (!TENANT_GUC.matcher(statement).find()) {
          violations.add(
              String.format(
                  "%s: policy '%s' on '%s' references app.current_group but NOT"
                      + " company_id = current_setting('app.current_tenant'...) — a two-GUC group"
                      + " table must conjoin BOTH dimensions (SEAM-2), never be group_id-only.",
                  migration.getFileName(), policy.group(1), policy.group(2)));
        }
      }
    }

    if (!violations.isEmpty()) {
      fail(
          "Group-scoped RLS policy missing the app.current_tenant conjunct (#39):\n  "
              + String.join("\n  ", violations));
    }
    // The current migrations DO define such policies (V6/V7) — assert we actually exercised the
    // rule, so a future refactor that hides the policies from the parser cannot vacuously pass.
    assertThat(groupPoliciesChecked)
        .as("expected at least one app.current_group policy to be linted")
        .isPositive();
  }

  /**
   * Locates the finance {@code src/main/resources/db/migration} directory and lists its {@code
   * *.sql} files. Resolves it from the classpath (the migrations are copied to the test runtime
   * classpath under {@code db/migration}) so the test is independent of the JUnit working
   * directory.
   */
  private static List<Path> migrationFiles() {
    Path dir;
    var url = MigrationRlsPolicyLintTest.class.getClassLoader().getResource("db/migration");
    if (url != null) {
      try {
        dir = Paths.get(url.toURI());
      } catch (URISyntaxException e) {
        throw new IllegalStateException("Cannot resolve db/migration from the classpath", e);
      }
    } else {
      // Fallback to the source tree relative to the module working directory.
      dir = Paths.get("src", "main", "resources", "db", "migration");
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot list finance migrations under " + dir, e);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read migration " + path, e);
    }
  }
}
