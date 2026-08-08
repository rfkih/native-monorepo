package id.co.nativeapp.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that contributes the shared {@link OperatorContextProvider} (ADR 0049 P2) to
 * every service that depends on {@code libs/security}. Registered in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} alongside
 * {@link NativeSecurityAutoConfiguration}/{@link NativeProblemDetailAutoConfiguration}.
 *
 * <p>{@link OperatorContextProvider} cannot be a plain {@code @Component}: {@code libs/security}'s
 * package ({@code id.co.nativeapp.security}) sits outside every consuming service's {@code
 * id.co.nativeapp.<service>} component-scan root (rooted at that service's
 * {@code @SpringBootApplication} class), so a bare {@code @Component} here would silently never be
 * picked up by any consumer — a service depending on the constructor-injected bean would fail to
 * start with a {@code NoSuchBeanDefinitionException}. Auto-configuration is the mechanism {@code
 * libs/security} already uses for everything else it contributes (see {@link
 * NativeSecurityAutoConfiguration}), so this follows the same pattern.
 *
 * <p>Deliberately NOT gated on a servlet web application (code review S3): {@link
 * OperatorContextProvider} is now a hard constructor dependency of {@code SaleWriter}, so it must
 * be unconditionally resolvable — a non-web {@code @SpringBootTest} slice that loads {@code
 * SaleWriter} would otherwise fail with {@code NoSuchBeanDefinitionException}. The bean is harmless
 * everywhere: {@code current()} reads {@code RequestContextHolder}, which returns empty outside a
 * request. {@code @ConditionalOnMissingBean} still lets a service override it.
 */
@AutoConfiguration
public class OperatorContextAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public OperatorContextProvider operatorContextProvider() {
    return new OperatorContextProvider();
  }
}
