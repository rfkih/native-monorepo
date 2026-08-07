package id.co.nativeapp.payment.settings.service;

import id.co.nativeapp.payment.config.ActorRolesProvider;
import id.co.nativeapp.payment.settings.domain.PaymentSettings;
import id.co.nativeapp.payment.settings.domain.SettingsForbiddenException;
import id.co.nativeapp.payment.settings.dto.EffectiveSettingsResponse;
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

  public SettingsService(SettingsWriter writer, SettingsReader reader, ActorRolesProvider roles) {
    this.writer = writer;
    this.reader = reader;
    this.roles = roles;
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

  /** Owner: upsert an outlet override (mode only). */
  public PaymentSettingsResponse upsertOutletOverride(
      UUID outletId, UpsertSettingsRequest request) {
    requireOwner();
    writer.upsertOutletOverride(outletId, request);
    return reader.list();
  }

  /** Owner: delete an outlet override. */
  public void deleteOutletOverride(UUID outletId) {
    requireOwner();
    writer.deleteOutletOverride(outletId);
  }

  /** Owner: upload/replace a scope's static QRIS image. */
  public QrImageMetaResponse uploadStaticQr(
      UUID outletId, String declaredContentType, byte[] data) {
    requireOwner();
    PaymentSettings row = writer.attachStaticQr(outletId, declaredContentType, data);
    return new QrImageMetaResponse(
        row.getStaticQrContentType(), row.getStaticQrByteSize(), row.getStaticQrSha256());
  }

  /** Owner: remove a scope's static QRIS image. */
  public void removeStaticQr(UUID outletId) {
    requireOwner();
    writer.removeStaticQr(outletId);
  }

  /** POS: the effective mode/availability for the till's outlet. NOT owner-gated. */
  public EffectiveSettingsResponse effective(UUID businessId) {
    return reader.effective(businessId);
  }

  /** POS: the effective static QRIS image blob, mapped to its dto. NOT owner-gated. */
  public QrImageContentResponse effectiveImage(UUID businessId) {
    QrImageView image = reader.effectiveImage(businessId);
    return new QrImageContentResponse(
        image.getContentType(), image.getSha256().strip(), image.getData());
  }

  private void requireOwner() {
    if (!roles.currentRoles().isEmpty() && !roles.isOwner()) {
      throw new SettingsForbiddenException();
    }
  }
}
