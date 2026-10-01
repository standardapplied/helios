/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PaginatedListTest {

  @Test
  void noMorePages() {
    var result =
        PaginatedList.<String>newBuilder()
            .withItems(List.of("a", "b"))
            .withPaginate(new Paginate(1, 10))
            .build();

    assertFalse(result.hasMore());
    assertEquals(List.of("a", "b"), result.items());
  }

  @Test
  void hasMoreWhenExceedsPageSize() {
    var items = IntStream.rangeClosed(1, 11).mapToObj(String::valueOf).toList();
    var result =
        PaginatedList.<String>newBuilder()
            .withItems(items)
            .withPaginate(new Paginate(1, 10))
            .build();

    assertTrue(result.hasMore());
    assertEquals(10, result.items().size());
    assertEquals("10", result.items().getLast());
  }

  @Test
  void exactPageSizeNoMore() {
    var items = IntStream.rangeClosed(1, 10).mapToObj(String::valueOf).toList();
    var result =
        PaginatedList.<String>newBuilder()
            .withItems(items)
            .withPaginate(new Paginate(1, 10))
            .build();

    assertFalse(result.hasMore());
    assertEquals(10, result.items().size());
  }

  @Test
  void emptyList() {
    var result =
        PaginatedList.<String>newBuilder().withItems(List.of()).withPaginate(Paginate.of()).build();

    assertFalse(result.hasMore());
    assertTrue(result.items().isEmpty());
  }

  @Test
  void nullItemsDefaultsToEmpty() {
    var result = PaginatedList.<String>newBuilder().withPaginate(Paginate.of()).build();

    assertFalse(result.hasMore());
    assertTrue(result.items().isEmpty());
  }

  @Test
  void paginateRequired() {
    assertThrows(
        NullPointerException.class,
        () -> PaginatedList.<String>newBuilder().withItems(List.of("a")).build());
  }

  @Test
  void itemsAreImmutable() {
    var result =
        PaginatedList.<String>newBuilder()
            .withItems(List.of("a"))
            .withPaginate(Paginate.of())
            .build();

    assertThrows(UnsupportedOperationException.class, () -> result.items().add("b"));
  }

  @Test
  void preservesPaginate() {
    var paginate = new Paginate(3, 25);
    var result =
        PaginatedList.<String>newBuilder().withItems(List.of("a")).withPaginate(paginate).build();

    assertEquals(paginate, result.paginate());
  }
}
