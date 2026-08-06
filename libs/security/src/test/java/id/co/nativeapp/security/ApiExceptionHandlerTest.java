package id.co.nativeapp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.errorinbox.ErrorInboxWriter.Recorded;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Unit proof for the traceable-error-reference feature: the catch-all {@code 500} handler ({@link
 * ApiExceptionHandler#handleUnexpected}) persists the fault to the error inbox and returns a
 * user-quotable {@code reference} (the W3C trace id), gated by the {@code persistHttpErrors}
 * constructor flag. The client-fault (400) handlers never touch the inbox, regardless of the flag.
 *
 * <p>Deliberately a plain unit test (no Spring context) — {@link ApiExceptionHandler} is a plain
 * class with two constructor collaborators, so it is exercised directly with a mocked {@link
 * ErrorInboxWriter} and a {@link MockHttpServletRequest}.
 */
class ApiExceptionHandlerTest {

  private final ErrorInboxWriter errorInboxWriter = mock(ErrorInboxWriter.class);

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<ErrorInboxWriter> providerOf(ErrorInboxWriter writer) {
    ObjectProvider<ErrorInboxWriter> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(writer);
    return provider;
  }

  private MockHttpServletRequest requestTo(String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRequestURI(uri);
    return request;
  }

  @Test
  void unexpectedExceptionPersistsToTheErrorInboxAndReturnsAReferenceWhenTheToggleIsOn() {
    when(errorInboxWriter.record(any(), anyString(), any(), anyString()))
        .thenReturn(new Recorded(1L, "fp", "redacted"));
    ApiExceptionHandler handler = new ApiExceptionHandler(providerOf(errorInboxWriter), true);
    HttpServletRequest request = requestTo("/api/v1/widgets");

    ProblemDetail problem = handler.handleUnexpected(new RuntimeException("boom"), request);

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    Object reference = problem.getProperties().get("reference");
    assertThat(reference).isInstanceOf(String.class);
    String referenceValue = (String) reference;
    assertThat(referenceValue).isNotBlank();
    // Back-compat: the pre-existing traceId property still carries the same value.
    assertThat(problem.getProperties().get("traceId")).isEqualTo(referenceValue);
    assertThat(problem.getDetail()).contains(referenceValue);

    ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> traceIdCaptor = ArgumentCaptor.forClass(String.class);
    verify(errorInboxWriter)
        .record(
            any(RuntimeException.class), sourceCaptor.capture(), isNull(), traceIdCaptor.capture());
    assertThat(sourceCaptor.getValue()).startsWith("http:").contains("/api/v1/widgets");
    assertThat(traceIdCaptor.getValue()).isEqualTo(referenceValue);
  }

  @Test
  void unexpectedExceptionDoesNotPersistWhenTheToggleIsOffButStillReturnsAReference() {
    ApiExceptionHandler handler = new ApiExceptionHandler(providerOf(errorInboxWriter), false);

    ProblemDetail problem =
        handler.handleUnexpected(new RuntimeException("boom"), requestTo("/api/v1/widgets"));

    Object reference = problem.getProperties().get("reference");
    assertThat(reference).isInstanceOf(String.class);
    assertThat((String) reference).isNotBlank();
    verifyNoInteractions(errorInboxWriter);
  }

  @Test
  void reusesAnExistingMdcTraceIdAsTheReferenceInsteadOfGeneratingANewOne() {
    MDC.put("traceId", "abc123traceid");
    when(errorInboxWriter.record(any(), anyString(), any(), anyString()))
        .thenReturn(new Recorded(1L, "fp", "redacted"));
    ApiExceptionHandler handler = new ApiExceptionHandler(providerOf(errorInboxWriter), true);

    ProblemDetail problem =
        handler.handleUnexpected(new RuntimeException("boom"), requestTo("/api/v1/widgets"));

    assertThat(problem.getProperties().get("reference")).isEqualTo("abc123traceid");
    verify(errorInboxWriter)
        .record(any(), anyString(), any(), org.mockito.ArgumentMatchers.eq("abc123traceid"));
  }

  @Test
  void aPersistenceFailureNeverChangesTheResponse() {
    when(errorInboxWriter.record(any(), anyString(), any(), anyString()))
        .thenThrow(new RuntimeException("db unreachable"));
    ApiExceptionHandler handler = new ApiExceptionHandler(providerOf(errorInboxWriter), true);

    ProblemDetail problem =
        handler.handleUnexpected(new RuntimeException("boom"), requestTo("/api/v1/widgets"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    Object reference = problem.getProperties().get("reference");
    assertThat(reference).isInstanceOf(String.class);
    assertThat((String) reference).isNotBlank();
  }

  @Test
  void aMissingErrorInboxBeanIsToleratedWhenTheToggleIsOn() {
    ObjectProvider<ErrorInboxWriter> emptyProvider = providerOf(null);
    ApiExceptionHandler handler = new ApiExceptionHandler(emptyProvider, true);

    ProblemDetail problem =
        handler.handleUnexpected(new RuntimeException("boom"), requestTo("/api/v1/widgets"));

    assertThat(problem.getProperties().get("reference")).isNotNull();
  }

  @Test
  void theNoArgConstructorDegradesToNoPersistence() {
    ApiExceptionHandler handler = new ApiExceptionHandler();

    ProblemDetail problem =
        handler.handleUnexpected(new RuntimeException("boom"), requestTo("/api/v1/widgets"));

    assertThat(problem.getProperties().get("reference")).isNotNull();
  }

  @Test
  void validationFailuresNeverInvokeTheErrorInboxRegardlessOfTheToggle() {
    ApiExceptionHandler handler = new ApiExceptionHandler(providerOf(errorInboxWriter), true);
    MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
    BindingResult bindingResult = mock(BindingResult.class);
    when(ex.getBindingResult()).thenReturn(bindingResult);
    when(bindingResult.getFieldErrors()).thenReturn(List.of());

    ProblemDetail problem = handler.handleInvalidBody(ex, requestTo("/api/v1/widgets"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    verifyNoInteractions(errorInboxWriter);
  }

  @Test
  void illegalArgumentFailuresNeverInvokeTheErrorInboxRegardlessOfTheToggle() {
    ApiExceptionHandler handler = new ApiExceptionHandler(providerOf(errorInboxWriter), true);

    ProblemDetail problem =
        handler.handleIllegalArgument(
            new IllegalArgumentException("bad currency"), requestTo("/api/v1/sales"));

    assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    verifyNoInteractions(errorInboxWriter);
  }
}
