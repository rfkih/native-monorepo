package id.co.nativeapp.restaurant.register.dto;

/**
 * Result of an open-session attempt (ADR 0036): the session plus whether THIS call created it
 * (fresh open → 201) or resolved to an existing row (idempotent replay / race loser → 200). The
 * RecordSaleResult pattern.
 */
public record OpenSessionResult(RegisterSessionResponse session, boolean created) {}
