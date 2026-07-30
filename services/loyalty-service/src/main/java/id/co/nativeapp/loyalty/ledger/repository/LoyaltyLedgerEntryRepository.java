package id.co.nativeapp.loyalty.ledger.repository;

import id.co.nativeapp.loyalty.ledger.domain.LoyaltyLedgerEntry;
import id.co.nativeapp.loyalty.ledger.domain.LoyaltyLedgerEntryType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link LoyaltyLedgerEntry}. The RLS policy on {@code
 * loyalty_ledger_entry} scopes every query to the bound tenant automatically — no manual {@code
 * WHERE company_id} is required.
 */
public interface LoyaltyLedgerEntryRepository extends JpaRepository<LoyaltyLedgerEntry, UUID> {

  /**
   * The EARN/REDEEM entries recorded against one sale — the reversal writer sums these to compute
   * the net reversal (see {@code ingest.service.SaleReversalWriter}). Excludes any prior {@code
   * REVERSE}/{@code ADJUST} row for the same sale so a reversal is never itself reversed.
   */
  List<LoyaltyLedgerEntry> findBySaleIdAndEntryTypeIn(
      UUID saleId, List<LoyaltyLedgerEntryType> types);

  /**
   * Whether a sale has already been reversed — the self-enforcing guard against TWO DISTINCT
   * reversal events for one sale (e.g. a {@code SaleVoided} followed by a {@code SaleRefunded})
   * each re-reversing the originals and double-crediting (review S2). A scalar exists, not a
   * projection (CLAUDE.md read-path convention).
   */
  boolean existsBySaleIdAndEntryType(UUID saleId, LoyaltyLedgerEntryType entryType);
}
