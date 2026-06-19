package id.co.nativeapp.finance.gl.repository;

import id.co.nativeapp.finance.gl.domain.JournalLine;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the append-only {@link JournalLine} records. Persists lines that have
 * been validated and collected by the {@link JournalLine#debit} / {@link JournalLine#credit}
 * factories and the {@link id.co.nativeapp.finance.gl.domain.JournalEntry#balanced} invariant
 * check.
 *
 * <p>A thin data port: no business logic, no manual {@code WHERE company_id}. Tenant scoping comes
 * solely from the auto-applied RLS GUC (rule 5). Lines are saved as a batch by the writer after the
 * entry header is persisted.
 */
public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {}
