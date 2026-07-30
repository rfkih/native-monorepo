package id.co.nativeapp.barbershop.ticket.repository;

import id.co.nativeapp.barbershop.ticket.domain.BarbershopTicketLine;
import id.co.nativeapp.barbershop.ticket.projection.TicketLineView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link BarbershopTicketLine}. Carries no manual {@code WHERE
 * company_id}: every method runs inside a {@code @Transactional}, so {@link RlsAutoApplyAspect}
 * sets {@code app.current_tenant} automatically (rule 5).
 */
public interface BarbershopTicketLineRepository
    extends JpaRepository<BarbershopTicketLine, UUID> {

  /** The lines of one ticket, in insertion order — used for receipt/response assembly. */
  @Query(
      value =
          """
          SELECT item_type AS item_type, item_id AS item_id, name AS name,
                 price_minor AS price_minor, currency AS currency, qty AS qty
            FROM barbershop_ticket_line
           WHERE ticket_id = :ticketId
           ORDER BY created_at, id
          """,
      nativeQuery = true)
  List<TicketLineView> findViewsByTicketId(@Param("ticketId") UUID ticketId);
}
