package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Regression test for the V12 {@code member_group_index} BACKFILL (#46 — the P3d deferred
 * operational item). It exercises the exact mechanism the migration uses, under the real Flyway
 * schema + RLS, as the unprivileged {@code app_user} that OWNS the tables:
 *
 * <ul>
 *   <li>a membership exists in the LEAD-scoped {@code group_member} read model but NOT in the
 *       member→group reverse index — the pre-V10 gap (its {@code GroupMembershipChanged} was
 *       already deduped and will never re-fire, so the consumer can never populate the index for
 *       it);
 *   <li>THE TRAP: as the table owner under {@code FORCE ROW LEVEL SECURITY} with no tenant GUC, the
 *       policy filters EVERY {@code group_member} row, so a plain backfill {@code SELECT} would
 *       copy nothing (a silent empty backfill);
 *   <li>the V12 fix — {@code NO FORCE} → backfill {@code INSERT ... SELECT ... ON CONFLICT DO
 *       NOTHING} → restore {@code FORCE} — copies the membership window into the reverse index, is
 *       idempotent on a re-run, and leaves {@code FORCE} re-enabled.
 * </ul>
 *
 * <p>The migration itself also runs (as the owner) on every context boot against the empty schema,
 * so V12's syntactic + owner-privilege validity is covered there; this test covers the ROW-COPY
 * behaviour the empty-schema run cannot.
 */
@SpringBootTest
class MemberGroupIndexBackfillTest extends PostgresRlsTestBase {

  // The exact backfill projection V12 runs (kept in sync with the migration).
  private static final String BACKFILL_SQL =
      "INSERT INTO member_group_index (member_company_id, group_id, effective_from, effective_to) "
          + "SELECT member_company_id, group_id, effective_from, effective_to FROM group_member "
          + "ON CONFLICT (member_company_id, group_id) DO NOTHING";

  @Test
  void backfillCopiesPreExistingGroupMemberRowsAcrossForceRlsThenRestoresForce() throws Exception {
    UUID lead = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    LocalDate from = LocalDate.of(2026, 1, 1);
    LocalDate openEnd = LocalDate.of(9999, 12, 31);

    // A membership present in group_member (lead-scoped) but with NO reverse-index row — exactly
    // the
    // pre-V10 gap V12 heals. Seeded over the admin (BYPASSRLS) connection so no GUC is needed to
    // write the RLS row.
    seedGroupMemberAsAdmin(lead, member, group, from, openEnd);
    assertThat(indexRowCountAsAdmin(member, group)).isZero();

    try (Connection owner = ownerConnection()) {
      // THE TRAP: owner under FORCE RLS with no tenant GUC sees ZERO group_member rows — a plain
      // backfill would copy nothing.
      assertThat(groupMemberCount(owner)).isZero();

      try (Statement st = owner.createStatement()) {
        st.execute("ALTER TABLE group_member NO FORCE ROW LEVEL SECURITY");
        try {
          assertThat(groupMemberCount(owner)).isEqualTo(1L); // the trap is lifted
          st.execute(BACKFILL_SQL);
          st.execute(BACKFILL_SQL); // idempotent: ON CONFLICT DO NOTHING copies nothing new
        } finally {
          st.execute("ALTER TABLE group_member FORCE ROW LEVEL SECURITY");
        }
      }

      // FORCE is restored: the owner is filtered again (no GUC bound).
      assertThat(groupMemberCount(owner)).isZero();
    }

    // The reverse-index row now exists exactly once, with the membership window mirrored
    // faithfully.
    assertThat(indexRowCountAsAdmin(member, group)).isEqualTo(1L);
    assertIndexWindowAsAdmin(member, group, from, openEnd);
  }

  // ----------------------------------------------------------------------- helpers

  private static Connection ownerConnection() throws SQLException {
    // app_user OWNS the Flyway-migrated tables (it is the migration role), so it may ALTER ...
    // FORCE.
    return java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
  }

  private static Connection adminConnection() throws SQLException {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static long groupMemberCount(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM group_member")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void seedGroupMemberAsAdmin(
      UUID lead, UUID member, UUID group, LocalDate from, LocalDate to) throws SQLException {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO group_member (id, group_id, member_company_id, lead_company_id,"
                    + " effective_from, effective_to, created_at, created_by, updated_at,"
                    + " updated_by, version, company_id) VALUES (?, ?, ?, ?, ?, ?, now(), 'seed',"
                    + " now(), 'seed', 0, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, group);
      ps.setObject(3, member);
      ps.setObject(4, lead);
      ps.setDate(5, Date.valueOf(from));
      ps.setDate(6, Date.valueOf(to));
      ps.setString(7, lead.toString()); // company_id = the lead (ck_group_member_lead_is_tenant)
      ps.executeUpdate();
    }
  }

  private long indexRowCountAsAdmin(UUID member, UUID group) throws SQLException {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM member_group_index WHERE member_company_id = ? AND group_id"
                    + " = ?")) {
      ps.setObject(1, member);
      ps.setObject(2, group);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private void assertIndexWindowAsAdmin(UUID member, UUID group, LocalDate from, LocalDate to)
      throws SQLException {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT effective_from, effective_to FROM member_group_index WHERE"
                    + " member_company_id = ? AND group_id = ?")) {
      ps.setObject(1, member);
      ps.setObject(2, group);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getDate("effective_from").toLocalDate()).isEqualTo(from);
        assertThat(rs.getDate("effective_to").toLocalDate()).isEqualTo(to);
      }
    }
  }
}
