package id.co.nativeapp.finance.ap.projection;

import java.util.UUID;

/**
 * Read projection over a {@code vendor} row — only the columns the vendor list/detail endpoints
 * need, never {@code SELECT *} of the entity. Snake-case native-query aliases map to these
 * accessors via Spring Data's projection convention. Reached only from the service + repository
 * layers (ArchUnit projection-layer rule).
 */
public interface VendorView {

  UUID getId();

  String getName();

  String getEmail();

  String getTaxId();

  boolean getActive();
}
