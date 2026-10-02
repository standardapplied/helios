/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class LineSinkTest {

  @Test
  void queuesEachTerminatedLineInOrder() {
    var sink = new LineSink();

    printTo(sink, "first\nsecond\npartial");

    assertEquals("first", sink.nextLine());
    assertEquals("second", sink.nextLine());

    printTo(sink, " then finished\n");

    assertEquals("partial then finished", sink.nextLine());
  }

  @Test
  void decodesALineAsUtf8() {
    var sink = new LineSink();

    printTo(sink, "naïve → ok\n");

    assertEquals("naïve → ok", sink.nextLine());
  }

  @Test
  void aLineOutlivesTheThreadThatWroteIt() {
    var sink = new LineSink();
    var writer = Thread.ofVirtual().start(() -> printTo(sink, "from a writer\n"));
    Await.termination("the writer thread", writer);

    assertEquals("from a writer", sink.nextLine());
  }

  @Test
  void nextLineWaitsForALineWrittenLater() {
    var sink = new LineSink();
    Thread.ofVirtual().start(() -> printTo(sink, "late\n"));

    assertEquals("late", sink.nextLine());
  }

  private static void printTo(LineSink sink, String text) {
    new PrintStream(sink, true, StandardCharsets.UTF_8).print(text);
  }
}
