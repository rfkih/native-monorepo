package id.co.nativeapp.org.company.projection;

import java.util.UUID;

/**
 * Slim read projection over {@code org_unit} rows for the POS outlet picker ({@code GET
 * /api/v1/outlets}).
 *
 * <p>Only the two columns the picker needs are selected — {@code id} and {@code name}. Lives in the
 * feature's {@code projection} package (CODE-STRUCTURE §3.3): a read model is neither the
 * write-side {@code domain} entity nor a request/response {@code dto}. Snake_case native-query
 * aliases map to these accessors via Spring Data's projection-interface convention.
 */
public interface OutletView {

  UUID getId();

  String getName();
}
