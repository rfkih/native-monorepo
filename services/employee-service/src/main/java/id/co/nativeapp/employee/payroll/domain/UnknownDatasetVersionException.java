package id.co.nativeapp.employee.payroll.domain;

/**
 * {@code POST /api/v1/payroll-setup/seed-official} named a {@code datasetVersion} with no matching
 * classpath resource under {@code statutory-datasets/}. Mapped to {@code 404 Not Found} — the
 * requested dataset simply does not exist, distinct from a malformed dataset (which would fail at
 * classpath-load time / build time, never reach a running service).
 */
public class UnknownDatasetVersionException extends RuntimeException {

  public UnknownDatasetVersionException(String datasetVersion) {
    super("Unknown statutory dataset version '" + datasetVersion + "'");
  }
}
