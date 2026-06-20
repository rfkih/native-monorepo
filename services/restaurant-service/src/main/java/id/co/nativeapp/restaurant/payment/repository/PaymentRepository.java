package id.co.nativeapp.restaurant.payment.repository;

import id.co.nativeapp.restaurant.payment.domain.Payment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for the {@code payment} aggregate. Writes go through the inherited {@code
 * saveAndFlush} (the whole aggregate is loaded on the write path); any future read path will add a
 * native-query projection (CODE-STRUCTURE §3.3), not a {@code SELECT *} of this Auditable entity.
 */
public interface PaymentRepository extends JpaRepository<Payment, UUID> {}
