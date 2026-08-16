package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.register.messaging.RegisterSessionClosedEvent;
import id.co.nativeapp.finance.register.service.RegisterCloseService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof, against real Postgres, of the ADR 0064 close-correction REVERSE + RE-POST
 * in {@link id.co.nativeapp.finance.register.service.RegisterCloseWriter} — the money-critical
 * orchestration the unit tests (pure builders) cannot cover (flaw-audit W2):
 *
 * <ul>
 *   <li>a superseding correction posts a balanced CONTRA of the prior variance journal (role
 *       REVERSAL) plus the corrected variance, and the three entries NET to the corrected figure;
 *   <li>a redelivered correction is a complete no-op (processOnce + deterministic contra id);
 *   <li>a correction arriving BEFORE its superseded original fails closed (retryable — the claim
 *       rolls back with the throw), and posting original-then-correction afterwards lands the books
 *       exactly once;
 *   <li>a correction back to zero variance reverses the prior entry and posts nothing new.
 * </ul>
 */
@SpringBootTest
class RegisterCloseCorrectionPostingTest extends PostgresRlsTestBase {

  private static final String TENANT = "cccccccc-cccc-cccc-cccc-ccccccccc064";
  private static final UUID BUSINESS = UUID.fromString("dddddddd-dddd-dddd-dddd-ddddddddd064");
  // A period no test in this class ever files — the sealed-period quarantine never fires here
  // (that branch is proven by SealedPeriodQuarantineTest's pattern).
  private static final Instant OPENED = Instant.parse("2026-03-15T01:00:00Z");
  private static final Instant CLOSED = Instant.parse("2026-03-15T13:00:00Z");

  @Autowired private RegisterCloseService service;

  /**
   * A consistent close/correction event: {@code expected = float + sales − refunds}; {@code
   * overShort = counted − expected}. Cash-only (no tender lines) — the tender legs are pinned by
   * the unit builder test.
   */
  private static RegisterSessionClosedEvent event(
      UUID eventId,
      UUID sessionId,
      long cashSales,
      long counted,
      UUID supersedes,
      int closeSeq,
      String reason) {
    long expected = cashSales; // float 0, refunds 0
    return new RegisterSessionClosedEvent(
        eventId,
        sessionId,
        TENANT,
        BUSINESS,
        OPENED,
        CLOSED,
        0L,
        cashSales,
        0L,
        expected,
        counted,
        counted - expected,
        "IDR",
        List.of(),
        supersedes,
        closeSeq,
        reason);
  }

  @Test
  void correctionReversesThePriorVarianceAndPostsTheCorrectedOneAndNetsExactly() throws Exception {
    UUID sessionId = UUID.randomUUID();
    UUID original = UUID.randomUUID();
    UUID correction = UUID.randomUUID();

    // Original close: 100k cash sales, counted 50k → SHORT 50k → Dr 5700 50k / Cr 1900 50k.
    assertThat(service.handle(event(original, sessionId, 100_000L, 50_000L, null, 1, null)))
        .isTrue();
    List<EntryRow> afterOriginal = entriesFor(original, correction);
    assertThat(afterOriginal).hasSize(1);

    // Correction: the drawer really held 80k → SHORT 20k, superseding the original.
    assertThat(
            service.handle(
                event(correction, sessionId, 100_000L, 80_000L, original, 2, "miscount")))
        .isTrue();

    List<EntryRow> all = entriesFor(original, correction);
    // Three entries: the original variance, its REVERSAL contra, the corrected variance.
    assertThat(all).hasSize(3);
    EntryRow contra =
        all.stream().filter(e -> "REVERSAL".equals(e.postingRole)).findFirst().orElseThrow();
    // The contra negates the original exactly: Cr 5700 50k / Dr 1900 50k.
    assertThat(contra.lines)
        .containsExactlyInAnyOrder(
            new LineRow("5700", 0L, 50_000L), new LineRow("1900", 50_000L, 0L));
    EntryRow corrected =
        all.stream().filter(e -> correction.equals(e.sourceEventId)).findFirst().orElseThrow();
    assertThat(corrected.postingRole).isEqualTo("PRIMARY");
    assertThat(corrected.lines)
        .containsExactlyInAnyOrder(
            new LineRow("5700", 20_000L, 0L), new LineRow("1900", 0L, 20_000L));

    // NET across all three: exactly the corrected 20k short — Dr 5700 nets 20k, Cr 1900 nets 20k.
    assertThat(netDebit(all, "5700")).isEqualTo(20_000L);
    assertThat(netDebit(all, "1900")).isEqualTo(-20_000L);
  }

