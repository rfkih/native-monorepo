package id.co.nativeapp.finance.ar.projection;

import java.util.UUID;

/**
 * Read projection over a {@code customer} row — only the columns the customer list/detail endpoints
 * need, never {@code SELECT *} of the entity. Snake-case native-query aliases map to these
 * accessors via Spring Data's projection convention. Reached only from the service + repository
 * layers (ArchUnit projection-layer rule).
 */
public interface CustomerView {

  UUID getId();

  String getName();

  String getEmail();

  String getTaxId();

  boolean getActive();
}
