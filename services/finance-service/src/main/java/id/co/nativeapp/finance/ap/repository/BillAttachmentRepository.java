package id.co.nativeapp.finance.ap.repository;

import id.co.nativeapp.finance.ap.domain.BillAttachment;
import id.co.nativeapp.finance.ap.projection.BillAttachmentView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Metadata rows of a bill's attachments (ADR 0084); the payloads live in the object store. */
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

  /** The number of attachments already on a bill — backs the per-bill cap. Scalar, RLS-scoped. */
  long countByBillId(UUID billId);

  /**
   * A bill's existing attachment with the given content hash — the idempotency probe (a
   * byte-identical re-upload returns this row instead of inserting a duplicate).
   */
  Optional<BillAttachment> findFirstByBillIdAndSha256(UUID billId, String sha256);
}
