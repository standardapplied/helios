/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

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

    public Builder<T> withItems(List<T> items) {
      this.items = new ArrayList<>(items);
      return this;
    }

    public Builder<T> withPaginate(Paginate paginate) {
      this.paginate = paginate;
      return this;
    }

    /**
     * Builds the PaginatedList. If items.size() exceeds the page size, truncates the list and sets
     * hasMore to true.
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
