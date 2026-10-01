/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PaginateTest {

  @Test
  void defaultPaginate() {
    var p = Paginate.of();

    assertEquals(1, p.pageNumber());
    assertEquals(50, p.pageSize());
  }

  @Test
  void offsetForFirstPage() {
    assertEquals(0, new Paginate(1, 10).offset());
  }

  @Test
  void offsetForSecondPage() {
    assertEquals(10, new Paginate(2, 10).offset());
  }

  @Test
  void offsetForThirdPage() {
    assertEquals(100, new Paginate(3, 50).offset());
  }

  @Test
  void limitIncludesPlusOne() {
    assertEquals(11, new Paginate(1, 10).limit());
    assertEquals(51, new Paginate(1, 50).limit());
  }

  @Test
  void safePageNumberClampsZero() {
    assertEquals(1, new Paginate(0, 10).safePageNumber());
  }

  @Test
  void safePageNumberClampsNegative() {
    assertEquals(1, new Paginate(-5, 10).safePageNumber());
  }

  @Test
  void safePageSizeClampsZero() {
    assertEquals(500, new Paginate(1, 0).safePageSize());
  }

  @Test
  void safePageSizeClampsNegative() {
    assertEquals(500, new Paginate(1, -1).safePageSize());
  }

  @Test
  void safePageSizeClampsOverMax() {
    assertEquals(500, new Paginate(1, 501).safePageSize());
  }

  @Test
  void safePageSizeAllowsMax() {
    assertEquals(500, new Paginate(1, 500).safePageSize());
  }

  @Test
  void safePageSizePreservesValid() {
    assertEquals(25, new Paginate(1, 25).safePageSize());
  }

  @Test
  void offsetUseSafeValues() {
    assertEquals(0, new Paginate(-1, 10).offset());
    assertEquals(0, new Paginate(0, 0).offset());
  }
}
