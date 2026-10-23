/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.runtime.CancellationToken;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class CancellableLineTest {

  @Test
  void aCancelledTokenStopsABacktrackingMatch() {
    var token = new CancellationToken();
    token.cancel("deadline");
    var line = new CancellableLine("a".repeat(100_000), token);
    var matcher = Pattern.compile("(a+)+b").matcher(line);

    var stopped = assertThrows(CancellationException.class, matcher::find);

    assertEquals("deadline", stopped.getMessage());
  }

  @Test
  void aLiveTokenMatchesAsThePlainLineDoes() {
    var line = new CancellableLine("x".repeat(5_000) + "abbc", new CancellationToken());
    var matcher = Pattern.compile("b+").matcher(line);

    assertTrue(matcher.find());
    assertEquals("bb", matcher.group());
    assertEquals(5_004, line.length());
    assertEquals("x".repeat(5_000) + "abbc", line.toString());
  }
}
