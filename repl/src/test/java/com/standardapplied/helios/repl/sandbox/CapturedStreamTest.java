/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static com.standardapplied.helios.repl.protocol.ProcessTransport.MAX_CAPTURED_OUTPUT_CHARS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What survives of a snippet's output, counted in characters and cut between characters. */
class CapturedStreamTest {

  private static final String ALMOST_AT_CAP = "x".repeat(MAX_CAPTURED_OUTPUT_CHARS - 1);

  private static final String ESCAPE = "\u001B";
  private static final String TWO_ESCAPES_PAST_THE_CAP = ESCAPE.repeat(1_000_000 + 2);
  private static final int ESCAPES_THAT_FIT = CapturedStream.MAX_ESCAPED_CHARS / 6;

  private static CapturedStream printing(String printed) {
    var stream = new CapturedStream();
    new PrintStream(stream, true, StandardCharsets.UTF_8).print(printed);
    return stream;
  }

  private static String captured(String printed) {
    return CapturedStream.text(printing(printed));
  }

  private static int serializedChars(Map<String, Object> reply) throws IOException {
    return ProcessTransport.RPC_PREFIX.length()
        + ProcessTransport.serializeMessage(new RpcMessage.Response("1", reply)).length();
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
        CapturedStream.text(stream));
  }

  @Test
  void charactersJsonEscapesAreCutAtTheirEscapedWidth() {
    assertEquals(
        ESCAPE.repeat(ESCAPES_THAT_FIT)
            + "\n[output truncated: "
            + (1_000_002 - ESCAPES_THAT_FIT)
            + " more chars discarded]",
        captured(TWO_ESCAPES_PAST_THE_CAP));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"", "\\", "\b", "\t", "\n", "\f", "\r", "😀"})
  void aCharacterTakingTwoCharsInJsonCountsAsTwo(String twoWide) {
    var escapes = 400_000;
    var room = (CapturedStream.MAX_ESCAPED_CHARS - 6 * escapes) / 2;
    var kept = ESCAPE.repeat(escapes) + twoWide.repeat(room);

    assertEquals(
        kept + "\n[output truncated: " + 1_000 * twoWide.length() + " more chars discarded]",
        captured(kept + twoWide.repeat(1_000)));
  }

  @Test
  void charactersJsonLeavesAloneReachTheCap() {
    for (var c = ' '; c < 0x80; c++) {
      var printed = String.valueOf(c).repeat(MAX_CAPTURED_OUTPUT_CHARS);
      if (c != '"' && c != '\\') {
        assertEquals(printed, captured(printed), "character " + (int) c);
      }
    }
  }

  @Test
  void aMultiByteCharacterAtTheEscapedCutIsKeptWhole() {
    var edge = "x".repeat(CapturedStream.MAX_ESCAPED_CHARS - 6 * ESCAPES_THAT_FIT - 1);
    var kept = ESCAPE.repeat(ESCAPES_THAT_FIT) + edge + "€";

    assertEquals(kept + "\n[output truncated: 1 more chars discarded]", captured(kept + "€"));
  }

  @Test
  void everyAsciiCharacterStaysInsideItsShareOnceSerialized() throws IOException {
    for (var c = (char) 0; c < 0x80; c++) {
      var text = captured(String.valueOf(c).repeat(MAX_CAPTURED_OUTPUT_CHARS + 1));

      assertTrue(
          serializedChars(Map.of("o", text)) < CapturedStream.MAX_ESCAPED_CHARS + 2_048,
          "character " + (int) c);
    }
  }

  @Test
  void aReplyOfThreeFullCapturesFitsAFrame() throws IOException {
    var full = captured(TWO_ESCAPES_PAST_THE_CAP);

    var frame =
        serializedChars(
            Map.of("stdout", full, "stderr", full, "exitCode", 1, "timedOut", true, "extra", full));

    assertTrue(frame <= ProcessTransport.MAX_FRAME_CHARS, "frame of " + frame);
  }

  @Test
  void capturesReadTogetherAreCutAsOneOutput() {
    var almostFull = printing(ALMOST_AT_CAP);
    var next = printing("ab");

    assertEquals(
        ALMOST_AT_CAP + "a\n[output truncated: 1 more chars discarded]",
        CapturedStream.text(almostFull, next));
  }

  @Test
  void aFullFirstCaptureKeepsTheMarkerLastAndCountsTheSecond() {
    var full = printing(ALMOST_AT_CAP + "xyz");
    var next = printing("Execution timed out\n");

    assertEquals(
        ALMOST_AT_CAP + "x\n[output truncated: 22 more chars discarded]",
        CapturedStream.text(full, next));
  }

  @Test
  void capturesThatFitAreReadOneAfterTheOther() {
    assertEquals("ab", CapturedStream.text(printing("a"), printing("b")));
  }
}
