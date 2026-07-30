package id.co.nativeapp.barbershop.payment.service;

import id.co.nativeapp.barbershop.payment.domain.BarbershopPayment;
import id.co.nativeapp.money.Money;

/**
 * A {@link PaymentProvider}'s decision for a tender: the resulting {@link BarbershopPayment.Status}
 * ({@code CAPTURED} for live cash, {@code PENDING} for a flagged digital tender), the provider
 * reference (null for cash), the {@code pending} flag (true for the flagged-pending digital
 * provider), and the cash {@code change} (null for digital). Internal application value type
 * (ported from carwash-service's equivalent, retargeted to {@link BarbershopPayment.Status}).
 */
public record TenderAuthorization(
    BarbershopPayment.Status status, String providerRef, boolean pending, Money change) {}
