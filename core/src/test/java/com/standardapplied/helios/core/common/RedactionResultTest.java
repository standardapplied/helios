/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedactionResultTest {

  private static RedactionResult result(String text) {
    return new RedactionResult(text.getBytes(StandardCharsets.UTF_8), Map.of());
  }

  @Test
  void aTextAtTheBoundIsKeptWhole() {
    var whole = result("r".repeat(8));
    assertSame(whole, whole.truncated(8));
  }

  @Test
  void aLongerTextIsCutAtTheBoundWithANoteOfWhatWasCut() {
    assertEquals("r".repeat(8) + "… [5 more chars]", result("r".repeat(13)).truncated(8).text());
  }

  @Test
  void aTextIsNeverCutInsideASurrogatePair() {
    assertEquals("rrr😀… [4 more chars]", result("rrr😀tail").truncated(4).text());
  }

  @Test
  void aCutKeepsTheRedactionCounts() {
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", "tok-0123456789");
    var cut = secrets.redactor().redact("tok-0123456789 and more").truncated(4);
    assertEquals("<red… [21 more chars]", cut.text());
    assertEquals(Map.of("TOKEN", 1), cut.counts());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void aBoundThatKeepsNothingIsRejected(int maxChars) {
    var ex = assertThrows(IllegalArgumentException.class, () -> result("r").truncated(maxChars));
    assertEquals("maxChars must be positive: " + maxChars, ex.getMessage());
  }
}
