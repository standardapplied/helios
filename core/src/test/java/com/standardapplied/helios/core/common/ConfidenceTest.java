/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConfidenceTest {

  @Test
  void wireValueIsEnumName() {
    assertEquals("LOW", Confidence.LOW.wireValue());
    assertEquals("MEDIUM", Confidence.MEDIUM.wireValue());
    assertEquals("HIGH", Confidence.HIGH.wireValue());
  }

  @Test
  void fromWireParsesLowerCase() {
    assertEquals(Confidence.LOW, Confidence.fromWire("low"));
    assertEquals(Confidence.MEDIUM, Confidence.fromWire("medium"));
    assertEquals(Confidence.HIGH, Confidence.fromWire("high"));
  }

  @Test
  void fromWireParsesUpperCase() {
    assertEquals(Confidence.LOW, Confidence.fromWire("LOW"));
    assertEquals(Confidence.HIGH, Confidence.fromWire("HIGH"));
  }

  @Test
  void fromWireTrimsWhitespace() {
    assertEquals(Confidence.MEDIUM, Confidence.fromWire("  medium  "));
  }

  @Test
  void fromWireRejectsNull() {
    var ex = assertThrows(IllegalArgumentException.class, () -> Confidence.fromWire(null));
    assertEquals("confidence value must not be blank", ex.getMessage());
  }

  @Test
  void fromWireRejectsBlank() {
    assertThrows(IllegalArgumentException.class, () -> Confidence.fromWire(""));
    assertThrows(IllegalArgumentException.class, () -> Confidence.fromWire("   "));
  }

  @Test
  void fromWireRejectsUnknownValue() {
    var ex = assertThrows(IllegalArgumentException.class, () -> Confidence.fromWire("certain"));
    assertEquals("unknown confidence level: certain", ex.getMessage());
  }

  @Test
  void valuesAreInOrdinalOrder() {
    var values = Confidence.values();
    assertEquals(Confidence.LOW, values[0]);
    assertEquals(Confidence.MEDIUM, values[1]);
    assertEquals(Confidence.HIGH, values[2]);
  }
}
