package id.co.nativeapp.carwash.metric;

/**
 * The grains a vertical can emit a metric at (ARCHITECTURE.md §2 / EVENT-CATALOG {@code
 * MetricPublished.grain}): {@code employee} | {@code shift} | {@code outlet}. A commission rule on
 * the employee side may only request a grain the emitting vertical declares in its metric contract;
 * the validation layer (on the consumer / employee side) rejects a rule requesting a grain the
 * vertical cannot emit. carwash declares the {@link #OUTLET} grain for its wash metrics.
 */
public enum MetricGrain {
  EMPLOYEE("employee"),
  SHIFT("shift"),
  OUTLET("outlet");

  private final String wireValue;

  MetricGrain(String wireValue) {
    this.wireValue = wireValue;
  }

  /** The lowercase wire value carried in the {@code MetricPublished.grain} field. */
  public String wireValue() {
    return wireValue;
  }
}
