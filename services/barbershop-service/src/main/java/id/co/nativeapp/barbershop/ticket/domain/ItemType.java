package id.co.nativeapp.barbershop.ticket.domain;

/**
 * The kind of catalog item a {@link BarbershopTicketLine} was priced from: a {@code service_item}
 * or a {@code service_addon} (V1 baseline). Stored as {@code EnumType.STRING} so the {@code
 * barbershop_ticket_line.item_type} column is human-readable and matches the migration's {@code
 * CHECK (item_type IN ('SERVICE', 'ADDON'))} constraint exactly.
 *
 * <p>Renamed from carwash-service's {@code ItemType.PACKAGE} to {@code SERVICE} (ADR 0024) — {@code
 * ADDON} is unchanged.
 */
public enum ItemType {
  SERVICE,
  ADDON
}
