package id.co.nativeapp.finance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the observability infrastructure beans (ADR 0005: error-inbox + webhook alerting).
 *
 * <p>The {@link TransactionTemplate} named {@code errorInboxTransactionTemplate} is configured with
 * {@code PROPAGATION_REQUIRES_NEW} so the {@code error_log} upsert always commits in its own
 * transaction — even when the calling business transaction has been rolled back (e.g. after a DLT
 * recoverer fires because a consume failed). Without {@code REQUIRES_NEW} the upsert would enlist
 * in — and roll back with — the failed business tx, defeating the whole purpose of the error inbox.
 */
@Configuration
public class ObservabilityConfig {

  /**
   * A {@link TransactionTemplate} that always runs in a brand-new transaction ({@code
   * PROPAGATION_REQUIRES_NEW}), used exclusively by {@code ErrorInboxWriter} to persist error
   * records even when the surrounding business transaction has already been marked for rollback.
   */
  @Bean
  public TransactionTemplate errorInboxTransactionTemplate(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }
}
