package id.co.nativeapp.payment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the externalized PSP-integration knobs ({@link PaymentProperties} — webhook callback origin
 * + Midtrans base URLs/timeouts/expiry). Validated at startup (fail fast).
 */
@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PspConfig {}
