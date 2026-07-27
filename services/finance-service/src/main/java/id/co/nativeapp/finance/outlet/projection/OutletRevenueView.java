package id.co.nativeapp.finance.outlet.projection;

import java.util.UUID;

/**
 * Read projection over one {@code outlet_revenue} row — only the three columns the {@code
 * /pnl/outlets} endpoint needs, never {@code SELECT *} of the accumulator entity.
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.finance.outlet.repository.OutletRevenueRepository}. Snake-case native-query
 * aliases map to these accessors via Spring Data's projection-interface convention (CLAUDE.md
 * "native-query aliases snake_case; map via projection interfaces"). Placed in a dedicated {@code
 * projection} package per docs/CODE-STRUCTURE.md §3.3 so the ArchUnit {@code Projection} layer rule
 * can enforce that projections are reached only from the service and repository layers.
 */
public interface OutletRevenueView {

  UUID getBusinessId();

  long getRevenueMinor();

  String getCurrency();
}
