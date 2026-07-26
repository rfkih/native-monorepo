package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * Request body for {@code PATCH /api/v1/menu/{menuItemId}/modifier-groups/{groupId}}.
 *
 * <p>All fields are optional (patch semantics). Only non-null fields are applied. The same
 * invariants as {@link CreateModifierGroupRequest} apply: {@code maxSelect >= minSelect >= 0} and
 * {@code SINGLE} implies {@code maxSelect == 1}.
 *
 * @param name the new group name; {@code null} means no change
 * @param selectionType {@code SINGLE} or {@code MULTI}; {@code null} means no change
 * @param required whether the group is required; {@code null} means no change
 * @param minSelect minimum selections; {@code null} means no change
 * @param maxSelect maximum selections; {@code null} means no change
 * @param displayOrder new sort position; {@code null} means no change
 */
public record UpdateModifierGroupRequest(
    String name,
    @Pattern(regexp = "SINGLE|MULTI") String selectionType,
    Boolean required,
    @Min(0) Integer minSelect,
    @Min(1) Integer maxSelect,
    @Min(0) Integer displayOrder) {}
