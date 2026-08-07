package id.co.nativeapp.payment.charge.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The till's view of a charge (create response + poll): the QR payload to render, its expiry, and
 * the lifecycle status. Never any credential material.
 */
public record ChargeResponse(
    UUID chargeId,
    String status,
    String vertical,
    UUID paymentId,
    String qrString,
    String qrUrl,
    Instant expiresAt,
    long amountMinor,
    String currency) {}
