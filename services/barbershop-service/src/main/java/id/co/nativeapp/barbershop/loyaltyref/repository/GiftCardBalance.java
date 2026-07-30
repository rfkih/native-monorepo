package id.co.nativeapp.barbershop.loyaltyref.repository;

/**
 * The two columns {@link GiftCardRefRepository#findActiveBalance} needs from {@code gift_card_ref}
 * to clamp a redemption: the cached ABSOLUTE stored-value balance and the card's currency. Not a
 * projection (this repository is a plain {@link org.springframework.jdbc.core.JdbcTemplate} wrapper
 * over a read-model table, not a Spring Data JPA repository) — a small internal value type local to
 * this package.
 *
 * @param balanceMinor the cached stored-value balance, minor units (may be negative — see V17
 *     migration comment on {@code gift_card_ref.balance_minor})
 * @param currency ISO-4217 currency code (right-padded {@code CHAR(3)} already stripped)
 */
public record GiftCardBalance(long balanceMinor, String currency) {}
