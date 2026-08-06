package id.co.nativeapp.security;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Auto-configuration that contributes the single SHARED RFC 7807 {@link ApiExceptionHandler} advice
 * (#12) to every service that depends on {@code libs/security}. Registered in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} alongside
 * {@link NativeSecurityAutoConfiguration}, so a service inherits ONE compliant {@code
 * ProblemDetail} advice with no copied {@code config/ApiExceptionHandler}.
 *
 * <p>It is gated on a servlet web application that has the {@code @RestControllerAdvice} machinery
 * on the classpath (every business service does; the non-web modules do not), and the bean is
 * {@code @ConditionalOnMissingBean(ApiExceptionHandler.class)} so a service can still replace the
 * shared advice wholesale if it ever needs to. A service that only needs to ADD a handler for its
 * own exception type (org-service's {@code TenantAccessDeniedException}, finance-service's {@code
 * ConstraintViolationException}) simply declares an additional {@code @RestControllerAdvice} — it
 * does not replace this one, and Spring resolves the most specific handler across both.
 *
 * <p>Also wires the traceable-error-reference feature: an {@link ObjectProvider} for the (possibly
 * absent, e.g. no {@link ErrorInboxWriter} bean on a service that doesn't consume Kafka/JDBC)
 * {@link ErrorInboxWriter}, and the {@code native.error-inbox.persist-http-errors} toggle (default
 * {@code true}) that gates whether {@link ApiExceptionHandler#handleUnexpected} persists a 500 to
 * the {@code error_log} ops table.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RestControllerAdvice.class)
public class NativeProblemDetailAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ApiExceptionHandler nativeApiExceptionHandler(
      ObjectProvider<ErrorInboxWriter> errorInbox,
      @Value("${native.error-inbox.persist-http-errors:true}") boolean persistHttpErrors) {
    return new ApiExceptionHandler(errorInbox, persistHttpErrors);
  }
}
