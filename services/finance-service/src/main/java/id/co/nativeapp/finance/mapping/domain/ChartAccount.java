package id.co.nativeapp.finance.mapping.domain;

/**
 * A {@code chart_of_account} row — global reference data (the default chart seeded by Flyway V2).
 *
 * <p>Modelled as a plain immutable value record, not a JPA {@code @Entity}: the chart and the
 * mapping rules are <em>global configuration</em> (the same for every tenant in this slice), not a
 * company's tenant-scoped business data, so they are deliberately not {@code Auditable} and not
 * under RLS (the same call finance makes for the {@code processed_event} consumer-infra table). The
 * tenant business data — {@code ledger_posting} and {@code consolidated_pnl} — stays Auditable +
 * RLS. The resolver reads these rows via {@code JdbcTemplate}; GL mapping is data, never hardcoded
 * in Java (ENGINEERING-STANDARDS §7).
 *
 * @param accountCode the natural key the ledger's {@code gl_account_code} dimension references
 * @param name the human-readable account name
 * @param accountType the account class (REVENUE / EXPENSE / …)
 */
public record ChartAccount(String accountCode, String name, AccountType accountType) {}
