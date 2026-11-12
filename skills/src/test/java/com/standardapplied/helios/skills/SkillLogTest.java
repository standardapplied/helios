/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.session.test.LogCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/** A log line keeps every printable character and escapes each one that could split or hide it. */
final class SkillLogTest {

  @Test
  void aBackslashIsDoubledSoTheTextOfAnEscapeCannotPassForOne() {
    assertEquals("a\\\\u{a}b \\\\ c", SkillLog.printable("a\\u{a}b \\ c"));
  }

  @Test
  void controlFormatAndSeparatorCharactersAreEscapedAndTheRestKept() {
    var text =
        "a\tb\nc\rd"
            + Character.toString(0x85)
            + Character.toString(0x200B)
            + Character.toString(0x202E)
            + Character.toString(0x2028)
            + Character.toString(0x2029)
            + "é&<>\"😀";

    assertEquals(
        "a\\u{9}b\\u{a}c\\u{d}d\\u{85}\\u{200b}\\u{202e}\\u{2028}\\u{2029}é&<>\"😀",
        SkillLog.printable(text));
  }

  @Test
  void aWarningLongerThan2000CharactersIsCutAndMarked() {
    var records = new ArrayList<LogRecord>();

    try (var ignored = LogCapture.of(Skills.class, records)) {
      SkillLog.warn("Skill %s: loaded", "n".repeat(5_000));
    }

    assertEquals(2_000, SkillLog.MAX_LINE_CHARS);
    assertEquals(
        List.of("Skill " + "n".repeat(1_994) + " [cut]"),
        records.stream().map(LogRecord::getMessage).toList());
  }
}
