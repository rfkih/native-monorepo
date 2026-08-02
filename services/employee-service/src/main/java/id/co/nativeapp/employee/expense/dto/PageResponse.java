package id.co.nativeapp.employee.expense.dto;

import java.util.List;

/**
 * The ENGINEERING-STANDARDS §1.3 pagination envelope — every collection GET returns this shape,
 * never a bare array, so fields stay additive. No fleet-wide shared version exists yet (checked:
 * finance AR has none either, code review W2), so this is a local, service-scoped record; a future
 * shared {@code libs/} version can supersede it without a wire-shape change.
 *
 * @param content this page's rows
 * @param page the 0-based page index requested
 * @param size the page size actually applied (post-cap)
 * @param totalElements the total row count across every page
 * @param totalPages the total number of pages ({@code 0} when {@code size <= 0}, defensively)
 */
public record PageResponse<T>(
    List<T> content, int page, int size, long totalElements, int totalPages) {

  /** Builds the envelope, computing {@code totalPages} from {@code totalElements}/{@code size}. */
  public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
    int totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(content, page, size, totalElements, totalPages);
  }
}
