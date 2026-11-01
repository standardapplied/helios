/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static com.standardapplied.helios.repl.protocol.ProcessTransport.MAX_CAPTURED_OUTPUT_CHARS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** What survives of a snippet's output, counted in characters and cut between characters. */
class CapturedStreamTest {

  private static final String ALMOST_AT_CAP = "x".repeat(MAX_CAPTURED_OUTPUT_CHARS - 1);

  private static String captured(String printed) {
    var stream = new CapturedStream();
    new PrintStream(stream, true, StandardCharsets.UTF_8).print(printed);
    return stream.text();
  }

  @Test
  void outputUpToTheCapIsReturnedAsPrinted() {
    var printed = ALMOST_AT_CAP + "é";

    assertEquals(printed, captured(printed));
  }

  @Test
  void outputPastTheCapIsCutAndTheRestCounted() {
    var printed = "x".repeat(3_000_000);

    assertEquals(
        printed.substring(0, MAX_CAPTURED_OUTPUT_CHARS)
            + "\n[output truncated: 2000000 more chars discarded]",
        captured(printed));
  }

  @Test
  void aThreeByteCharacterAtTheCutIsKeptWhole() {
    assertEquals(
        ALMOST_AT_CAP + "€\n[output truncated: 1 more chars discarded]",
        captured(ALMOST_AT_CAP + "€€"));
  }

  @Test
  void aThreeByteCharacterPastTheCutIsDroppedWhole() {
    assertEquals(
        ALMOST_AT_CAP + "x\n[output truncated: 1 more chars discarded]",
        captured(ALMOST_AT_CAP + "x€"));
  }

  @Test
  void aFourByteCharacterThatWouldCrossTheCutIsDroppedWholeAndCountsAsTwoChars() {
    assertEquals(
        ALMOST_AT_CAP + "\n[output truncated: 2 more chars discarded]",
        captured(ALMOST_AT_CAP + "😀"));
  }

  @Test
  void aFourByteCharacterEndingAtTheCutIsKept() {
    var printed = "x".repeat(MAX_CAPTURED_OUTPUT_CHARS - 2) + "😀";

    assertEquals(printed, captured(printed));
  }

  @Test
  void fourByteCharactersCountAsTwoCharsEach() {
    var smiley = "\uD83D\uDE00";

    assertEquals(
        smiley.repeat(MAX_CAPTURED_OUTPUT_CHARS / 2)
            + "\n[output truncated: 2000000 more chars discarded]",
        captured(smiley.repeat(1_500_000)));
  }

  @Test
  void bytesThatAreNotUtf8AreBoundedToo() throws Exception {
    var stream = new CapturedStream();
    var strayContinuation = new byte[4 * MAX_CAPTURED_OUTPUT_CHARS];
    Arrays.fill(strayContinuation, (byte) 0x80);

    stream.write(strayContinuation, 0, strayContinuation.length);

    assertEquals(
        "\uFFFD".repeat(MAX_CAPTURED_OUTPUT_CHARS)
            + "\n[output truncated: 2000000 more chars discarded]",
        stream.text());
  }
}
