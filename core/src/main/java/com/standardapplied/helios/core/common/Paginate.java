/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

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

  /**
   * Returns the page number to use: {@code pageNumber}, or 1 when it is zero or negative.
   *
   * @return the 1-based page number, at least 1
   */
  public int safePageNumber() {
    return pageNumber <= 0 ? 1 : pageNumber;
  }

  /**
   * Returns the page size to use: {@code pageSize} when it is between 1 and 500, otherwise 500.
   *
   * @return the page size, from 1 to 500
   */
  public int safePageSize() {
    if (pageSize <= 0 || pageSize > 500) {
      return 500;
    }
    return pageSize;
  }

  /**
   * Returns the default pagination.
   *
   * @return page 1 with 50 items per page
   */
  public static Paginate of() {
    return new Paginate(1, 50);
  }
}
