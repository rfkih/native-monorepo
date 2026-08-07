package id.co.nativeapp.payment.settings.projection;

import java.util.UUID;

/**
 * Read model for a {@code payment_settings} row (CODE-STRUCTURE §3.3): the owner list AND the
 * effective-mode resolution are served from this projection — it deliberately NEVER selects the
 * encrypted credential columns (rule 6); {@code gateway_connected} is derived as {@code
 * server_key_encrypted IS NOT NULL} in SQL so presence is knowable without the ciphertext ever
 * leaving the database. Snake_case aliases map to these camelCase getters.
 */
public interface SettingsView {

  UUID getId();

  UUID getOutletId();

  String getMode();

  boolean getHasStaticImage();

  Integer getStaticQrByteSize();

  String getStaticQrSha256();

  String getProvider();

  String getProviderEnvironment();

  String getServerKeyLast4();

  boolean getGatewayConnected();
}
