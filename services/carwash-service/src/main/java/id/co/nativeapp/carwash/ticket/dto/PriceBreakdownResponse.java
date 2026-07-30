package id.co.nativeapp.carwash.ticket.dto;

/** The response shape of a resolved price breakdown (quote, or a checked-out ticket's pricing). */
public record PriceBreakdownResponse(
    long subtotalMinor,
    long discountMinor,
    long serviceChargeMinor,
    long taxMinor,
    long grandTotalMinor,
    String currency,
    boolean usesIllustrativeRules) {}
