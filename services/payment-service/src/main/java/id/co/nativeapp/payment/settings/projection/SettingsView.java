package id.co.nativeapp.payment.settings.projection;

import java.util.UUID;

/**
 * Read model for a {@code payment_settings} row (CODE-STRUCTURE §3.3): the owner list AND the
 * effective-mode resolution are served from this projection — it deliberately NEVER selects the
 * encrypted credential columns (rule 6). Presence is exposed as booleans derived in SQL ({@code
 * *_server_key_encrypted IS NOT NULL}) so the ciphertext never leaves the database, and the only
 * readable trace is each environment's {@code *_last4}. Snake_case aliases map to these camelCase
 * getters. {@code orgUnitId} is {@code null} for the company row, or an outlet/division id (ADR
 * 0045 amendment, V4) otherwise.
 *
 * <p>Per-environment credentials (V6): {@code providerEnvironment} is the ACTIVE slot; {@code
 * gatewayConnected} is whether THAT active slot holds a key, while {@code sandboxConnected}/{@code
 * productionConnected} + the two {@code *Last4} expose each slot independently for the owner UI.
 */
public interface SettingsView {

  UUID getId();

  UUID getOrgUnitId();

  String getMode();

  boolean getHasStaticImage();

  Integer getStaticQrByteSize();

  String getStaticQrSha256();

  String getProvider();

  /** The ACTIVE environment (SANDBOX/PRODUCTION) the till + webhook use, or {@code null}. */
  String getProviderEnvironment();

  String getSandboxServerKeyLast4();

  String getProductionServerKeyLast4();

  boolean getSandboxConnected();

  boolean getProductionConnected();

  /** Whether the ACTIVE environment's slot holds a server key (the till's GATEWAY precondition). */
  boolean getGatewayConnected();
}
