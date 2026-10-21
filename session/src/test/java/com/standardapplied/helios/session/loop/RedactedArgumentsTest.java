/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.common.Redactor;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.ToolCall;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RedactedArgumentsTest {

  private static final String SECRET = "sk-test-0123456789abcdef";
  private static final String MARKER = "<redacted:API_KEY>";

  private static Redactor redactor() {
    var secrets = new SecretRegistry();
    secrets.register("API_KEY", SECRET);
    return secrets.redactor();
  }

  private static Map<String, Object> ordered(Object... keysAndValues) {
    var map = new LinkedHashMap<String, Object>();
    for (var i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }

  @Test
  void redactsKeysAndStringsAtAnyDepthAndKeepsEverythingElseInOrder() {
    var number = new BigDecimal("1.50");
    var array = new ArrayList<>(Arrays.asList("x " + SECRET, 7, null));
    var arguments =
        ordered(
            "z",
            true,
            "nested",
            ordered("token-" + SECRET, ordered("deep", SECRET), "list", array),
            "a",
            number,
            "none",
            null);

    var redacted = RedactedArguments.of(arguments, redactor());

    var expectedArray = new ArrayList<>(Arrays.asList("x " + MARKER, 7, null));
    assertEquals(
        ordered(
            "z",
            true,
            "nested",
            ordered("token-" + MARKER, ordered("deep", MARKER), "list", expectedArray),
            "a",
            number,
            "none",
            null),
        redacted);
    assertEquals(List.of("z", "nested", "a", "none"), List.copyOf(redacted.keySet()));
  }

  @Test
  void whereRedactingMakesTwoKeysEqualTheLaterEntryWins() {
    var arguments = ordered(SECRET, "first", MARKER, "second");

    assertEquals(Map.of(MARKER, "second"), RedactedArguments.of(arguments, redactor()));
  }

  @Test
  void aCallKeepsItsIdAndNameAndCarriesRedactedArguments() {
    var call = new ToolCall("c1", "Write", Map.of("path", SECRET));

    assertEquals(
        new ToolCall("c1", "Write", Map.of("path", MARKER)),
        RedactedArguments.of(call, redactor()));
  }
}
