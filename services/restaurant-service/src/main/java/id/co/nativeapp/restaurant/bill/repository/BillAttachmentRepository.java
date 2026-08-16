package id.co.nativeapp.restaurant.bill.repository;

import id.co.nativeapp.restaurant.bill.domain.BillAttachment;
import id.co.nativeapp.restaurant.bill.projection.BillAttachmentView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link BillAttachment}.
 *
 * <p>No manual {@code WHERE company_id} — the RLS policy (V40) applies the tenant automatically
 * (rule 5). The list read returns a narrow projection (no payload, no object key), never {@code
 * SELECT *}; the single-attachment serve path loads the full entity via inherited {@code findById}
 * (it needs the {@code object_key} to fetch the bytes).
 */
public interface BillAttachmentRepository extends JpaRepository<BillAttachment, UUID> {

  /** Metadata of a bill's attachments, oldest first. RLS-scoped to the session tenant. */
  @Query(
      value =
          """
          SELECT ba.id                AS id,
                 ba.content_type       AS content_type,
                 ba.byte_size          AS byte_size,
                 ba.sha256             AS sha256,
                 ba.original_filename  AS original_filename,
                 ba.created_at         AS created_at
            FROM bill_attachment ba
           WHERE ba.bill_id = :billId
           ORDER BY ba.created_at
          """,
      nativeQuery = true)
  List<BillAttachmentView> findMetaByBillId(@Param("billId") UUID billId);

  /**
   * The number of attachments already on a bill — backs the per-bill cap (flaw-audit W1: without a
   * ceiling a looping client writes unbounded 5 MiB objects). Derived scalar count, RLS-scoped.
   */
  long countByBillId(UUID billId);

  /**
   * A bill's existing attachment with the given content hash, if any — the idempotency probe
   * (flaw-audit W3): a byte-identical re-upload (network retry, double-tap) returns this row
   * instead of inserting a duplicate. Derived query, RLS-scoped; the CHAR(64) column compares
   * exactly against the 64-hex digest.
   */
  Optional<BillAttachment> findFirstByBillIdAndSha256(UUID billId, String sha256);
}
