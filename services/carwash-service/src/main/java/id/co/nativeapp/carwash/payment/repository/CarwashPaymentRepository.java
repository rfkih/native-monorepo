package id.co.nativeapp.carwash.payment.repository;

import id.co.nativeapp.carwash.payment.domain.CarwashPayment;
import id.co.nativeapp.carwash.payment.projection.CarwashPaymentView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link CarwashPayment}. Carries no manual {@code WHERE company_id}:
 * every method runs inside a {@code @Transactional}, so {@link RlsAutoApplyAspect} sets {@code
 * app.current_tenant} automatically (rule 5).
 *
 * <p>The write path (capture) uses the inherited {@code findById}/{@code save} — a ticket has AT
 * MOST one payment in this phase (no split tenders), so the capture writer first resolves the
 * payment's own id via {@link #findViewByTicketId} (a read-path projection), then loads the full
 * aggregate by that id to mutate it (CLAUDE.md convention: the full entity is loaded only via
 * {@code findById}/{@code save}).
 */
public interface CarwashPaymentRepository extends JpaRepository<CarwashPayment, UUID> {

  /**
   * The single payment for a ticket, projected — used both to assemble a {@code TicketResponse} and
   * to resolve the payment id for a write-path {@code findById} reload before capture.
   */
  @Query(
      value =
          """
          SELECT id AS id, ticket_id AS ticket_id, business_id AS business_id,
                 tender_type AS tender_type, status AS status,
                 amount_minor AS amount_minor, currency AS currency,
                 tendered_minor AS tendered_minor, change_minor AS change_minor,
                 provider_ref AS provider_ref, provider_pending AS provider_pending
            FROM carwash_payment
           WHERE ticket_id = :ticketId
          """,
      nativeQuery = true)
  Optional<CarwashPaymentView> findViewByTicketId(@Param("ticketId") UUID ticketId);
}
