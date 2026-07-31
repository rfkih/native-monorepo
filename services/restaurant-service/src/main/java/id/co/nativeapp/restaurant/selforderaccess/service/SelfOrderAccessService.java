package id.co.nativeapp.restaurant.selforderaccess.service;

import id.co.nativeapp.restaurant.config.ActorRolesProvider;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderAccess;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderAccessForbiddenException;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderTokenCodec;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderTokenPayload;
import id.co.nativeapp.restaurant.selforderaccess.dto.SelfOrderAccessResponse;
import id.co.nativeapp.restaurant.selforderaccess.dto.SelfOrderTableToken;
import id.co.nativeapp.restaurant.table.dto.TableResponse;
import id.co.nativeapp.restaurant.table.service.TableReader;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the owner/manager-only self-order QR management surface (Phase 6, ADR 0029): {@code
 * GET /api/v1/self-order-access?outletId=} (mint the current token set, provisioning a secret on
 * first use) and {@code POST /api/v1/self-order-access/rotate} (revoke every printed QR for an
 * outlet at once).
 *
 * <p><strong>Owner/manager-only, server-side.</strong> Both operations mint tokens that grant
 * order-creating access to the outlet, so both are gated exactly like {@code
 * promotion.service.PromotionAdminService}'s writes / {@code promotion.service.ManualDiscountGuard}
 * — the SAME empty-roles-pass semantics (a headerless call is the gateway-less dev recipe / a
 * direct service-layer test, never a real cashier token).
 *
 * <p>Not itself {@code @Transactional} — transactional units of work live in {@link
 * SelfOrderAccessWriter} / {@link SelfOrderAccessReader} so the proxy and the RLS aspect engage.
 */
@Service
public class SelfOrderAccessService {

  private final SelfOrderAccessReader reader;
  private final SelfOrderAccessWriter writer;
  private final TableReader tableReader;
  private final ActorRolesProvider actorRoles;

  public SelfOrderAccessService(
      SelfOrderAccessReader reader,
      SelfOrderAccessWriter writer,
      TableReader tableReader,
      ActorRolesProvider actorRoles) {
    this.reader = reader;
    this.writer = writer;
    this.tableReader = tableReader;
    this.actorRoles = actorRoles;
  }

  /**
   * Returns the outlet's current token set, provisioning its first {@code self_order_access} row on
   * demand if none exists yet.
   */
  public SelfOrderAccessResponse getActive(UUID outletId) {
    requireOwnerOrManager();
    SelfOrderAccess access =
        reader.findActive(outletId).orElseGet(() -> ensureActiveConvergingOnRace(outletId));
    return mintTokens(access, outletId);
  }

  /**
   * First-provision the outlet's access row, converging on the winner if a concurrent call minted
   * first (review N-1/O-1). This orchestration is deliberately in the NON-transactional service, not
   * inside {@link SelfOrderAccessWriter#ensureActive}: the losing mint's unique-index violation
   * aborts THAT method's transaction, so the recovering re-read must run afterwards in a FRESH
   * transaction ({@link SelfOrderAccessReader#findActive}, its own {@code @Transactional}) — a catch
   * inside the writer would re-read on the already-poisoned transaction and 500 anew. Mirrors the
   * proven {@code SelfOrderService.createOrder} / {@code parkSelfOrder} + fresh-tx re-read pattern.
   */
  private SelfOrderAccess ensureActiveConvergingOnRace(UUID outletId) {
    try {
      return writer.ensureActive(outletId);
    } catch (DataIntegrityViolationException raceLost) {
      return reader.findActive(outletId).orElseThrow(() -> raceLost);
    }
  }

  /**
   * Retires the current row and mints a fresh one — every previously-printed QR stops verifying.
   */
  public SelfOrderAccessResponse rotate(UUID outletId) {
    requireOwnerOrManager();
    SelfOrderAccess access = writer.rotate(outletId);
    return mintTokens(access, outletId);
  }

  private SelfOrderAccessResponse mintTokens(SelfOrderAccess access, UUID outletId) {
    String companyId = TenantContext.require().companyId();
    byte[] secretBytes = Base64.getDecoder().decode(access.getSecretPlaintext());

    String kioskToken =
        SelfOrderTokenCodec.encode(
            new SelfOrderTokenPayload(
                SelfOrderTokenPayload.CURRENT_VERSION,
                access.getKid(),
                companyId,
                outletId,
                outletId,
                null),
            secretBytes);

    List<SelfOrderTableToken> tableTokens =
        tableReader.listByBusiness(outletId).stream()
            .filter(TableResponse::active)
            .map(
                table ->
                    new SelfOrderTableToken(
                        table.tableId(),
                        table.label(),
                        SelfOrderTokenCodec.encode(
                            new SelfOrderTokenPayload(
                                SelfOrderTokenPayload.CURRENT_VERSION,
                                access.getKid(),
                                companyId,
                                outletId,
                                outletId,
                                table.label()),
                            secretBytes)))
            .toList();

    return new SelfOrderAccessResponse(access.getKid(), kioskToken, tableTokens);
  }

  /**
   * Empty-roles-pass semantics (see class javadoc): an EMPTY role set is let through; a non-empty
   * set that is neither {@code owner} nor {@code manager} is denied.
   */
  private void requireOwnerOrManager() {
    if (!actorRoles.currentRoles().isEmpty() && !actorRoles.isOwnerOrManager()) {
      throw new SelfOrderAccessForbiddenException();
    }
  }
}
