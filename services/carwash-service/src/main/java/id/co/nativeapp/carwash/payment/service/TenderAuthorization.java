package id.co.nativeapp.carwash.payment.service;

import id.co.nativeapp.carwash.payment.domain.CarwashPayment;
import id.co.nativeapp.money.Money;

/**
 * A {@link PaymentProvider}'s decision for a tender: the resulting {@link CarwashPayment.Status}
 * ({@code CAPTURED} for live cash, {@code PENDING} for a flagged digital tender), the provider
 * reference (null for cash), the {@code pending} flag (true for the flagged-pending digital
 * provider), and the cash {@code change} (null for digital). Internal application value type
 * (ported from restaurant-service's equivalent, retargeted to {@link CarwashPayment.Status}).
 */
public record TenderAuthorization(
    CarwashPayment.Status status, String providerRef, boolean pending, Money change) {}
