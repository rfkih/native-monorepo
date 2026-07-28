package id.co.nativeapp.finance.ar.repository;

import id.co.nativeapp.finance.ar.domain.InvoiceLine;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Write-path repository for {@link InvoiceLine} rows (saved when a draft is created). RLS-scoped.
 */
public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, UUID> {}
