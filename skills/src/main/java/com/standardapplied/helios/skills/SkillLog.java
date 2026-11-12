/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.skills;

import com.standardapplied.helios.session.files.ReadTool;
import java.io.IOException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Logs the loader's warnings, each one line on the logger {@code
 * com.standardapplied.helios.skills.Skills}: names read from disk, and exception messages that
 * repeat them, may hold a line break or another control character, which would split or forge a log
 * line, so every such character is written as a backslash, {@code u} and its hexadecimal code point
 * in braces, and a backslash as two, so a name holding that text cannot pass for an escape. A line
 * longer than {@link #MAX_LINE_CHARS} is cut.
 */
final class SkillLog {

  /** {@link ReadTool#MAX_LINE_CHARS}, the line cut {@code Read} and {@code Grep} apply. */
  static final int MAX_LINE_CHARS = ReadTool.MAX_LINE_CHARS;

  private static final Logger LOGGER = Logger.getLogger(Skills.class.getName());

  private SkillLog() {}

  /**
   * Log {@code template} formatted with {@code args} as one warning line, formatted only if logged.
   */
  static void warn(String template, Object... args) {
    LOGGER.warning(() -> line(template.formatted(args)));
  }

  /** Why reading a file or directory failed, as the warnings word it. */
  static String failed(IOException e) {
    return "reading it failed: " + e;
  }

  /** {@code text} made {@link #printable} and cut to {@link #MAX_LINE_CHARS} code points. */
  static String line(String text) {
    var line = printable(text);
    if (line.codePointCount(0, line.length()) <= MAX_LINE_CHARS) {
      return line;
    }
    return line.substring(0, line.offsetByCodePoints(0, MAX_LINE_CHARS)) + " [cut]";
  }

  /** {@code text} with each backslash and control, format or separator character escaped. */
  static String printable(String text) {
    return text.codePoints().mapToObj(SkillLog::escaped).collect(Collectors.joining());
  }

  private static String escaped(int c) {
    if (c == '\\') {
      return "\\\\";
    }
    var unprintable =
        Character.isISOControl(c)
            || switch (Character.getType(c)) {
              case Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR ->
                  true;
              default -> false;
            };
    return unprintable ? "\\u{" + Integer.toHexString(c) + "}" : Character.toString(c);
  }
}
