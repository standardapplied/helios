/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.protocol;

import static com.standardapplied.helios.repl.protocol.ProcessTransport.MAX_CAPTURED_OUTPUT_CHARS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** What a capture keeps at its cap, and how a drain reports what it dropped. */
class CapturedOutputTest {

  private static final String AT_CAP = "x".repeat(MAX_CAPTURED_OUTPUT_CHARS);

  @Test
  void textUpToTheCapIsKeptWithoutAMarker() {
    var output = new CapturedOutput();

    output.append(AT_CAP);

    assertEquals(AT_CAP, output.drain());
  }

  @Test
  void textPastTheCapIsCountedAndTheDrainReportsIt() {
    var output = new CapturedOutput();

    output.append(AT_CAP);
    output.append("abc");
    output.append("de");

    assertEquals(AT_CAP + "\n[output truncated: 5 more chars discarded]", output.drain());
  }

  @Test
  void aDrainResetsTheCountAndTheRoom() {
    var output = new CapturedOutput();
    output.append(AT_CAP + "past");
    output.drain();

    output.append("next");

    assertEquals("next", output.drain());
    assertEquals("", output.drain());
  }

  @Test
  void linesAreSeparatedOnlyFromWhatIsAlreadyKept() {
    var output = new CapturedOutput();

    output.appendLine("first");
    output.appendLine("");
    output.appendLine("third");

    assertEquals("first\n\nthird", output.drain());
    output.appendLine("after a drain");
    assertEquals("after a drain", output.drain());
  }

  @Test
  void aSeparatorPastTheCapCountsAsDiscarded() {
    var output = new CapturedOutput();
    output.appendLine(AT_CAP);

    output.appendLine("abc");

    assertEquals(AT_CAP + "\n[output truncated: 4 more chars discarded]", output.drain());
  }

  @Test
  void aSurrogatePairTheCapWouldSplitIsDroppedWhole() {
    var output = new CapturedOutput();
    var kept = "x".repeat(MAX_CAPTURED_OUTPUT_CHARS - 1);

    output.append(kept + "😀");

    assertEquals(kept + "\n[output truncated: 2 more chars discarded]", output.drain());
  }

  @Test
  void aSurrogatePairEndingExactlyAtTheCapIsKept() {
    var output = new CapturedOutput();
    var text = "x".repeat(MAX_CAPTURED_OUTPUT_CHARS - 2) + "😀";

    output.append(text);

    assertEquals(text, output.drain());
  }

  @Test
  void aHighSurrogateBelowTheCapStaysForItsPairInTheNextDrain() {
    var output = new CapturedOutput();

    output.append("\uD83D");

    assertEquals("\uD83D", output.drain());
  }

  @Test
  void nothingIsKeptOnceAnythingWasDropped() {
    var output = new CapturedOutput();
    output.append("kept");
    output.discard(1);

    output.append("late");

    assertEquals("kept\n[output truncated: 5 more chars discarded]", output.drain());
  }

  @Test
  void takeReturnsTheKeptTextAndTheCountAndStartsOver() {
    var output = new CapturedOutput();
    output.append(AT_CAP + "xy");

    assertEquals(new CapturedOutput.Cut(AT_CAP, 2), output.take());
    assertEquals(new CapturedOutput.Cut("", 0), output.take());
  }

  @Test
  void aCutAppendedToABufferKeepsItsCount() {
    var output = new CapturedOutput();

    output.append(new CapturedOutput.Cut("kept", 7));

    assertEquals("kept\n[output truncated: 7 more chars discarded]", output.drain());
  }

  @Test
  void aCutWithNothingDroppedHasNoMarker() {
    assertEquals("kept", new CapturedOutput.Cut("kept", 0).text());
  }

  @Test
  void charactersDroppedBeforeTheBufferAreReported() {
    var output = new CapturedOutput();
    output.append("kept");

    output.discard(7);

    assertEquals("kept\n[output truncated: 7 more chars discarded]", output.drain());
  }
}
