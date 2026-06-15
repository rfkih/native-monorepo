package id.co.nativeapp.entitlement.entitlement.domain;

/**
 * Thrown when a request references a {@code module_key} that does not exist in {@code
 * module_catalog}. It is an invalid-argument condition at the API edge — a client asked to
 * grant/revoke a module the platform does not offer — so the shared RFC-7807 advice maps it to a
 * {@code 400} (an {@link IllegalArgumentException}, like an unknown ISO-4217 currency code), never
 * a {@code 500}. The message is English server diagnostics; the frontend maps the stable problem
 * type to an i18n key (ENGINEERING-STANDARDS §1.2, HR-9).
 */
public class UnknownModuleException extends IllegalArgumentException {

  public UnknownModuleException(String moduleKey) {
    super("Unknown module_key '" + moduleKey + "': not present in module_catalog");
  }
}
