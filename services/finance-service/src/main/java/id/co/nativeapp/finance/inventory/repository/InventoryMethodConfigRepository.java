package id.co.nativeapp.finance.inventory.repository;

import id.co.nativeapp.finance.inventory.domain.InventoryMethodConfig;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link InventoryMethodConfig} (ADR 0067 §5, Phase D4). No manual
 * {@code WHERE company_id} — RLS applies the tenant scope (rule 5), so {@code findAll()} sees only
 * this company's (at most one, {@code uq_inventory_method_config_company}) row. The write path
 * loads the whole entity to verify a replay's payload (the {@code CompanyOpeningBalanceRepository}
 * idiom).
 */
public interface InventoryMethodConfigRepository
    extends JpaRepository<InventoryMethodConfig, UUID> {}
