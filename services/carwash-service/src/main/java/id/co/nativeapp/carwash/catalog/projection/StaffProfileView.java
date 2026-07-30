package id.co.nativeapp.carwash.catalog.projection;

import java.util.UUID;

/**
 * Read projection over a {@code staff_profile} row — only the columns a {@link
 * id.co.nativeapp.carwash.catalog.dto.StaffProfileResponse StaffProfileResponse} needs.
 */
public interface StaffProfileView {

  UUID getId();

  UUID getBusinessId();

  String getDisplayLabel();

  UUID getEmployeeId();

  boolean isActive();
}
