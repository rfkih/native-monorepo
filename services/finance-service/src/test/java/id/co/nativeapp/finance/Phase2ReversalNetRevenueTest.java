package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.reversal.messaging.SaleRefundedEvent;
import id.co.nativeapp.finance.reversal.messaging.SaleVoidedEvent;
import id.co.nativeapp.finance.reversal.service.PartialRefundNotSupportedException;
import id.co.nativeapp.finance.reversal.service.ReversalPostingService;
import id.co.nativeapp.money.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for B1 and W1 (Phase 2 reversal correctness).
 *
 * <p>B1 — void must unwind the read model by the original NET revenue (subtotal − discount), NOT
 * the grand total. A sale with tax and service charge: grand total != net; a void must net the read
 * model to 0 and produce a balanced per-leg GL contra entry.
 *
 * <p>W1 — full refund must reverse the original GL per-leg (not the 2-line GROSS template) and
 * unwind the read model by the original NET revenue. Partial refund must be rejected.
 *
 * <p>Extends {@link PostgresRlsTestBase}: Testcontainers PostgreSQL 16, singleton container,
 * unprivileged {@code app_user}, FORCE RLS. Every test starts from empty tables ({@link
 * #resetTables}).
 */
@SpringBootTest
class Phase2ReversalNetRevenueTest extends PostgresRlsTestBase {

  // Tenant distinct from SaleVoidedReversalTest / SaleRefundedReversalTest to avoid interference
  // when the shared Spring context reuses the same container.
  private static final String TENANT = "55555555-5555-5555-5555-aaaaaaaaaaaa";
  private static final UUID BUSINESS = UUID.fromString("66666666-6666-6666-6666-aaaaaaaaaaaa");
  private static final Instant OCCURRED = Instant.parse("2026-06-14T12:00:00Z");

  // Phase 2 sale: subtotal=30_000 discount=0 SC=1_500(5%) tax=3_150(10%+SC) grandTotal=34_650
  // netRevenue = subtotal − discount = 30_000 − 0 = 30_000
  private static final long SUBTOTAL_MINOR = 30_000L;
  private static final long DISCOUNT_MINOR = 0L;
  private static final long SERVICE_CHARGE_MINOR = 1_500L;
  private static final long TAX_MINOR = 3_150L;
  private static final long GRAND_TOTAL_MINOR = 34_650L; // 30_000 + 1_500 + 3_150
  private static final long NET_REVENUE_MINOR = 30_000L; // subtotal − discount

  // Phase 2 sale WITH DISCOUNT: subtotal=30_000 discount=5_000 SC=1_250(5%) tax=2_625(10%+SC)
  // grandTotal = 25_000 + 1_250 + 2_625 = 28_875; netRevenue = 30_000 − 5_000 = 25_000
  private static final long DISCOUNT_NONZERO_SUBTOTAL = 30_000L;
  private static final long DISCOUNT_NONZERO_DISCOUNT = 5_000L;
  private static final long DISCOUNT_NONZERO_SC = 1_250L;
  private static final long DISCOUNT_NONZERO_TAX = 2_625L;
  private static final long DISCOUNT_NONZERO_GRAND = 28_875L;
  private static final long DISCOUNT_NONZERO_NET = 25_000L; // 30_000 − 5_000

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private ReversalPostingService reversalPostingService;

  // -----------------------------------------------------------------------
  // B1: void unwinds read model by NET, not grand total
  // -----------------------------------------------------------------------

  /**
   * B1 core: sale-then-void nets consolidated_revenue to ZERO. Uses a Phase 2 sale where grand
   * total (34,650) != net revenue (30,000). Before fix: void would decrement by 34,650 leaving
   * −4,650. After fix: void decrements by 30,000 → 0.
   */
  @Test
  void voidOfPhase2SaleNetsConsolidatedRevenueToZero() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();

    // Post a Phase 2 SaleRecorded — accumulates NET revenue (30,000).
    revenuePostingService.handle(phase2SaleEvent(saleEventId, saleId));
    long revenueAfterSale = consolidatedRevenueMinor();
    assertThat(revenueAfterSale)
        .as("sale must accumulate NET revenue (subtotal − discount)")
        .isEqualTo(NET_REVENUE_MINOR);

    // Post the void — must decrement by NET (30,000), not by grand total (34,650).
    UUID voidId = UUID.randomUUID();
    reversalPostingService.handleVoid(voidEvent(voidId, saleId));

    long revenueAfterVoid = consolidatedRevenueMinor();
    assertThat(revenueAfterVoid)
        .as("sale-then-void must net consolidated_revenue to 0 (B1 fix)")
        .isZero();
  }

  /** B1: P&L REVENUE leg also nets to zero after void. */
  @Test
  void voidOfPhase2SaleNetsPnlRevenueToZero() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(phase2SaleEvent(saleEventId, saleId));
    long pnlAfterSale = consolidatedPnlRevenue();
    assertThat(pnlAfterSale).isEqualTo(NET_REVENUE_MINOR);

    UUID voidId = UUID.randomUUID();
    reversalPostingService.handleVoid(voidEvent(voidId, saleId));

    long pnlAfterVoid = consolidatedPnlRevenue();
    assertThat(pnlAfterVoid).as("P&L revenue leg must net to 0 after void (B1 fix)").isZero();
  }

  /** B1: the SALE_VOID GL contra entry is balanced. */
  @Test
  void voidOfPhase2SaleProducesBalancedGlEntry() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(phase2SaleEvent(saleEventId, saleId));

    UUID voidId = UUID.randomUUID();
    reversalPostingService.handleVoid(voidEvent(voidId, saleId));

    assertGlEntryIsBalanced(voidId);
  }

  /** B1: void with non-zero discount — netRevenue = subtotal − discount = 25,000. */
  @Test
  void voidOfPhase2SaleWithDiscountNetsToZero() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(phase2SaleWithDiscountEvent(saleEventId, saleId));

    long revenueAfterSale = consolidatedRevenueMinor();
    assertThat(revenueAfterSale)
        .as("sale with discount must accumulate subtotal−discount = 25,000")
        .isEqualTo(DISCOUNT_NONZERO_NET);

    UUID voidId = UUID.randomUUID();
    reversalPostingService.handleVoid(voidEvent(voidId, saleId, DISCOUNT_NONZERO_GRAND));

    long revenueAfterVoid = consolidatedRevenueMinor();
    assertThat(revenueAfterVoid).as("sale-then-void (with discount) must net to 0").isZero();
    assertGlEntryIsBalanced(voidId);
  }

  /**
   * M1 (bug hunt): voiding a GIFT-CARD-settled sale must net the DIMENSIONAL ledger_posting to
   * zero. The SALE ledger_posting is booked with the grand total (100,000), but SaleVoided.amount
   * is the PAYMENT residual (grand − gift_card = 60,000). Before the fix the contra used the
   * residual → ledger_posting left standing at +40,000 (the gift-card portion), silently
   * overstating the trial balance / unit-P&L / consolidation. After the fix the contra uses the
   * reconstructed grand total (the tender legs) → nets to 0.
   */
  @Test
  void voidOfGiftCardSettledSaleNetsDimensionalLedgerToZero() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    // subtotal=100,000 no discount/sc/tax → grand=100,000; 40,000 settled by gift card, 60,000 cash.
    revenuePostingService.handle(giftCardSaleEvent(saleEventId, saleId, 100_000L, 40_000L));

    assertThat(ledgerPostingMinor())
        .as("SALE dimensional ledger_posting is booked with the grand total")
        .isEqualTo(100_000L);

    // Void: SaleVoided carries the PAYMENT residual (grand − gift_card = 60,000), not the grand
    // total.
    UUID voidId = UUID.randomUUID();
    reversalPostingService.handleVoid(voidEvent(voidId, saleId, 60_000L));

    assertThat(ledgerPostingMinor())
        .as("void must net the dimensional ledger to 0 by reversing the grand total, not the"
            + " residual (M1 fix)")
        .isZero();
    assertGlEntryIsBalanced(voidId);
  }

  /** B1: void idempotency — re-delivery does not double-unwind. */
  @Test
  void voidIdempotencyNoDoubleUnwind() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(phase2SaleEvent(saleEventId, saleId));

    UUID voidId = UUID.randomUUID();
    SaleVoidedEvent voidEvent = voidEvent(voidId, saleId);
    boolean first = reversalPostingService.handleVoid(voidEvent);
    boolean second = reversalPostingService.handleVoid(voidEvent);

    assertThat(first).isTrue();
    assertThat(second).as("re-delivery must be skipped (idempotent)").isFalse();
    assertThat(consolidatedRevenueMinor()).as("no double-unwind after re-delivery").isZero();
  }

  // -----------------------------------------------------------------------
  // W1: full refund unwinds per-leg and reads model by NET
  // -----------------------------------------------------------------------

  /** W1: full refund nets consolidated_revenue to 0. */
  @Test
  void fullRefundOfPhase2SaleNetsConsolidatedRevenueToZero() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(phase2SaleEvent(saleEventId, saleId));

    assertThat(consolidatedRevenueMinor()).isEqualTo(NET_REVENUE_MINOR);

    UUID refundId = UUID.randomUUID();
    reversalPostingService.handleRefund(
        new SaleRefundedEvent(
            refundId,
            TENANT,
            BUSINESS,
            saleId,
            UUID.randomUUID(),
            Money.ofMinor(GRAND_TOTAL_MINOR, "IDR"), // full refund = grand total
            GRAND_TOTAL_MINOR,
            OCCURRED,
            "CASH"));

    assertThat(consolidatedRevenueMinor())
        .as("full refund must net consolidated_revenue to 0 (W1 fix)")
        .isZero();
  }

  /**
   * CRITICAL (bug hunt): a FULL refund of a DISCOUNTED sale must be ACCEPTED and net revenue to
   * zero — not misclassified as partial and rejected. The full-vs-partial gate summed ALL debit
   * legs (grand + discount) and compared to the refund (grand), so every discounted / points /
   * gift-card full refund was wrongly rejected and the sale stayed counted as revenue forever.
   * This is the regression that was missing (the only full-refund test used a zero-discount sale).
   */
  @Test
  void fullRefundOfDiscountedSaleIsAcceptedAndNetsToZero() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    // Discounted sale: subtotal 30,000, discount 5,000, grand 28,875, net 25,000.
    revenuePostingService.handle(phase2SaleWithDiscountEvent(saleEventId, saleId));
    assertThat(consolidatedRevenueMinor()).isEqualTo(DISCOUNT_NONZERO_NET);

    UUID refundId = UUID.randomUUID();
    // Full refund = the grand total (28,875). Must NOT throw PartialRefundNotSupportedException.
    reversalPostingService.handleRefund(
        new SaleRefundedEvent(
            refundId,
            TENANT,
            BUSINESS,
            saleId,
            UUID.randomUUID(),
            Money.ofMinor(DISCOUNT_NONZERO_GRAND, "IDR"),
            DISCOUNT_NONZERO_GRAND,
            OCCURRED,
            "CASH"));

    assertThat(consolidatedRevenueMinor())
        .as("full refund of a discounted sale must be accepted and net revenue to 0 (CRITICAL fix)")
        .isZero();
    assertGlEntryIsBalanced(refundId);
  }

  /** W1: full refund produces a balanced GL contra entry (per-leg, not GROSS template). */
  @Test
  void fullRefundProducesBalancedGlEntry() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(phase2SaleEvent(saleEventId, saleId));

    UUID refundId = UUID.randomUUID();
    reversalPostingService.handleRefund(
        new SaleRefundedEvent(
            refundId,
            TENANT,
            BUSINESS,
            saleId,
            UUID.randomUUID(),
            Money.ofMinor(GRAND_TOTAL_MINOR, "IDR"),
            GRAND_TOTAL_MINOR,
            OCCURRED,
            "CASH"));

    assertGlEntryIsBalanced(refundId);
    // Full refund per-leg: expect the same number of lines as the original SALE entry (not 2).
    assertThat(glLineCount(refundId))
        .as("full refund must post per-leg contra entry, not 2-line GROSS template")
        .isGreaterThan(2);
  }

  /** W1: full refund idempotency. */
  @Test
  void fullRefundIdempotencyNoDoubleUnwind() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(phase2SaleEvent(saleEventId, saleId));

    UUID refundId = UUID.randomUUID();
    SaleRefundedEvent refundEvent =
        new SaleRefundedEvent(
            refundId,
            TENANT,
            BUSINESS,
            saleId,
            UUID.randomUUID(),
            Money.ofMinor(GRAND_TOTAL_MINOR, "IDR"),
            GRAND_TOTAL_MINOR,
            OCCURRED,
            "CASH");

    boolean first = reversalPostingService.handleRefund(refundEvent);
    boolean second = reversalPostingService.handleRefund(refundEvent);

    assertThat(first).isTrue();
    assertThat(second).as("re-delivery skipped (idempotent)").isFalse();
    assertThat(consolidatedRevenueMinor()).as("no double-unwind").isZero();
  }

  /** W1: partial refund is rejected with PartialRefundNotSupportedException. */
  @Test
  void partialRefundWithExistingOriginalEntryIsRejected() throws Exception {
    UUID saleId = UUID.randomUUID();
    UUID saleEventId = UUID.randomUUID();
    revenuePostingService.handle(phase2SaleEvent(saleEventId, saleId));

    UUID refundId = UUID.randomUUID();
    SaleRefundedEvent partialRefund =
        new SaleRefundedEvent(
            refundId,
            TENANT,
            BUSINESS,
            saleId,
            UUID.randomUUID(),
            Money.ofMinor(10_000L, "IDR"), // partial: 10,000 < 34,650
            10_000L,
            OCCURRED,
            "CASH");

    assertThatThrownBy(() -> reversalPostingService.handleRefund(partialRefund))
        .isInstanceOf(PartialRefundNotSupportedException.class)
        .hasMessageContaining("Partial refund not yet supported");

    // No GL entry or read-model update must have been written (transaction rolled back).
    assertThat(consolidatedRevenueMinor())
        .as("partial refund rejection must not unwind revenue")
        .isEqualTo(NET_REVENUE_MINOR);
  }

  // -----------------------------------------------------------------------
  // Backward-compat: legacy (no original SALE entry) void still works
  // -----------------------------------------------------------------------

  /** Legacy/carwash void (no original SALE entry found) falls back to grand-total unwind. */
  @Test
  void legacyVoidWithoutOriginalEntryUnwindsByGrandTotal() throws Exception {
    // Post a legacy SaleRecorded (no saleId, no breakdown) — net == gross.
    UUID saleEventId = UUID.randomUUID();
    long legacyAmount = 20_000L;
    revenuePostingService.handle(
        new SaleRecordedEvent(
            saleEventId, TENANT, BUSINESS, Money.ofMinor(legacyAmount, "IDR"), OCCURRED));

    assertThat(consolidatedRevenueMinor()).isEqualTo(legacyAmount);

    // Void with a saleId that is NOT linked to any SALE journal entry (null → no lookup).
    UUID voidId = UUID.randomUUID();
    reversalPostingService.handleVoid(
        new SaleVoidedEvent(
            voidId,
            TENANT,
            BUSINESS,
            null, // no saleId — triggers legacy fall-back
            UUID.randomUUID(),
            Money.ofMinor(legacyAmount, "IDR"),
            OCCURRED,
            "CASH"));

    assertThat(consolidatedRevenueMinor())
        .as("legacy void must unwind by grand total (net == gross for legacy)")
        .isZero();
  }

  // -----------------------------------------------------------------------
  // Helpers — event factories
  // -----------------------------------------------------------------------

  private SaleRecordedEvent phase2SaleEvent(UUID eventId, UUID saleId) {
    return new SaleRecordedEvent(
        eventId,
        saleId,
        TENANT,
        BUSINESS,
        Money.ofMinor(GRAND_TOTAL_MINOR, "IDR"),
        OCCURRED,
        "CASH",
        SUBTOTAL_MINOR,
        DISCOUNT_MINOR,
        SERVICE_CHARGE_MINOR,
        TAX_MINOR,
        "v1-illustrative",
        true);
  }

  private SaleRecordedEvent phase2SaleWithDiscountEvent(UUID eventId, UUID saleId) {
    return new SaleRecordedEvent(
        eventId,
        saleId,
        TENANT,
        BUSINESS,
        Money.ofMinor(DISCOUNT_NONZERO_GRAND, "IDR"),
        OCCURRED,
        "CASH",
        DISCOUNT_NONZERO_SUBTOTAL,
        DISCOUNT_NONZERO_DISCOUNT,
        DISCOUNT_NONZERO_SC,
        DISCOUNT_NONZERO_TAX,
        "v1-illustrative",
        true);
  }

  /**
   * A gift-card-settled SALE (Phase 4): {@code grand} owed, {@code giftCard} covered by stored
   * value (the rest by cash). No discount/SC/tax so subtotal == grand, keeping the arithmetic clear.
   */
  private SaleRecordedEvent giftCardSaleEvent(UUID eventId, UUID saleId, long grand, long giftCard) {
    return new SaleRecordedEvent(
        eventId,
        saleId,
        TENANT,
        BUSINESS,
        Money.ofMinor(grand, "IDR"),
        OCCURRED,
        "CASH",
        grand, // subtotal == grand (no discount/sc/tax)
        0L,
        0L,
        0L,
        "v1-illustrative",
        true,
        null, // loyaltyMemberId
        null, // loyaltyRedeemedPoints
        null, // loyaltyRedeemedMinor
        UUID.randomUUID().toString(), // giftCardId
        giftCard); // giftCardRedeemedMinor
  }

  private SaleVoidedEvent voidEvent(UUID voidId, UUID saleId) {
    return voidEvent(voidId, saleId, GRAND_TOTAL_MINOR);
  }

  private SaleVoidedEvent voidEvent(UUID voidId, UUID saleId, long amountMinor) {
    return new SaleVoidedEvent(
        voidId,
        TENANT,
        BUSINESS,
        saleId,
        UUID.randomUUID(),
        Money.ofMinor(amountMinor, "IDR"),
        OCCURRED,
        "CASH");
  }

  // -----------------------------------------------------------------------
  // Helpers — DB reads over admin (BYPASSRLS) connection
  // -----------------------------------------------------------------------

  private long consolidatedRevenueMinor() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(sum(total_minor), 0) FROM consolidated_revenue"
                    + " WHERE company_id = '"
                    + TENANT
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long ledgerPostingMinor() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(sum(amount_minor), 0) FROM ledger_posting"
                    + " WHERE company_id = '"
                    + TENANT
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long consolidatedPnlRevenue() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT COALESCE(sum(revenue_minor), 0) FROM consolidated_pnl"
                    + " WHERE company_id = '"
                    + TENANT
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private void assertGlEntryIsBalanced(UUID sourceEventId) throws Exception {
    String sql =
        "SELECT jl.debit_minor, jl.credit_minor"
            + " FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + sourceEventId
            + "'";
    long totalDebit = 0L;
    long totalCredit = 0L;
    int lines = 0;
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        totalDebit += rs.getLong("debit_minor");
        totalCredit += rs.getLong("credit_minor");
        lines++;
      }
    }
    assertThat(lines)
        .as("GL entry must have >= 2 lines for sourceEventId=" + sourceEventId)
        .isGreaterThanOrEqualTo(2);
    assertThat(totalDebit).as("GL entry must balance (Σdebit == Σcredit)").isEqualTo(totalCredit);
    assertThat(totalDebit).as("GL entry totals must be positive").isPositive();
  }

  private int glLineCount(UUID sourceEventId) throws Exception {
    String sql =
        "SELECT COUNT(*) FROM journal_entry je"
            + " JOIN journal_line jl ON jl.entry_id = je.id"
            + " WHERE je.source_event_id = '"
            + sourceEventId
            + "'";
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @BeforeEach
  @Override
  void resetTables() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE ledger_posting, consolidated_revenue, consolidated_pnl,"
              + " outlet_revenue, org_unit_ref,"
              + " payroll_run_ledger, group_ref, group_member, group_lead,"
              + " group_trial_balance, consolidation_ledger, consolidation_summary,"
              + " intercompany_match, group_membership_pending, processed_event,"
              + " outbox, within_company_close, member_group_index,"
              + " journal_line, journal_entry CASCADE");
    } catch (Exception ignored) {
      // Tables not yet created.
    }
  }
}
