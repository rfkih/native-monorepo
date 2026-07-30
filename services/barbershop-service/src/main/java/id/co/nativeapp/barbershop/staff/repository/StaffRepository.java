package id.co.nativeapp.barbershop.staff.repository;

import id.co.nativeapp.barbershop.staff.domain.Staff;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@link Staff} local staff read model.
 *
 * <p>A thin data port: derived queries only, no business logic, no manual {@code WHERE company_id}
 * — tenant scoping comes solely from the auto-applied RLS GUC (rule 5). RLS makes a cross-tenant
 * staff row invisible, so a vertical only ever sees its own tenant's staff.
 */
public interface StaffRepository extends JpaRepository<Staff, UUID> {}
