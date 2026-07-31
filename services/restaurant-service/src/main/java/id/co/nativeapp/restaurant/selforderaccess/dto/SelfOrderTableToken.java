package id.co.nativeapp.restaurant.selforderaccess.dto;

import java.util.UUID;

/**
 * One minted, table-scoped self-order token — the QR code content to print on that table's tent.
 *
 * @param tableId the {@code restaurant_table} id (for the console to correlate the printed QR back
 *     to the table it labels)
 * @param tableLabel the table's label (e.g. {@code "T1"}) — also embedded in the token's payload
 * @param token the compact {@code payload.signature} string to render as a QR code
 */
public record SelfOrderTableToken(UUID tableId, String tableLabel, String token) {}
