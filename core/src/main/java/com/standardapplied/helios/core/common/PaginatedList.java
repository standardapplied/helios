/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A paginated result list with metadata about whether more pages exist.
 *
 * @param <T> the type of items
 * @param items the items for the current page
 * @param hasMore whether more pages exist beyond this one
 * @param paginate the pagination parameters used
 */
public record PaginatedList<T>(List<T> items, boolean hasMore, Paginate paginate) {

  /**
   * Creates a builder with no items and no pagination set.
   *
   * @param <T> the type of items
   * @return a new builder
   */
  public static <T> Builder<T> newBuilder() {
    return new Builder<>();
  }

  /**
   * Builder for PaginatedList. Detects hasMore using the limit+1 trick.
   *
   * @param <T> element type of the paginated list
   */
  public static class Builder<T> {

    private List<T> items;
    private Paginate paginate;

    private Builder() {}

    /**
     * Sets the items fetched for the page, which may hold one more than the page size so {@link
     * #build()} can tell whether another page exists. The list is copied. Unset, the page is empty.
     *
     * @param items the fetched items; non-null
     * @return this builder
     * @throws NullPointerException if {@code items} is null
     */
    public Builder<T> withItems(List<T> items) {
      this.items = new ArrayList<>(items);
      return this;
    }

    /**
     * Sets the pagination the items were fetched with; its {@link Paginate#safePageSize()} is the
     * page size {@link #build()} cuts to. Required.
     *
     * @param paginate the pagination parameters; must be non-null by {@link #build()}
     * @return this builder
     */
    public Builder<T> withPaginate(Paginate paginate) {
      this.paginate = paginate;
      return this;
    }

    /**
     * Builds the PaginatedList. If items.size() exceeds the page size, truncates the list and sets
     * hasMore to true.
     *
     * @return the page, its items an unmodifiable list of at most the page size
     * @throws NullPointerException if no pagination was set, or an item is null
     */
    public PaginatedList<T> build() {
      if (items == null) {
        items = List.of();
      }
      Objects.requireNonNull(paginate, "paginate is required");

      var pageSize = paginate.safePageSize();
      var hasMore = items.size() > pageSize;
      if (hasMore) {
        items = items.subList(0, pageSize);
      }
      return new PaginatedList<>(List.copyOf(items), hasMore, paginate);
    }
  }
}
