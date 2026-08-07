package id.co.nativeapp.payment.settings.repository;

import id.co.nativeapp.payment.settings.domain.PaymentSettings;
import id.co.nativeapp.payment.settings.projection.QrImageView;
import id.co.nativeapp.payment.settings.projection.SettingsView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data port for {@code payment_settings}. Reads are native queries into projections (ADR 0002 /
 * CODE-STRUCTURE §3.3) — and deliberately NEVER select the encrypted credential columns (rule 6);
 * the full {@link PaymentSettings} entity (which decrypts them via its converter) is loaded only on
 * the write path via the derived finders / inherited {@code findById}/{@code save}. Every query is
 * tenant-scoped by RLS (rule 5) — the session GUC bounds every statement, so no explicit {@code
 * company_id} predicate is repeated here.
 */
public interface PaymentSettingsRepository extends JpaRepository<PaymentSettings, UUID> {

  /** Write path: the company default row (full aggregate — needed to mutate it). */
  Optional<PaymentSettings> findByOutletIdIsNull();

  /** Write path: an outlet's override row (full aggregate — needed to mutate it). */
  Optional<PaymentSettings> findByOutletId(UUID outletId);

  /** All of the tenant's rows — the owner list and the effective-mode resolution source. */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT id,
                 outlet_id,
                 mode,
                 (static_qr_data IS NOT NULL)      AS has_static_image,
                 static_qr_byte_size,
                 static_qr_sha256,
                 provider,
                 provider_environment,
                 server_key_last4,
                 (server_key_encrypted IS NOT NULL) AS gateway_connected
            FROM payment_settings
           ORDER BY outlet_id NULLS FIRST
          """)
  List<SettingsView> findAllViews();

  /** The outlet override's image, if that row exists AND carries one. */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT static_qr_content_type AS content_type,
                 static_qr_sha256       AS sha256,
                 static_qr_data         AS data
            FROM payment_settings
           WHERE outlet_id = :outletId
             AND static_qr_data IS NOT NULL
          """)
  Optional<QrImageView> findOutletImage(@Param("outletId") UUID outletId);

  /** The company default row's image, if it exists AND carries one. */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT static_qr_content_type AS content_type,
                 static_qr_sha256       AS sha256,
                 static_qr_data         AS data
            FROM payment_settings
           WHERE outlet_id IS NULL
             AND static_qr_data IS NOT NULL
          """)
  Optional<QrImageView> findCompanyDefaultImage();
}
