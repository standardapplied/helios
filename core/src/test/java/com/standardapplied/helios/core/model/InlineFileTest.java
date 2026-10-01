/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InlineFileTest {

  @Test
  void ofFactory() {
    var data = new byte[] {1, 2, 3};
    var file = InlineFile.of(data, "image/png");

    assertArrayEquals(data, file.data());
    assertEquals("image/png", file.mimeType());
  }

  @Test
  void nullDataThrows() {
    assertThrows(NullPointerException.class, () -> InlineFile.of(null, "image/png"));
  }

  @Test
  void emptyDataThrows() {
    assertThrows(IllegalArgumentException.class, () -> InlineFile.of(new byte[0], "image/png"));
  }

  @Test
  void nullMimeTypeThrows() {
    // mimeType is bundled with blank checks via Strings.isBlank; surfaces as IAE
    assertThrows(IllegalArgumentException.class, () -> InlineFile.of(new byte[] {1}, null));
  }

  @Test
  void blankMimeTypeThrows() {
    assertThrows(IllegalArgumentException.class, () -> InlineFile.of(new byte[] {1}, "  "));
  }

  @Test
  void equalityByContent() {
    var a = InlineFile.of(new byte[] {1, 2, 3}, "image/png");
    var b = InlineFile.of(new byte[] {1, 2, 3}, "image/png");

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void differentDataNotEqual() {
    var a = InlineFile.of(new byte[] {1, 2, 3}, "image/png");
    var b = InlineFile.of(new byte[] {4, 5, 6}, "image/png");

    assertNotEquals(a, b);
  }

  @Test
  void differentMimeTypeNotEqual() {
    var a = InlineFile.of(new byte[] {1, 2, 3}, "image/png");
    var b = InlineFile.of(new byte[] {1, 2, 3}, "application/pdf");

    assertNotEquals(a, b);
  }

  @Test
  void toStringContainsMimeTypeAndSize() {
    var file = InlineFile.of(new byte[] {1, 2, 3}, "application/pdf");

    assertTrue(file.toString().contains("application/pdf"));
    assertTrue(file.toString().contains("3"));
  }
}
