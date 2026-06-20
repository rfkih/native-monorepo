package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.domain.Payment;

/**
 * A {@link PaymentProvider}'s decision for a tender: the resulting {@link Payment.Status} ({@code
 * CAPTURED} for live cash, {@code PENDING} for a flagged digital tender), the provider reference
 * (null for cash), the {@code pending} flag (true for the flagged-pending digital provider), and
 * the cash {@code change} (null for digital). Internal application value type.
 */
public record TenderAuthorization(
    Payment.Status status, String providerRef, boolean pending, Money change) {}
