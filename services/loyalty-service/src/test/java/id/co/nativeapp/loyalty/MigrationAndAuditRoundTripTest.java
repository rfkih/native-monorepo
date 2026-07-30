package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.loyalty.member.dto.EnrollMemberRequest;
import id.co.nativeapp.loyalty.member.dto.MemberResponse;
import id.co.nativeapp.loyalty.member.service.MemberService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Mandatory migration + audit round-trip proof (ENGINEERING-STANDARDS §3.2). The full context boots
 * with {@code ddl-auto=validate}, so Flyway applies the baseline and Hibernate validates every
 * mapping against the migrated schema (catching drift, e.g. {@code CHAR(3)} currency vs {@code
 * VARCHAR}, or the {@code BYTEA} PII columns vs the entity's converter target type).
 *
 * <p>An enrolled member round-trips with its {@code company_id} and {@code created_by} populated
 * from the {@link TenantContext} scope (rule 4), read over the admin connection (the table is
 * FORCE RLS) — and its PII columns are proven to be ciphertext, not plaintext (rule 6).
 */
@SpringBootTest
class MigrationAndAuditRoundTripTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "owner-a@example.co.id";

  @Autowired private MemberService memberService;

  @Test
  void anEnrolledMemberRoundTripsWithCompanyIdAndCreatedByFromTheScope() throws Exception {
    MemberResponse enrolled =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () -> memberService.enroll(new EnrollMemberRequest("0812-3456-7890", "Budi Santoso")));

    Map<String, Object> row = memberRowAsAdmin(enrolled.id());
    assertThat(row.get("company_id")).isEqualTo(TENANT_A);
    assertThat(row.get("created_by")).isEqualTo(ACTOR_A);
    assertThat(row.get("updated_by")).isEqualTo(ACTOR_A);
    assertThat(row.get("points_balance")).isEqualTo(0L);
    assertThat(row.get("balance_seq")).isEqualTo(0L);

    // PII at rest is ciphertext, never plaintext (rule 6) — the raw column bytes never contain the
    // normalized phone digits.
    byte[] phoneEncrypted = (byte[]) row.get("phone_encrypted");
    assertThat(new String(phoneEncrypted, java.nio.charset.StandardCharsets.UTF_8))
        .doesNotContain("081234567890");

    // The read path decrypts transparently via the JPA converter.
    MemberResponse reloaded =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> memberService.getById(enrolled.id()));
    assertThat(reloaded.displayName()).isEqualTo("Budi Santoso");
    assertThat(reloaded.phoneTail()).isEqualTo("****7890");
  }

  private Map<String, Object> memberRowAsAdmin(UUID memberId) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT company_id, created_by, updated_by, points_balance, balance_seq,"
                    + " phone_encrypted FROM loyalty_member WHERE id = ?")) {
      ps.setObject(1, memberId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return Map.of(
            "company_id", rs.getString("company_id"),
            "created_by", rs.getString("created_by"),
            "updated_by", rs.getString("updated_by"),
            "points_balance", rs.getLong("points_balance"),
            "balance_seq", rs.getLong("balance_seq"),
            "phone_encrypted", rs.getBytes("phone_encrypted"));
      }
    }
  }
}
