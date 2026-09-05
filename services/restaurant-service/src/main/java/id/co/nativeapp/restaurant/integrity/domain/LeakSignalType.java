package id.co.nativeapp.restaurant.integrity.domain;

/**
 * The kinds of signal the sales-leak report can raise (ADR 0074).
 *
 * <p>This enum name is the WIRE VALUE and the i18n key suffix — the server never returns
 * user-facing prose (rule 9). The console renders each signal's title, explanation and advice from
 * its {@code salesIntegrity.signal.<TYPE>.*} keys, so adding a type here means adding copy in both
 * locales, and renaming one is a breaking change to the client.
 *
 * <p>Each type carries its own default severity because severity is a property of what the signal
 * MEANS, not of the outlet it was found at: "stock left the building with no sale" is inherently
 * harder to explain than "somebody forgot to close the register".
 */
public enum LeakSignalType {

  /**
   * Tracked menu items counted short — the cleanest signal: one missing bottle is one unrecorded
   * sale at one known price.
   */
  MISSING_TRACKED_ITEMS(LeakSeverity.HIGH),

  /**
   * Ingredients consumed beyond what the recipes account for. High, but the noisiest of the high
   * signals until waste and staff meals can be recorded and netted out.
   */
  INGREDIENT_SHORTFALL(LeakSeverity.HIGH),

  /**
   * An hour with no sales at all, on a trading day, at a weekday-and-hour the outlet's own history
   * says is normally busy — the shape a till switched off during service leaves behind.
   */
  DARK_HOUR(LeakSeverity.HIGH),

  /** Sales rung while no register session was open, so no drawer count ever covered them. */
  SALES_OUTSIDE_SESSION(LeakSeverity.MEDIUM),

  /** A day that took money and never produced a Z-report. */
  TRADING_DAY_WITHOUT_CLOSE(LeakSeverity.MEDIUM),

  /** The drawer keeps coming up short at close, for the same person, beyond rounding. */
  PERSISTENT_CASH_SHORT(LeakSeverity.MEDIUM),

  /**
   * The drawer holds materially MORE than the system expects. Counter-intuitive as a leak signal,
   * and exactly why it is here: cash with no sale behind it is what an unrecorded sale leaves in
   * the till when the money was not taken out.
   */
  UNEXPLAINED_CASH_OVER(LeakSeverity.MEDIUM),

  /** A session left open long after the shift ended — the drawer was never reconciled. */
  SESSION_LEFT_OPEN(LeakSeverity.LOW),

  /**
   * A run of closes that came out to EXACTLY zero variance. An honest small outlet can look like
   * this, which is why it is LOW — but a counted drawer that never disagrees with the system by a
   * single rupiah is more often a figure being copied than counted.
   */
  EXACT_ZERO_CLOSE_RUN(LeakSeverity.LOW);

  private final LeakSeverity defaultSeverity;

  LeakSignalType(LeakSeverity defaultSeverity) {
    this.defaultSeverity = defaultSeverity;
  }

  public LeakSeverity defaultSeverity() {
    return defaultSeverity;
  }
}
