package id.co.nativeapp.payment.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link PspWebhookBodySizeFilter} scoped to the anonymous webhook surface only (the
 * {@code SelfOrderFilterConfig} belt-and-suspenders idiom): the size guard runs FIRST, before any
 * parsing, and never touches an authenticated route.
 */
@Configuration
public class PspWebhookFilterConfig {

  @Bean
  public FilterRegistrationBean<PspWebhookBodySizeFilter> pspWebhookBodySizeFilter() {
    FilterRegistrationBean<PspWebhookBodySizeFilter> registration =
        new FilterRegistrationBean<>(new PspWebhookBodySizeFilter());
    registration.addUrlPatterns("/api/v1/psp-webhooks/*");
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }
}
