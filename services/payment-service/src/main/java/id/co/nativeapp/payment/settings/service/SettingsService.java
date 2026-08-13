package id.co.nativeapp.payment.settings.service;

import id.co.nativeapp.mediastorage.MediaStorage;
import id.co.nativeapp.payment.charge.service.QrisGatewayPort;
import id.co.nativeapp.payment.config.ActorRolesProvider;
import id.co.nativeapp.payment.settings.domain.PaymentSettings;
import id.co.nativeapp.payment.settings.domain.ProviderEnvironment;
import id.co.nativeapp.payment.settings.domain.SettingsForbiddenException;
import id.co.nativeapp.payment.settings.domain.SettingsValidationException;
import id.co.nativeapp.payment.settings.dto.EffectiveSettingsResponse;
import id.co.nativeapp.payment.settings.dto.GatewayVerifyRequest;
import id.co.nativeapp.payment.settings.dto.GatewayVerifyResponse;
import id.co.nativeapp.payment.settings.dto.PaymentSettingsResponse;
import id.co.nativeapp.payment.settings.dto.QrImageContentResponse;
import id.co.nativeapp.payment.settings.dto.QrImageMetaResponse;
import id.co.nativeapp.payment.settings.dto.UpsertSettingsRequest;
import id.co.nativeapp.payment.settings.projection.QrImageView;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the payment-settings surface (ADR 0045): the OWNER-ONLY admin operations (list,
 * upsert, image management — the gateway routes them owner-only; {@link #requireOwner()} re-checks
 * service-side, defense in depth) and the POS-facing effective/image reads (any authenticated
 * company member — the till runs as cashier).
 *
 * <p>The role guard follows the fleet's dev-recipe trust (the loyalty earn-rule idiom): a caller
 * whose PRESENT roles lack {@code owner} is rejected; a caller with NO roles header (service-layer
 * tests, the dev profile without gateway headers) is not.
 */
@Service
public class SettingsService {

  private final SettingsWriter writer;
  private final SettingsReader reader;
  private final ActorRolesProvider roles;
  private final MediaStorage mediaStorage;
  private final QrObjectMigrationWriter migrationWriter;
  private final QrisGatewayPort gatewayPort;

  public SettingsService(
      SettingsWriter writer,
      SettingsReader reader,
      ActorRolesProvider roles,
      MediaStorage mediaStorage,
      QrObjectMigrationWriter migrationWriter,
      QrisGatewayPort gatewayPort) {
    this.writer = writer;
    this.reader = reader;
    this.roles = roles;
    this.mediaStorage = mediaStorage;
    this.migrationWriter = migrationWriter;
    this.gatewayPort = gatewayPort;
  }

  /** Owner: the company's full QRIS configuration. */
  public PaymentSettingsResponse list() {
    requireOwner();
    return reader.list();
  }

  /** Owner: upsert the company default scope. */
  public PaymentSettingsResponse upsertCompanyDefault(UpsertSettingsRequest request) {
    requireOwner();
    writer.upsertCompanyDefault(request);
    return reader.list();
  }

  /** Owner: upsert a unit (outlet or division) override (mode only). */
  public PaymentSettingsResponse upsertUnitOverride(UUID unitId, UpsertSettingsRequest request) {
    requireOwner();
    writer.upsertUnitOverride(unitId, request);
    return reader.list();
  }

  /** Owner: delete a unit (outlet or division) override. */
  public void deleteUnitOverride(UUID unitId) {
    requireOwner();
    writer.deleteUnitOverride(unitId);
  }

  /**
   * Owner: verify a Midtrans key against the provider without saving or charging (ADR 0045
   * amendment — "Test connection"). Probes the supplied key, or the key already stored for that
   * environment's slot when none is supplied. Availability only — the key never leaves call scope.
   */
  public GatewayVerifyResponse verifyGateway(GatewayVerifyRequest request) {
    requireOwner();
    ProviderEnvironment environment = ProviderEnvironment.parse(request.environment());
    String serverKey =
        notBlank(request.serverKey())
            ? request.serverKey().strip()
            : writer.serverKeyForEnvironment(environment).orElse(null);
    if (serverKey == null) {
      throw new SettingsValidationException(
          "No server key is stored for the " + environment + " environment to verify.");
    }
    QrisGatewayPort.GatewayVerification result =
        gatewayPort.verify(new QrisGatewayPort.GatewayCredentials(serverKey, environment));
    return new GatewayVerifyResponse(result.name());
  }

  /** Owner: upload/replace a scope's static QRIS image. */
  public QrImageMetaResponse uploadStaticQr(UUID unitId, String declaredContentType, byte[] data) {
    requireOwner();
    PaymentSettings row = writer.attachStaticQr(unitId, declaredContentType, data);
    return new QrImageMetaResponse(
        row.getStaticQrContentType(), row.getStaticQrByteSize(), row.getStaticQrSha256());
  }

  /** Owner: remove a scope's static QRIS image. */
  public void removeStaticQr(UUID unitId) {
    requireOwner();
    writer.removeStaticQr(unitId);
  }

  /**
   * POS: the effective mode/availability for the till's outlet ({@code businessId}) and division
   * ({@code divisionId}, both nullable). NOT owner-gated.
   */
  public EffectiveSettingsResponse effective(UUID businessId, UUID divisionId) {
    return reader.effective(businessId, divisionId);
  }

  /**
   * POS: the effective static QRIS image blob, mapped to its dto. NOT owner-gated. Payload homes
   * (ADR 0048): an object-backed row's bytes come from the object store; a legacy row serves its
   * inline bytea and opportunistically flips to object-backed (read-through migration — never on
   * the critical path, failures swallowed in {@link QrObjectMigrationWriter}).
   */
  public QrImageContentResponse effectiveImage(UUID businessId, UUID divisionId) {
    QrImageView image = reader.effectiveImage(businessId, divisionId);
    byte[] data;
    if (image.getObjectKey() != null) {
      data = mediaStorage.get(image.getObjectKey()).data();
    } else {
      data = image.getData();
      migrationWriter.migrateOpportunistically(image.getId());
    }
    return new QrImageContentResponse(image.getContentType(), image.getSha256().strip(), data);
  }

  private void requireOwner() {
    if (!roles.currentRoles().isEmpty() && !roles.isOwner()) {
      throw new SettingsForbiddenException();
    }
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }
}
