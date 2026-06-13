package id.co.nativeapp.tenant;

import java.sql.SQLException;
import javax.sql.DataSource;
import java.util.Objects;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bridges {@link TenantContext} to {@link RlsConnectionInitializer} within Spring
 * transactions: when invoked for an active transaction it applies
 * {@code SET LOCAL app.current_tenant} on that transaction's connection so
 * PostgreSQL row-level security keys on the bound tenant.
 *
 * <p>Because {@code SET LOCAL} is transaction-scoped, PostgreSQL clears the
 * setting automatically at commit/rollback — there is no explicit teardown to
 * register, and the next unit of work to borrow the pooled connection starts
 * with the setting unset (its RLS policies then return no rows, failing closed).
 *
 * <p>Two integration styles are supported:
 * <ul>
 *   <li>{@link #applyToCurrentTransaction()} — call once after the transaction
 *       has begun (e.g. from an aspect, a JPA repository hook, or directly at the
 *       top of a service method) to push the tenant onto the live connection; and</li>
 *   <li>{@link #register()} — register a {@link TransactionSynchronization} so the
 *       setting is (re)applied around suspend/resume, keeping the GUC correct if a
 *       nested or resumed transaction reuses the connection.</li>
 * </ul>
 *
 * <p>This component is intentionally a thin, framework-aware adapter; all the SQL
 * lives in {@link RlsConnectionInitializer}, which is plain-JDBC and unit-testable
 * without Spring.
 */
public class RlsTransactionSynchronizer {

    private final DataSource dataSource;
    private final RlsConnectionInitializer initializer;

    public RlsTransactionSynchronizer(DataSource dataSource, RlsConnectionInitializer initializer) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.initializer = Objects.requireNonNull(initializer, "initializer");
    }

    /**
     * Applies {@code SET LOCAL app.current_tenant} on the connection bound to the
     * active transaction, using the tenant from {@link TenantContext}.
     *
     * <p>No-op (returns {@code false}) when there is no active transaction or no
     * bound tenant — failing safe: with the GUC unset, RLS policies that read
     * {@code current_setting('app.current_tenant', true)} match nothing, so no
     * row leaks.
     *
     * @return {@code true} if the setting was applied; {@code false} if skipped
     * @throws IllegalStateException if applying the setting fails
     */
    public boolean applyToCurrentTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return false;
        }
        var tenant = TenantContext.current();
        if (tenant.isEmpty()) {
            return false;
        }
        // DataSourceUtils returns the *transaction-bound* connection, not a fresh
        // one from the pool, so SET LOCAL lands on the same connection the
        // subsequent queries use.
        var connection = DataSourceUtils.getConnection(dataSource);
        try {
            initializer.applyTenant(connection, tenant.get().companyId());
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Failed to set RLS tenant on the current transaction connection", e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /**
     * Applies the tenant now (if possible) and registers a
     * {@link TransactionSynchronization} that re-applies it after the transaction
     * resumes, so the GUC stays correct across suspend/resume on a shared
     * connection. Safe to call with no active transaction (it simply does
     * nothing in that case).
     */
    public void register() {
        applyToCurrentTransaction();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void resume() {
                    applyToCurrentTransaction();
                }
            });
        }
    }
}
