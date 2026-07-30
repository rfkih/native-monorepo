package id.co.nativeapp.carwash.ticket.domain;

/**
 * The kind of catalog item a {@link CarwashTicketLine} was priced from: a {@code wash_package} or a
 * {@code wash_addon} (V4/V6). Stored as {@code EnumType.STRING} so the {@code
 * carwash_ticket_line.item_type} column is human-readable and matches the migration's {@code CHECK
 * (item_type IN ('PACKAGE', 'ADDON'))} constraint exactly.
 */
public enum ItemType {
  PACKAGE,
  ADDON
}
