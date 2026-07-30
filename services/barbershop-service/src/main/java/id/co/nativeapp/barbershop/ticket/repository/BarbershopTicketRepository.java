package id.co.nativeapp.barbershop.ticket.repository;

import id.co.nativeapp.barbershop.ticket.domain.BarbershopTicket;
import id.co.nativeapp.barbershop.ticket.projection.TicketView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link BarbershopTicket}. Carries no manual {@code WHERE company_id}:
 * every method runs inside a {@code @Transactional}, so {@link RlsAutoApplyAspect} sets {@code
 * app.current_tenant} automatically and the RLS policy restricts results to the bound company (rule
 * 5). Read paths use a native query + projection; the write path (checkout/capture) uses the
 * inherited {@code findById}/{@code save}.
 */
public interface BarbershopTicketRepository extends JpaRepository<BarbershopTicket, UUID> {

  /**
   * Finds an existing ticket by its client idempotency key within the bound tenant (RLS-scoped),
   * joined to {@code staff_profile} for the barber's display label. Used for the idempotency
   * fast-path short-circuit and the conflict-recovery re-read (mirrors carwash's {@code
   * CarwashTicketRepository#findViewByIdempotencyKey}).
   */
  @Query(
      value =
          """
          SELECT t.id                        AS id,
                 t.business_id                AS business_id,
                 t.chair                      AS chair,
                 t.staff_profile_id            AS staff_profile_id,
                 sp.display_label              AS staff_label,
                 t.sale_id                     AS sale_id,
                 t.subtotal_minor              AS subtotal_minor,
                 t.discount_minor              AS discount_minor,
                 t.service_charge_minor        AS service_charge_minor,
                 t.tax_minor                   AS tax_minor,
                 t.total_minor                 AS total_minor,
                 t.currency                    AS currency,
                 t.tax_rule_version            AS tax_rule_version,
                 t.uses_illustrative_rules     AS uses_illustrative_rules,
                 t.occurred_at                 AS occurred_at,
                 t.idempotency_key             AS idempotency_key
            FROM barbershop_ticket t
            LEFT JOIN staff_profile sp ON sp.id = t.staff_profile_id
           WHERE t.idempotency_key = :idempotencyKey
          """,
      nativeQuery = true)
  Optional<TicketView> findViewByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

  /** Fetches a single ticket by id (RLS-scoped). Used for GET /{id} and the capture response. */
  @Query(
      value =
          """
          SELECT t.id                        AS id,
                 t.business_id                AS business_id,
                 t.chair                      AS chair,
                 t.staff_profile_id            AS staff_profile_id,
                 sp.display_label              AS staff_label,
                 t.sale_id                     AS sale_id,
                 t.subtotal_minor              AS subtotal_minor,
                 t.discount_minor              AS discount_minor,
                 t.service_charge_minor        AS service_charge_minor,
                 t.tax_minor                   AS tax_minor,
                 t.total_minor                 AS total_minor,
                 t.currency                    AS currency,
                 t.tax_rule_version            AS tax_rule_version,
                 t.uses_illustrative_rules     AS uses_illustrative_rules,
                 t.occurred_at                 AS occurred_at,
                 t.idempotency_key             AS idempotency_key
            FROM barbershop_ticket t
            LEFT JOIN staff_profile sp ON sp.id = t.staff_profile_id
           WHERE t.id = :ticketId
          """,
      nativeQuery = true)
  Optional<TicketView> findViewById(@Param("ticketId") UUID ticketId);
}
