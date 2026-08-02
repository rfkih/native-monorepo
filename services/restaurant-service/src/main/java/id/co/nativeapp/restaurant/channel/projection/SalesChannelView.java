package id.co.nativeapp.restaurant.channel.projection;

import java.util.UUID;

/**
 * Read projection for a sales channel (ADR 0036 Phase B2) — backs {@code SalesChannelRepository}'s
 * native list / find-by-id queries. Lives in its own {@code projection} package — a read model is
 * neither the write-side domain entity nor a request/response DTO (CODE-STRUCTURE.md §3.3).
 */
public interface SalesChannelView {

  UUID getId();

  String getCode();

  String getName();

  boolean isActive();
}
