package id.co.nativeapp.finance.tax.service;

import java.util.UUID;

/**
 * The outcome of {@link TaxFilingWriter#file}: the filing id and whether this call FRESHLY filed
 * the return ({@code created == true}) or found an already-filed period and no-oped ({@code created
 * == false}). Lets the controller honour the idempotent-POST contract (ENGINEERING-STANDARDS §1.1):
 * a fresh file → {@code 201 Created}, an idempotent re-file → {@code 200 OK} with the existing
 * resource (mirroring {@code WithinCompanyCloseController}'s re-close → 200).
 */
public record FileReturnResult(UUID filingId, boolean created) {}
