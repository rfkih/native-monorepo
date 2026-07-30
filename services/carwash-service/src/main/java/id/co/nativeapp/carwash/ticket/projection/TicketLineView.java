package id.co.nativeapp.carwash.ticket.projection;

import java.util.UUID;

/** Read projection over a {@code carwash_ticket_line} row — the columns a receipt line needs. */
public interface TicketLineView {

  String getItemType();

  UUID getItemId();

  String getName();

  long getPriceMinor();

  String getCurrency();

  int getQty();
}
