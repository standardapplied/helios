/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.skills;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Logs the loader's warnings as one line each: names read from disk, and exception messages that
 * repeat them, may hold a line break or another control character, which would split or forge a log
 * line, so every such character is written as a backslash, {@code u} and its hexadecimal code point
 * in braces, and a backslash as two, so a name holding that text cannot pass for an escape.
 */
final class SkillLog {

  private SkillLog() {}

  /** Log {@code message} at {@code WARNING} on {@code logger}, made {@link #printable}. */
  static void warn(Logger logger, Supplier<String> message) {
    logger.warning(() -> printable(message.get()));
  }

  /** Why reading a file or directory failed, as the warnings word it. */
  static String failed(IOException e) {
    return "reading it failed: " + e;
  }

  /** {@code text} with each backslash and control, format or separator character escaped. */
  static String printable(String text) {
    var printable = new StringBuilder(text.length());
    text.codePoints()
        .forEach(
            c -> {
              if (c == '\\') {
                printable.append("\\\\");
              } else if (unprintable(c)) {
                printable.append("\\u{").append(Integer.toHexString(c)).append('}');
              } else {
                printable.appendCodePoint(c);
              }
            });
    return printable.toString();
  }

  private static boolean unprintable(int c) {
    return Character.isISOControl(c)
        || switch (Character.getType(c)) {
          case Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
          default -> false;
        };
  }
}
