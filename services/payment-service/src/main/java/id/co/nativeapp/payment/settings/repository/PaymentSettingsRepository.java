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
 *
 * <p>{@code org_unit_id} (V4, ADR 0045 amendment) holds ANY org-unit id — an OUTLET or a DIVISION
 * (business-unit) id from org-service's tree; this repository is scope-agnostic between the two
 * (payment-service holds no org read model), so the same finders serve both an outlet lookup and a
 * division lookup.
 */
public interface PaymentSettingsRepository extends JpaRepository<PaymentSettings, UUID> {

  /** Write path: the company default row (full aggregate — needed to mutate it). */
  Optional<PaymentSettings> findByOrgUnitIdIsNull();

  /**
   * Write path: a unit's (outlet or division) override row (full aggregate — needed to mutate it).
   */
  Optional<PaymentSettings> findByOrgUnitId(UUID orgUnitId);

  /** All of the tenant's rows — the owner list and the effective-mode resolution source. */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT id,
                 org_unit_id,
                 mode,
                 (static_qr_data IS NOT NULL
                    OR static_qr_object_key IS NOT NULL) AS has_static_image,
                 static_qr_byte_size,
                 static_qr_sha256,
                 provider,
                 provider_environment,
                 sandbox_server_key_last4,
                 production_server_key_last4,
                 (sandbox_server_key_encrypted IS NOT NULL) AS sandbox_connected,
                 (production_server_key_encrypted IS NOT NULL) AS production_connected,
                 (CASE provider_environment
                    WHEN 'SANDBOX' THEN sandbox_server_key_encrypted IS NOT NULL
                    WHEN 'PRODUCTION' THEN production_server_key_encrypted IS NOT NULL
                    ELSE FALSE END) AS gateway_connected
            FROM payment_settings
           ORDER BY org_unit_id NULLS FIRST
          """)
  List<SettingsView> findAllViews();

  /**
   * A unit's (outlet or division) image, if that row exists AND carries one — "carries one" =
   * either payload home (inline bytea on a legacy row, object key since ADR 0048).
   */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT id                     AS id,
                 static_qr_content_type AS content_type,
                 static_qr_sha256       AS sha256,
                 static_qr_data         AS data,
                 static_qr_object_key   AS object_key
            FROM payment_settings
           WHERE org_unit_id = :orgUnitId
             AND (static_qr_data IS NOT NULL OR static_qr_object_key IS NOT NULL)
          """)
  Optional<QrImageView> findImageByOrgUnitId(@Param("orgUnitId") UUID orgUnitId);

  /** The company default row's image, if it exists AND carries one (either payload home). */
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT id                     AS id,
                 static_qr_content_type AS content_type,
                 static_qr_sha256       AS sha256,
                 static_qr_data         AS data,
                 static_qr_object_key   AS object_key
            FROM payment_settings
           WHERE org_unit_id IS NULL
             AND (static_qr_data IS NOT NULL OR static_qr_object_key IS NOT NULL)
          """)
  Optional<QrImageView> findCompanyDefaultImage();
}
