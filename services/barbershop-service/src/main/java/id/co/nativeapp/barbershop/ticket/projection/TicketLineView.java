package id.co.nativeapp.barbershop.ticket.projection;

import java.util.UUID;

/** Read projection over a {@code barbershop_ticket_line} row — the columns a receipt line needs. */
public interface TicketLineView {

  String getItemType();

  UUID getItemId();

  String getName();

  long getPriceMinor();

  String getCurrency();

  int getQty();
}
