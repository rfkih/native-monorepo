package id.co.nativeapp.carwash.payment.service;

import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.money.Money;
import java.util.UUID;

/**
 * What a {@link PaymentProvider} is asked to authorize: the ticket being paid, the tender, the
 * server-computed {@code amount} due (Money), the cash {@code tenderedMinor} (null for digital),
 * and the idempotency key. An internal application value type — not an HTTP DTO.
 *
 * <p>Adapted from restaurant-service's equivalent (which additionally carried {@code orderId} +
 * {@code businessId}): carwash payments are not order-coupled, so this instruction is keyed by
 * {@code ticketId} only, and {@code businessId} is dropped — neither {@link CashProvider} nor
 * {@link DigitalProvider} nor {@link PaymentProviderRegistry} read a business/outlet id; that stays
 * the concern of the (not-yet-built) ticket-checkout writer, which will run the {@link
 * id.co.nativeapp.carwash.outletref.service.OutletAccessGuard} check itself before ever building
 * this instruction.
 */
public record PaymentInstruction(
    UUID ticketId,
    TenderType tenderType,
    Money amount,
    Long tenderedMinor,
    String idempotencyKey) {}
