package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.payroll.domain.StatutoryParams;
import org.junit.jupiter.api.Test;

/**
 * Load-time validation of {@link StatutoryParams} — the bridge between the rule DATA (numbers in
 * JSON) and the calculator MACHINERY. A pure unit test (no Spring/DB).
 *
 * <p>Focus (#34): a PROGRESSIVE table's TOP bracket cap MUST be the agreed unbounded sentinel so
 * the top marginal rate covers all income above its floor. A FINITE top cap would silently leave
 * income above it untaxed, so it is rejected the moment the table is loaded (fail-fast) rather than
 * only when a high-enough earner trips the run-time walk.
 */
class StatutoryParamsTest {

  @Test
  void anUnboundedTopBracketLoadsAndSortsAscendingByFloor() {
    // Brackets supplied out of order; the loader sorts them and accepts the unbounded top cap.
    String json =
        "{\"brackets\":["
            + "{\"floor_minor\":50000000,\"cap_minor\":"
            + StatutoryParams.UNBOUNDED_TOP_CAP_MINOR
            + ",\"rate_bp\":1500},"
            + "{\"floor_minor\":0,\"cap_minor\":50000000,\"rate_bp\":1000}]}";

    StatutoryParams.ProgressiveParams params = StatutoryParams.progressive(json);

    assertThat(params.brackets()).hasSize(2);
    assertThat(params.brackets().get(0).floorMinor()).isZero();
    assertThat(params.brackets().get(1).capMinor())
        .isEqualTo(StatutoryParams.UNBOUNDED_TOP_CAP_MINOR);
  }

  @Test
  void aFiniteTopBracketCapIsRejectedAtLoadTime() {
    // A single bracket capped at a FINITE 100,000,000 — top income above it would be untaxed.
    String json = "{\"brackets\":[{\"floor_minor\":0,\"cap_minor\":100000000,\"rate_bp\":1000}]}";

    assertThatThrownBy(() -> StatutoryParams.progressive(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unbounded sentinel")
        .hasMessageContaining(Long.toString(StatutoryParams.UNBOUNDED_TOP_CAP_MINOR));
  }

  @Test
  void aFiniteTopCapIsRejectedEvenWhenLowerBracketsAreUnbounded() {
    // The HIGHEST-floor bracket is the one that must be unbounded; a finite cap there is rejected
    // even if a lower bracket happens to carry the sentinel value.
    String json =
        "{\"brackets\":["
            + "{\"floor_minor\":0,\"cap_minor\":50000000,\"rate_bp\":1000},"
            + "{\"floor_minor\":50000000,\"cap_minor\":250000000,\"rate_bp\":1500}]}";

    assertThatThrownBy(() -> StatutoryParams.progressive(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unbounded sentinel");
  }
}
