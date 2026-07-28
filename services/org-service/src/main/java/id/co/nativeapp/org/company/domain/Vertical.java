package id.co.nativeapp.org.company.domain;

import java.util.Locale;

/**
 * The business vertical of a {@link OrgUnitType#BUSINESS_UNIT} org node — WHAT KIND of business it
 * runs. Drives which POS surface its outlets get (restaurant today; carwash/barbershop show a
 * coming-soon panel until their consoles ship).
 *
 * <p><strong>Casing decision (deliberate, do not "fix"):</strong> the wire/DB/event value is the
 * LOWERCASE module-key-style {@link #key()} ({@code "restaurant"}), aligned with
 * entitlement-service's {@code module_catalog.module_key} vocabulary — NOT the uppercase {@code
 * .name()} casing the {@code type} column uses. Persistence goes through {@link VerticalConverter}
 * (never {@code @Enumerated}, which would silently store {@code RESTAURANT}). Per-company
 * entitlement (which modules a company MAY use) remains entitlement-service's separate concept;
 * this enum only aligns vocabulary with it.
 *
 * <p>The whitelist is fixed and server-authoritative (like the currency/language whitelists);
 * widening it is a deliberate platform change. A vertical is REQUIRED for a business unit,
 * forbidden for outlet/team nodes (outlets inherit via their parent), and IMMUTABLE after creation
 * (like the company base currency).
 *
 * <p><strong>Adding a vertical? Update every copy of the whitelist</strong> — this enum is the
 * source of truth, but the literal {@code restaurant|carwash|barbershop} is repeated in the DTO
 * {@code @Pattern} constraints ({@code SignupRequest}, {@code
 * CreateCompanyRequest.BusinessRequest}, {@code CreateBusinessRequest}, {@code
 * CreateOrgUnitRequest}) and in the console ({@code org/api.ts} {@code Vertical} type, the {@code
 * VERTICALS} consts in Signup/Onboarding, the {@code AddUnitDialog} options, and the {@code
 * VerticalComingSoon} icon map), plus the event catalog narrative. A missed backend copy fails safe
 * (400 at the DTO boundary) but blocks the feature.
 */
public enum Vertical {
  RESTAURANT("restaurant"),
  CARWASH("carwash"),
  BARBERSHOP("barbershop");

  private final String key;

  Vertical(String key) {
    this.key = key;
  }

  /** The lowercase module-key-style wire/DB/event value. */
  public String key() {
    return key;
  }

  /**
   * Parses a vertical from a request/DB string, accepting any case and trimming whitespace.
   *
   * @throws IllegalArgumentException if {@code raw} is null/blank or not a whitelisted vertical
   *     (mapped to a {@code 400} by the shared advice)
   */
  public static Vertical fromKey(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("vertical must not be blank");
    }
    String normalized = raw.strip().toLowerCase(Locale.ROOT);
    for (Vertical vertical : values()) {
      if (vertical.key.equals(normalized)) {
        return vertical;
      }
    }
    throw new IllegalArgumentException("Unsupported vertical: " + raw);
  }
}
