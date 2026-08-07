package id.co.nativeapp.org.company.projection;

import java.util.UUID;

/**
 * Slim read projection over {@code org_unit} rows for the POS outlet picker ({@code GET
 * /api/v1/outlets}).
 *
 * <p>Only the columns the picker needs are selected — {@code id}, {@code name}, the parent business
 * unit's {@code vertical} (joined in; outlet rows themselves store NULL, and the alias may be null
 * for an anomalous parentless outlet), and the parent's own {@code id} as {@code divisionId} (same
 * join, same null-for-anomaly caveat). Lives in the feature's {@code projection} package
 * (CODE-STRUCTURE §3.3): a read model is neither the write-side {@code domain} entity nor a
 * request/response {@code dto}. Snake_case native-query aliases map to these accessors via Spring
 * Data's projection-interface convention.
 */
public interface OutletView {

  UUID getId();

  String getName();

  String getVertical();

  UUID getDivisionId();
}
