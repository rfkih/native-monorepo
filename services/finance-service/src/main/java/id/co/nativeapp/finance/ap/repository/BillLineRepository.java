package id.co.nativeapp.finance.ap.repository;

import id.co.nativeapp.finance.ap.domain.BillLine;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Write-path repository for {@link BillLine} rows (saved when a draft is created). RLS-scoped. */
public interface BillLineRepository extends JpaRepository<BillLine, UUID> {}
