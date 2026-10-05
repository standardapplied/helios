/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import org.junit.jupiter.api.Test;

/** JSON primitives on inputs the {@link HeliosEvent} records never hand them. */
class JsonTextTest {

  @Test
  void nullMapValueIsWrittenAsNull() {
    var map = new HashMap<String, Object>();
    map.put("k", null);

    assertEquals("{\"k\":null}", JsonText.object(map));
  }

  @Test
  void anObjectWithNoFieldsIsEmpty() {
    assertEquals("{}", new JsonObjectWriter().toString());
  }

  @Test
  void nanNumberIsRejected() {
    var thrown = assertThrows(IllegalArgumentException.class, () -> JsonText.number(Double.NaN));

    assertEquals("Cannot encode non-finite number: NaN", thrown.getMessage());
  }

  @Test
  void nanArrayElementIsRejectedWithItsIndex() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> JsonText.array(new double[] {1.0, Double.NaN}));

    assertEquals("Cannot encode non-finite number at index 1", thrown.getMessage());
  }
}
