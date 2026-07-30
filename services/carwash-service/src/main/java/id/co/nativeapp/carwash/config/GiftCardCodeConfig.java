package id.co.nativeapp.carwash.config;

import id.co.nativeapp.carwash.giftcard.domain.GiftCardCodeGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the keyed gift-card code derivation (security review W-4): binds {@link
 * GiftCardProperties} (the externalized, fleet-wide {@code NATIVE_GIFTCARD_CODE_KEY} + the {@code
 * NATIVE_GIFTCARD_MAX_MINT_MINOR} mint ceiling, security review W-3) and builds the singleton
 * {@link GiftCardCodeGenerator} bean {@code giftcard.service.GiftCardSaleWriter} delegates to.
 */
@Configuration
@EnableConfigurationProperties(GiftCardProperties.class)
public class GiftCardCodeConfig {

  /**
   * The process-wide gift-card code generator, keyed from the externalized {@code
   * native.giftcard.code-key}. A single instance is shared (it is stateless apart from the
   * immutable key).
   */
  @Bean
  public GiftCardCodeGenerator giftCardCodeGenerator(GiftCardProperties properties) {
    return GiftCardCodeGenerator.fromBase64Key(properties.codeKey());
  }
}
