package id.co.nativeapp.mediastorage;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the {@link MediaStorage} bean (the libs/tenant idiom: registered in {@code
 * META-INF/spring/…AutoConfiguration.imports}, so a service gets a ready client by depending on
 * this module and setting {@code native.media.*} — no copied config class per service).
 *
 * <p>Gated on {@code native.media.endpoint} being present: a module that pulls this library
 * transitively without configuring an object store simply gets no bean (and a service that DOES
 * inject {@link MediaStorage} without config still fails fast at startup with a missing-bean error
 * naming the injection point).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "native.media", name = "endpoint")
@EnableConfigurationProperties(MediaStorageProperties.class)
public class MediaStorageAutoConfiguration {

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  MediaStorage mediaStorage(MediaStorageProperties properties) {
    return new S3MediaStorage(properties);
  }
}
