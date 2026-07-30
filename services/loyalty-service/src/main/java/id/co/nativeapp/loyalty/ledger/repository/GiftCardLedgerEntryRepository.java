package id.co.nativeapp.loyalty.ledger.repository;

import id.co.nativeapp.loyalty.ledger.domain.GiftCardLedgerEntry;
import id.co.nativeapp.loyalty.ledger.domain.GiftCardLedgerEntryType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link GiftCardLedgerEntry}. The RLS policy on {@code
 * gift_card_ledger_entry} scopes every query to the bound tenant automatically.
 */
public interface GiftCardLedgerEntryRepository extends JpaRepository<GiftCardLedgerEntry, UUID> {

  /** The REDEEM entries recorded against one sale — summed by the reversal writer. */
  List<GiftCardLedgerEntry> findBySaleIdAndEntryTypeIn(
      UUID saleId, List<GiftCardLedgerEntryType> types);

  /**
   * Whether a sale's gift-card activity was already reversed — see the loyalty twin (review S2).
   */
  boolean existsBySaleIdAndEntryType(UUID saleId, GiftCardLedgerEntryType entryType);
}
