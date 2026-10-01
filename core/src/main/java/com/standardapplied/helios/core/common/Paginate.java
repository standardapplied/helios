/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

/**
 * Holds pagination values for paginated queries.
 *
 * @param pageNumber the 1-based page number
 * @param pageSize the number of items per page
 */
public record Paginate(int pageNumber, int pageSize) {

  /**
   * Converts page number to SQL offset.
   *
   * @return the offset to query a DB
   */
  public int offset() {
    return (safePageNumber() - 1) * safePageSize();
  }

  /**
   * Converts page size to SQL limit. Adds one to detect whether more pages exist.
   *
   * @return the limit used to query a DB
   */
  public int limit() {
    return safePageSize() + 1;
  }

  /** Returns a safe page number (minimum 1). */
  public int safePageNumber() {
    return pageNumber <= 0 ? 1 : pageNumber;
  }

  /** Returns a safe page size (minimum 1, maximum 500). */
  public int safePageSize() {
    if (pageSize <= 0 || pageSize > 500) {
      return 500;
    }
    return pageSize;
  }

  /** Default pagination: page 1, size 50. */
  public static Paginate of() {
    return new Paginate(1, 50);
  }
}
