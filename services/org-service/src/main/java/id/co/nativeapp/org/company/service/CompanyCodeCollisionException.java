package id.co.nativeapp.org.company.service;

/**
 * Internal signal (ADR 0054): the freshly minted {@code company_code} collided with an existing one
 * on the {@code uq_company_company_code} UNIQUE index. Caught by {@link CompanyService} to mint a
 * fresh code and retry the tenant bootstrap in a new transaction (the collision marks the writer's
 * {@code REQUIRES_NEW} transaction rollback-only, so the retry cannot live inside it).
 *
 * <p>Never leaves the service layer — it is a control-flow signal, not an API-facing exception.
 */
class CompanyCodeCollisionException extends RuntimeException {

  CompanyCodeCollisionException() {
    super("company_code collision — retry with a fresh code");
  }
}
