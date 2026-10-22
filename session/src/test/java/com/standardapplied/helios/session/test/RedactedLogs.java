/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.LogRecord;

/**
 * Checks on a log record that attaches the session's redacted copy of an exception rather than the
 * exception itself.
 */
public final class RedactedLogs {

  private RedactedLogs() {}

  /**
   * Assert {@code logged} is the redacted copy of {@code original} with nothing to redact: each
   * level reads {@code <class>: <message>} of the original's level and keeps its frames.
   *
   * @param original the exception that was thrown
   * @param logged what the log record attached
   */
  public static void assertCopyOf(Throwable original, Throwable logged) {
    var messages = new ArrayList<String>();
    for (var level = original; level != null; level = level.getCause()) {
      messages.add(level.getClass().getName() + ": " + level.getMessage());
    }
    assertCopyOf(original, logged, messages);
  }

  /**
   * Assert {@code logged} is a copy of {@code original}'s cause chain, a level for each of {@code
   * messages}, each level reading its message and keeping the original level's frames.
   *
   * @param original the exception that was thrown
   * @param logged what the log record attached
   * @param messages the copy's message at each level, outermost first
   */
  public static void assertCopyOf(Throwable original, Throwable logged, List<String> messages) {
    var source = original;
    var copy = logged;
    for (var message : messages) {
      assertNotSame(source, copy, "the exception itself was logged");
      assertEquals(message, copy.getMessage());
      assertArrayEquals(source.getStackTrace(), copy.getStackTrace());
      source = source.getCause();
      copy = copy.getCause();
    }
    assertNull(copy, "the copy's chain is longer than " + messages.size());
  }

  /**
   * Every string a log record carries: its message and, at each level of its attached exception's
   * cause chain, the level's text, frames and suppressed exceptions.
   *
   * @param logged the record
   * @return the strings, joined by new lines
   */
  public static String text(LogRecord logged) {
    var strings = new ArrayList<String>();
    strings.add(logged.getMessage());
    for (var level = logged.getThrown(); level != null; level = level.getCause()) {
      strings.add(level.toString());
      Arrays.stream(level.getStackTrace()).map(String::valueOf).forEach(strings::add);
      Arrays.stream(level.getSuppressed()).map(String::valueOf).forEach(strings::add);
    }
    return String.join("\n", strings);
  }
}