  @Test
  void redeliveredCorrectionIsACompleteNoOp() throws Exception {
    UUID sessionId = UUID.randomUUID();
    UUID original = UUID.randomUUID();
    UUID correction = UUID.randomUUID();
    service.handle(event(original, sessionId, 100_000L, 50_000L, null, 1, null));
    service.handle(event(correction, sessionId, 100_000L, 80_000L, original, 2, "miscount"));
    assertThat(entriesFor(original, correction)).hasSize(3);

    // Redelivery: skipped by processOnce; no fourth entry, no second contra.
    assertThat(
            service.handle(
                event(correction, sessionId, 100_000L, 80_000L, original, 2, "miscount")))
        .isFalse();
    assertThat(entriesFor(original, correction)).hasSize(3);
  }

  @Test
  void correctionBeforeItsOriginalFailsClosedThenLandsExactlyOnceAfterTheOriginal()
      throws Exception {
    UUID sessionId = UUID.randomUUID();
    UUID original = UUID.randomUUID();
    UUID correction = UUID.randomUUID();
    RegisterSessionClosedEvent correctionEvent =
        event(correction, sessionId, 100_000L, 80_000L, original, 2, "early");

    // The correction arrives first (a DLT'd/late original): fail closed — never "reconciled to
    // zero", never a corrected variance racing ahead of the entry it reverses.
    assertThatThrownBy(() -> service.handle(correctionEvent))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("has not been processed yet");
    assertThat(entriesFor(original, correction)).isEmpty();

    // The throw rolled the processOnce claim back — the retry (after the original posts) works.
    assertThat(service.handle(event(original, sessionId, 100_000L, 50_000L, null, 1, null)))
        .isTrue();
    assertThat(service.handle(correctionEvent)).isTrue();
    List<EntryRow> all = entriesFor(original, correction);
    assertThat(all).hasSize(3);
    assertThat(netDebit(all, "5700")).isEqualTo(20_000L);
  }

  @Test
  void correctionBackToZeroReversesThePriorEntryAndPostsNothingNew() throws Exception {
    UUID sessionId = UUID.randomUUID();
    UUID original = UUID.randomUUID();
    UUID correction = UUID.randomUUID();
    service.handle(event(original, sessionId, 100_000L, 50_000L, null, 1, null));

    // The drawer actually reconciled (counted == expected): the correction reverses the wrong
    // short and posts NO new variance — net zero on both accounts.
    assertThat(
            service.handle(
                event(correction, sessionId, 100_000L, 100_000L, original, 2, "reconciled")))
        .isTrue();
    List<EntryRow> all = entriesFor(original, correction);
    assertThat(all).hasSize(2); // original + contra only
    assertThat(all.stream().filter(e -> correction.equals(e.sourceEventId))).isEmpty();
    assertThat(netDebit(all, "5700")).isZero();
    assertThat(netDebit(all, "1900")).isZero();
  }

  // ------------------------------------------------------------------ admin helpers

  private record LineRow(String accountCode, long debitMinor, long creditMinor) {}

  private record EntryRow(UUID id, UUID sourceEventId, String postingRole, List<LineRow> lines) {}

  /**
   * Every journal entry (with lines) this scenario produced: the two named source events plus any
   * entry whose source id is neither (the deterministic contra). Admin JDBC bypasses RLS.
   */
  private List<EntryRow> entriesFor(UUID original, UUID correction) throws Exception {
    List<EntryRow> entries = new ArrayList<>();
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT id, source_event_id, posting_role FROM journal_entry"
                    + " WHERE description LIKE 'Register close%' ORDER BY created_at, id")) {
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          UUID id = (UUID) rs.getObject("id");
          entries.add(
              new EntryRow(
                  id,
                  (UUID) rs.getObject("source_event_id"),
                  rs.getString("posting_role"),
                  linesOf(admin, id)));
        }
      }
    }
    // Keep only entries belonging to THIS scenario: the two source events + the contra derived
    // from the original's entry (its source id is neither original nor correction).
    return entries.stream()
        .filter(
            e ->
                original.equals(e.sourceEventId)
                    || correction.equals(e.sourceEventId)
                    || "REVERSAL".equals(e.postingRole))
        .toList();
  }

  private static List<LineRow> linesOf(Connection admin, UUID entryId) throws Exception {
    List<LineRow> lines = new ArrayList<>();
    try (PreparedStatement ps =
        admin.prepareStatement(
            "SELECT account_code, debit_minor, credit_minor FROM journal_line"
                + " WHERE entry_id = ? ORDER BY line_no")) {
      ps.setObject(1, entryId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          lines.add(
              new LineRow(
                  rs.getString("account_code").strip(),
                  rs.getLong("debit_minor"),
                  rs.getLong("credit_minor")));
        }
      }
    }
    return lines;
  }

  /** Σ debits − Σ credits for one account across the scenario's entries. */
  private static long netDebit(List<EntryRow> entries, String accountCode) {
    return entries.stream()
        .flatMap(e -> e.lines.stream())
        .filter(l -> l.accountCode.equals(accountCode))
        .mapToLong(l -> l.debitMinor - l.creditMinor)
        .sum();
  }
}
