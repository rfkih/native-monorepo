package id.co.nativeapp.restaurant.config;

import id.co.nativeapp.restaurant.selforderaccess.service.SelfOrderAccessReader;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers {@link SelfOrderTokenFilter} scoped ONLY to {@code /api/v1/self-order/**} (Phase 6, ADR
 * 0029) via an explicit {@link FilterRegistrationBean} url-pattern — unlike {@link DevTenantFilter}
 * (a plain {@code @Component}, auto-registered for every URL and self-restricting via {@code
 * shouldNotFilter}), this filter is deliberately NEVER invoked for any other path: the anonymous
 * self-order surface is the only place an unauthenticated caller is ever trusted with anything, and
 * scoping the registration itself (not just the filter's own logic) is the belt-and-suspenders
 * choice.
 *
 * <p>Runs at the highest precedence among application filters — before the security chain has any
 * chance to matter for this path (the path is separately declared {@code
 * native.security.public-paths} so Spring Security {@code permitAll}s it and {@code
 * libs.security.TenantBindingFilter} skips it entirely; see {@code application.yml}) — so this
 * filter is the FIRST and ONLY authentication step {@code /api/v1/self-order/**} sees.
 */
@Configuration
public class SelfOrderFilterConfig {

  private static final String URL_PATTERN = "/api/v1/self-order/*";

  @Bean
  public FilterRegistrationBean<SelfOrderTokenFilter> selfOrderTokenFilterRegistration(
      SelfOrderAccessReader reader) {
    FilterRegistrationBean<SelfOrderTokenFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new SelfOrderTokenFilter(reader));
    registration.addUrlPatterns(URL_PATTERN);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return registration;
  }

  /**
   * Body-size guard for the same anonymous surface, ordered BEFORE the token filter (a huge body
   * should be refused without even validating the token — cheapest rejection first). See {@link
   * SelfOrderBodySizeFilter}.
   */
  @Bean
  public FilterRegistrationBean<SelfOrderBodySizeFilter> selfOrderBodySizeFilterRegistration() {
    FilterRegistrationBean<SelfOrderBodySizeFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(new SelfOrderBodySizeFilter());
    registration.addUrlPatterns(URL_PATTERN);
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE - 1);
    return registration;
  }
}
