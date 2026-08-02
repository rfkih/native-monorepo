package id.co.nativeapp.restaurant.channel.service;

import id.co.nativeapp.restaurant.channel.domain.SalesChannel;
import id.co.nativeapp.restaurant.channel.domain.SalesChannelCodeConflictException;
import id.co.nativeapp.restaurant.channel.domain.SalesChannelForbiddenException;
import id.co.nativeapp.restaurant.channel.domain.SalesChannelNotFoundException;
import id.co.nativeapp.restaurant.channel.dto.CreateSalesChannelRequest;
import id.co.nativeapp.restaurant.channel.dto.SalesChannelResponse;
import id.co.nativeapp.restaurant.channel.dto.UpdateSalesChannelRequest;
import id.co.nativeapp.restaurant.channel.projection.SalesChannelView;
import id.co.nativeapp.restaurant.channel.repository.SalesChannelRepository;
import id.co.nativeapp.restaurant.config.ActorRolesProvider;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for the company-managed sales-channel catalog
 * (ADR 0036 Phase B2) — the source of the {@code channelCode} a POS checkout stamps on an {@code
 * ONLINE} tender.
 *
 * <p>A distinct {@code @Component} bean (not inline in the controller) so the Spring proxy applies
 * the transaction + {@code RlsAutoApplyAspect} tenant-GUC advice (the {@code RegisterSessionWriter}
 * pattern). {@code SalesChannelController} calls this bean directly — mirroring how a
 * single-writer feature (e.g. {@code register}) skips an intermediate {@code *Service} facade when
 * there is no cross-transaction orchestration to coordinate.
 *
 * <p><strong>Role gate.</strong> A channel is money-routing configuration (it decides how an {@code
 * ONLINE} sale's platform receivable is bucketed at finance) — CREATE/PATCH require {@code
 * owner}/{@code manager}; LIST (the POS channel picker at checkout) is open to every POS role,
 * including {@code cashier}. Empty-roles-pass semantics, mirroring {@code ManualDiscountGuard}/
 * {@code PromotionAdminService}: an EMPTY role set is let through (the {@code X-Roles} header only
 * exists behind the gateway, so a real cashier token is always denied; a headerless request is the
 * gateway-less dev recipe or a direct service-layer test).
 */
@Component
public class SalesChannelWriter {

  private final SalesChannelRepository repository;
  private final ActorRolesProvider actorRoles;

  public SalesChannelWriter(SalesChannelRepository repository, ActorRolesProvider actorRoles) {
    this.repository = repository;
    this.actorRoles = actorRoles;
  }

  /**
   * Creates a sales channel. The code is normalized to uppercase/trim before the duplicate
   * pre-check and the insert (mirrors {@code PromotionAdminWriter.createCoupon}'s coupon-code
   * normalization).
   *
   * @throws SalesChannelForbiddenException if the caller's role set is non-empty and neither
   *     {@code owner} nor {@code manager}
   * @throws SalesChannelCodeConflictException if a channel with this (normalized) code already
   *     exists for the tenant
   */
  @Transactional
  public SalesChannelResponse create(CreateSalesChannelRequest request) {
    requireWriteRole();
    String companyId = TenantContext.require().companyId();

    String code = normalize(request.code());
    if (repository.existsByCode(code)) {
      throw new SalesChannelCodeConflictException(code);
    }

    SalesChannel channel = new SalesChannel(code, request.name().strip());
    channel.setCompanyId(companyId);
    return toResponse(repository.saveAndFlush(channel));
  }

  /**
   * Updates a sales channel's name and/or active flag. {@code code} is never accepted here — it is
   * immutable once created (see {@code SalesChannel}).
   *
   * @throws SalesChannelForbiddenException if the caller's role set is non-empty and neither
   *     {@code owner} nor {@code manager}
   * @throws SalesChannelNotFoundException if the id is unknown/cross-tenant
   */
  @Transactional
  public SalesChannelResponse update(UUID id, UpdateSalesChannelRequest request) {
    requireWriteRole();
    SalesChannel channel =
        repository.findById(id).orElseThrow(() -> new SalesChannelNotFoundException(id));

    if (request.name() != null) {
      channel.rename(request.name().strip());
    }
    if (request.active() != null) {
      channel.setActive(request.active());
    }
    return toResponse(repository.saveAndFlush(channel));
  }

  /**
   * Every sales channel for the bound tenant, ordered by code — the POS checkout channel picker.
   * NOT role-gated (see class javadoc).
   */
  @Transactional(readOnly = true)
  public List<SalesChannelResponse> list() {
    return repository.findAllViews().stream().map(SalesChannelWriter::toResponse).toList();
  }

  /**
   * A single sales channel by id.
   *
   * @throws SalesChannelNotFoundException if the id is unknown/cross-tenant
   */
  @Transactional(readOnly = true)
  public SalesChannelResponse get(UUID id) {
    return repository
        .findViewById(id)
        .map(SalesChannelWriter::toResponse)
        .orElseThrow(() -> new SalesChannelNotFoundException(id));
  }

  /**
   * Empty-roles-pass semantics (see class javadoc): an EMPTY role set is let through; a non-empty
   * set that is neither {@code owner} nor {@code manager} is denied.
   */
  private void requireWriteRole() {
    if (!actorRoles.currentRoles().isEmpty() && !actorRoles.isOwnerOrManager()) {
      throw new SalesChannelForbiddenException();
    }
  }

  private static String normalize(String code) {
    return code.strip().toUpperCase(Locale.ROOT);
  }

  private static SalesChannelResponse toResponse(SalesChannel channel) {
    return new SalesChannelResponse(
        channel.getId(), channel.getCode(), channel.getName(), channel.isActive());
  }

  private static SalesChannelResponse toResponse(SalesChannelView view) {
    return new SalesChannelResponse(view.getId(), view.getCode(), view.getName(), view.isActive());
  }
}
