package id.co.nativeapp.restaurant.selforderaccess.service;

import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderAccess;
import id.co.nativeapp.restaurant.selforderaccess.repository.SelfOrderAccessRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write units of work for {@code self_order_access}: first-time
 * provisioning and rotation (Phase 6, ADR 0029).
 *
 * <p>A distinct bean from {@link SelfOrderAccessService} so the Spring proxy + auto-RLS aspect
 * engage (rule 5) — same reasoning as every other {@code *Writer} in this fleet.
 *
 * <p><strong>{@code kid} / secret generation.</strong> {@code kid} is 8 random bytes hex-encoded (16
 * characters, fitting the {@code VARCHAR(16)} column exactly) — it is a compact correlation id, not
 * itself a secret. The secret is 32 random bytes (AES-256/HMAC-SHA256-key-length), base64-encoded
 * before being handed to the entity, which encrypts it at rest via the {@code
 * config.SelfOrderSecretConverter} JPA converter. Both are drawn from a single {@link SecureRandom}
 * instance.
 */
@Component
public class SelfOrderAccessWriter {

  private static final int KID_BYTES = 8;
  private static final int SECRET_BYTES = 32;

  private final SelfOrderAccessRepository repository;
  private final SecureRandom random = new SecureRandom();

  public SelfOrderAccessWriter(SelfOrderAccessRepository repository) {
    this.repository = repository;
  }

  /**
   * Returns the outlet's current ACTIVE row, minting a fresh one if none exists yet (first-time
   * provisioning — lets the management {@code GET} endpoint bootstrap an outlet's QR set without
   * requiring an explicit {@code rotate} call first).
   */
  @Transactional
  public SelfOrderAccess ensureActive(UUID outletId) {
    String companyId = TenantContext.require().companyId();
    return repository
        .findByOutletIdAndStatus(outletId, SelfOrderAccess.STATUS_ACTIVE)
        .orElseGet(() -> mint(outletId, companyId));
  }

  /**
   * Retires the outlet's current ACTIVE row (a no-op if none exists — a never-provisioned outlet)
   * and mints a fresh ACTIVE row, invalidating every token signed by the retired secret at once.
   */
  @Transactional
  public SelfOrderAccess rotate(UUID outletId) {
    String companyId = TenantContext.require().companyId();
    repository
        .findByOutletIdAndStatus(outletId, SelfOrderAccess.STATUS_ACTIVE)
        .ifPresent(
            existing -> {
              existing.retire();
              repository.saveAndFlush(existing);
            });
    return mint(outletId, companyId);
  }

  private SelfOrderAccess mint(UUID outletId, String companyId) {
    SelfOrderAccess access = new SelfOrderAccess(outletId, newKid(), newSecretBase64());
    access.setCompanyId(companyId);
    return repository.saveAndFlush(access);
  }

  private String newKid() {
    byte[] bytes = new byte[KID_BYTES];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private String newSecretBase64() {
    byte[] bytes = new byte[SECRET_BYTES];
    random.nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
