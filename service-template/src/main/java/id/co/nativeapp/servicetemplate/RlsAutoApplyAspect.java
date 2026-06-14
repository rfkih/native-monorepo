package id.co.nativeapp.servicetemplate;

import id.co.nativeapp.tenant.RlsTransactionSynchronizer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * THE auto-applied RLS guarantee (the M0.2 item deferred by both reviewers).
 *
 * <p>This aspect wraps every {@code @Transactional} method so the PostgreSQL
 * tenant GUC ({@code app.current_tenant}) is set on the transaction's connection
 * <em>automatically</em>, on every transaction, without any query or service
 * method ever calling {@link RlsTransactionSynchronizer#applyToCurrentTransaction()}
 * by hand. A developer who writes a new repository method and simply forgets the
 * tenant is still fully protected: with the GUC set, the row-level-security
 * policy keyed to {@code current_setting('app.current_tenant', true)} restricts
 * reads and writes to the bound company; if (defensively) the GUC were ever left
 * unset, the policy matches nothing and the query fails closed.
 *
 * <p><strong>Ordering.</strong> The pointcut matches both Spring's
 * {@code @Transactional} and JTA's {@code jakarta.transaction.Transactional}. The
 * aspect runs with {@code LOWEST_PRECEDENCE}, so it sits <em>inside</em> Spring's
 * transaction interceptor (which runs first, at a higher precedence): by the time
 * this advice executes, the transaction has begun and a connection is bound, so
 * {@code SET LOCAL} lands on the same connection the method's queries use and is
 * cleared by PostgreSQL at commit/rollback.
 *
 * <p>It delegates to {@link RlsTransactionSynchronizer#register()}, which applies
 * the tenant now and re-applies it across transaction suspend/resume so the GUC
 * stays correct on a shared pooled connection. When no tenant is bound or no
 * transaction is active the synchronizer is a safe no-op.
 */
@Aspect
@Component
public class RlsAutoApplyAspect implements Ordered {

    private final RlsTransactionSynchronizer synchronizer;

    public RlsAutoApplyAspect(RlsTransactionSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
    }

    /**
     * Around every Spring- or JTA-{@code @Transactional} method (class- or
     * method-level), push the bound tenant onto the active transaction before the
     * method body runs.
     */
    @Around(
            "@annotation(org.springframework.transaction.annotation.Transactional)"
                    + " || @within(org.springframework.transaction.annotation.Transactional)"
                    + " || @annotation(jakarta.transaction.Transactional)"
                    + " || @within(jakarta.transaction.Transactional)")
    public Object applyTenant(ProceedingJoinPoint pjp) throws Throwable {
        synchronizer.register();
        return pjp.proceed();
    }

    /**
     * Lowest precedence so this advice runs <em>after</em> Spring's transaction
     * interceptor has opened the transaction — the connection must already be
     * bound for {@code SET LOCAL} to take effect. Spring's {@code @Transactional}
     * advisor is pinned to a strictly higher precedence in
     * {@link PersistenceConfig#TX_ADVISOR_ORDER}, so "this aspect runs inside the
     * transaction" is guaranteed by contract, not by an undefined tie-break.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
