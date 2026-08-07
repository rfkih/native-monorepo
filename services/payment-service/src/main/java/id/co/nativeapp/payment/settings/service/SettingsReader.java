package id.co.nativeapp.payment.settings.service;

import id.co.nativeapp.payment.settings.domain.PaymentSettingsNotFoundException;
import id.co.nativeapp.payment.settings.domain.QrisMode;
import id.co.nativeapp.payment.settings.dto.EffectiveSettingsResponse;
import id.co.nativeapp.payment.settings.dto.PaymentSettingsResponse;
import id.co.nativeapp.payment.settings.dto.SettingsRowResponse;
import id.co.nativeapp.payment.settings.projection.QrImageView;
import id.co.nativeapp.payment.settings.projection.SettingsView;
import id.co.nativeapp.payment.settings.repository.PaymentSettingsRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of {@code payment_settings} (ADR 0045, DIVISION-scope amendment) — served entirely from
 * projections that never touch the encrypted credential columns (rule 6). A tenant's settings are
 * at most 1 + #units rows, so the effective resolution (outlet override → division override →
 * company default → implicit MANUAL) is done in Java over the one list query rather than in SQL.
 * payment-service holds no org read model: {@code businessId} (outlet) and {@code divisionId} are
 * whatever the console/till supplies, matched by simple id equality against {@code org_unit_id} —
 * scope-agnostic between an outlet row and a division row by construction.
 *
 * <p>{@code @Transactional} so the RLS aspect binds the tenant GUC for the read (the raw
 * TransactionTemplate-less read would run unbound and fail closed — the fleet's known gotcha).
 */
@Component
public class SettingsReader {

  private final PaymentSettingsRepository repository;

  public SettingsReader(PaymentSettingsRepository repository) {
    this.repository = repository;
  }

  /** The owner list: company default + unit (outlet/division) overrides. */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public PaymentSettingsResponse list() {
    TenantContext.require();
    List<SettingsView> views = repository.findAllViews();
    SettingsRowResponse companyDefault =
        views.stream()
            .filter(v -> v.getOrgUnitId() == null)
            .findFirst()
            .map(this::toRow)
            .orElse(null);
    List<SettingsRowResponse> overrides =
        views.stream().filter(v -> v.getOrgUnitId() != null).map(this::toRow).toList();
    return new PaymentSettingsResponse(companyDefault, overrides);
  }

  /**
   * What the till needs at tender time, resolved for {@code businessId} (the outlet, nullable) and
   * {@code divisionId} (the outlet's parent business unit, nullable). Facet fallbacks per the ADR
   * amendment: mode = outlet ?? division ?? company ?? implicit MANUAL; static-image availability
   * is {@code true} when ANY of the three rows carries one; gateway state comes from the COMPANY
   * row only (credentials are company-level).
   */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public EffectiveSettingsResponse effective(UUID businessId, UUID divisionId) {
    TenantContext.require();
    List<SettingsView> views = repository.findAllViews();
    Optional<SettingsView> companyRow =
        views.stream().filter(v -> v.getOrgUnitId() == null).findFirst();
    Optional<SettingsView> divisionRow = findByUnitId(views, divisionId);
    Optional<SettingsView> outletRow = findByUnitId(views, businessId);

    String mode =
        outletRow
            .map(SettingsView::getMode)
            .or(() -> divisionRow.map(SettingsView::getMode))
            .or(() -> companyRow.map(SettingsView::getMode))
            .orElse(QrisMode.MANUAL.name());
    boolean staticAvailable =
        outletRow.map(SettingsView::getHasStaticImage).orElse(false)
            || divisionRow.map(SettingsView::getHasStaticImage).orElse(false)
            || companyRow.map(SettingsView::getHasStaticImage).orElse(false);
    EffectiveSettingsResponse.EffectiveGatewayResponse gateway =
        companyRow
            .filter(v -> v.getProvider() != null)
            .map(
                v ->
                    new EffectiveSettingsResponse.EffectiveGatewayResponse(
                        v.getProvider(), v.getProviderEnvironment(), v.getGatewayConnected()))
            .orElse(null);
    return new EffectiveSettingsResponse(mode, staticAvailable, gateway);
  }

  /**
   * The effective static QRIS image for {@code businessId} (outlet, nullable) / {@code divisionId}
   * (nullable): outlet's own image wins, else the division image, else the company image — a row
   * WITHOUT an image still falls through to the next scope (the per-facet rule).
   *
   * @throws PaymentSettingsNotFoundException when none of the three scopes carries an image (→ 404)
   */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public QrImageView effectiveImage(UUID businessId, UUID divisionId) {
    TenantContext.require();
    Optional<QrImageView> outletImage =
        businessId == null ? Optional.empty() : repository.findImageByOrgUnitId(businessId);
    return outletImage
        .or(
            () ->
                divisionId == null ? Optional.empty() : repository.findImageByOrgUnitId(divisionId))
        .or(repository::findCompanyDefaultImage)
        .orElseThrow(
            () -> new PaymentSettingsNotFoundException("No static QRIS image is configured."));
  }

  private static Optional<SettingsView> findByUnitId(List<SettingsView> views, UUID unitId) {
    return unitId == null
        ? Optional.empty()
        : views.stream().filter(v -> unitId.equals(v.getOrgUnitId())).findFirst();
  }

  private SettingsRowResponse toRow(SettingsView view) {
    SettingsRowResponse.GatewayInfoResponse gateway =
        view.getProvider() == null
            ? null
            : new SettingsRowResponse.GatewayInfoResponse(
                view.getProvider(),
                view.getProviderEnvironment(),
                view.getServerKeyLast4(),
                view.getGatewayConnected());
    return new SettingsRowResponse(
        view.getId(),
        view.getOrgUnitId(),
        view.getMode(),
        view.getHasStaticImage(),
        view.getStaticQrByteSize(),
        view.getStaticQrSha256(),
        gateway);
  }
}
