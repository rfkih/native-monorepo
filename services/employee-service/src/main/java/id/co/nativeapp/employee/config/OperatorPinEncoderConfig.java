package id.co.nativeapp.employee.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Wires the Argon2id encoder the ADR 0049 P1 operator PIN is hashed with — a one-way KDF, distinct
 * from the reversible {@code PiiCipher} (rule 6): a PIN is verified, never decrypted back to
 * plaintext.
 *
 * <p>{@link Argon2PasswordEncoder#defaultsForSpringSecurity_v5_8()} picks Spring Security's current
 * recommended Argon2id parameters (memory/iterations/parallelism) rather than hand-tuning them
 * here, so the cost factor tracks the framework's own guidance as it evolves. The Argon2
 * implementation delegates to BouncyCastle (the JCE provider on the classpath — see {@code
 * build.gradle.kts}), which spring-security-crypto itself does not bundle.
 */
@Configuration
public class OperatorPinEncoderConfig {

  /** The process-wide operator-PIN encoder: {@code .encode(pin)} / {@code .matches(pin, hash)}. */
  @Bean
  public PasswordEncoder operatorPinEncoder() {
    return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }
}
