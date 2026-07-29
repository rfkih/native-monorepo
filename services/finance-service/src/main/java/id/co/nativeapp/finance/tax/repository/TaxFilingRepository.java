package id.co.nativeapp.finance.tax.repository;

import id.co.nativeapp.finance.tax.domain.TaxFiling;
import id.co.nativeapp.finance.tax.projection.EfakturLineView;
import id.co.nativeapp.finance.tax.projection.TaxFilingView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link TaxFiling} aggregate (Phase 4 Tax / PPN, ADR 0017).
 *
 * <p>A thin data port: no manual {@code WHERE company_id} — RLS scopes every read/write to the bound
 * company (rule 5). The entity-returning {@link #findByPeriodAndTaxType} is the write-path
 * idempotency probe (a re-file finds the row and no-ops); the read paths (detail, history, the VAT
 * report's filing-state lookup) use native + projection queries, never {@code SELECT *} of the
 * entity.
 */
public interface TaxFilingRepository extends JpaRepository<TaxFiling, UUID> {

  /**
   * The filing row for a {@code (period, tax_type)} within the bound company, if it has already
   * filed — the WRITE-path idempotency probe (loads the aggregate; a re-file returns its id).
   */
  Optional<TaxFiling> findByPeriodAndTaxType(String period, String taxType);

  /** One filing by id, projected — the READ (detail) path. */
  @Query(
      value =
          """
          SELECT tf.id                  AS id,
                 tf.period              AS period,
                 tf.tax_type            AS tax_type,
                 tf.status              AS status,
                 tf.currency            AS currency,
                 tf.output_vat_minor    AS output_vat_minor,
                 tf.input_vat_minor     AS input_vat_minor,
                 tf.net_minor           AS net_minor,
                 tf.net_direction       AS net_direction,
                 tf.filing_entry_id     AS filing_entry_id,
                 tf.settlement_entry_id AS settlement_entry_id,
                 tf.filed_at            AS filed_at,
                 tf.settled_at          AS settled_at
            FROM tax_filing tf
           WHERE tf.id = :id
          """,
      nativeQuery = true)
  Optional<TaxFilingView> findViewById(@Param("id") UUID id);

  /** One filing by {@code (period, tax_type)}, projected — the READ (VAT-report filing-state) path. */
  @Query(
      value =
          """
          SELECT tf.id                  AS id,
                 tf.period              AS period,
                 tf.tax_type            AS tax_type,
                 tf.status              AS status,
                 tf.currency            AS currency,
                 tf.output_vat_minor    AS output_vat_minor,
                 tf.input_vat_minor     AS input_vat_minor,
                 tf.net_minor           AS net_minor,
                 tf.net_direction       AS net_direction,
                 tf.filing_entry_id     AS filing_entry_id,
                 tf.settlement_entry_id AS settlement_entry_id,
                 tf.filed_at            AS filed_at,
                 tf.settled_at          AS settled_at
            FROM tax_filing tf
           WHERE tf.period = :period AND tf.tax_type = :taxType
          """,
      nativeQuery = true)
  Optional<TaxFilingView> findViewByPeriodAndTaxType(
      @Param("period") String period, @Param("taxType") String taxType);

  /**
   * The filing history for the bound company, projected, most-recent period first. No {@code WHERE
   * company_id} — the result set is constrained solely by the auto-applied RLS policy (rule 5).
   * Bounded {@code LIMIT} as an interim guard (mirrors the AR/AP list readers).
   */
  @Query(
      value =
          """
          SELECT tf.id                  AS id,
                 tf.period              AS period,
                 tf.tax_type            AS tax_type,
                 tf.status              AS status,
                 tf.currency            AS currency,
                 tf.output_vat_minor    AS output_vat_minor,
                 tf.input_vat_minor     AS input_vat_minor,
                 tf.net_minor           AS net_minor,
                 tf.net_direction       AS net_direction,
                 tf.filing_entry_id     AS filing_entry_id,
                 tf.settlement_entry_id AS settlement_entry_id,
                 tf.filed_at            AS filed_at,
                 tf.settled_at          AS settled_at
            FROM tax_filing tf
           ORDER BY tf.period DESC, tf.id
           LIMIT 500
          """,
      nativeQuery = true)
  List<TaxFilingView> findAllViews();

  /**
   * The output tax invoices issued in {@code period} that carry output VAT — the e-Faktur export
   * (Phase 4). A native join over {@code invoice} + {@code customer} (a SQL table reference, NOT a
   * Java dependency on the {@code ar} feature), RLS-scoped, bounded {@code LIMIT}. Issued /
   * partially-paid / paid invoices with a positive tax, whose issue month is {@code period}.
   */
  @Query(
      value =
          """
          SELECT i.invoice_number AS invoice_number,
                 i.issue_date     AS issue_date,
                 c.name           AS customer_name,
                 c.tax_id         AS customer_tax_id,
                 i.subtotal_minor AS dpp_minor,
                 i.tax_minor      AS ppn_minor,
                 i.total_minor    AS total_minor,
                 i.currency       AS currency
            FROM invoice i
            JOIN customer c ON c.id = i.customer_id
           WHERE i.status IN ('ISSUED', 'PARTIALLY_PAID', 'PAID')
             AND i.tax_minor > 0
             AND to_char(i.issue_date, 'YYYY-MM') = :period
           ORDER BY i.issue_date, i.invoice_number
           LIMIT 5000
          """,
      nativeQuery = true)
  List<EfakturLineView> findEfakturLines(@Param("period") String period);

  /**
   * A DETERMINISTIC per-{@code (company, period, tax_type)} transaction-scoped advisory lock that
   * exists regardless of whether a {@code tax_filing} row exists yet, taken at the TOP of the file
   * flow BEFORE the {@link #findByPeriodAndTaxType} idempotency probe. It serializes two concurrent
   * file attempts for the same key: without it both could pass the "no row yet" probe, both post a
   * netting entry, and one would win the {@code uq_tax_filing} UNIQUE insert while the other died on
   * a unique-violation 500. With the lock the second attempt blocks until the first commits, then
   * finds the row and returns the clean idempotent no-op. The UNIQUE constraint is the durable
   * backstop; the lock makes concurrency graceful (copied verbatim from {@code
   * WithinCompanyCloseRepository#lockPeriod}). Returns {@code true} so Spring Data can bind the void
   * {@code pg_advisory_xact_lock} as a scalar result.
   */
  @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key)::bigint) IS NULL", nativeQuery = true)
  boolean lockPeriod(@Param("key") String key);
}
