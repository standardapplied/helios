/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Locks the {@link Runtime} enum's wire form. Adding or renaming a value is a deliberate
 * protocol-level change that the model-facing tool docs and provider routing tables track — a
 * silent rename would mean every existing model prompt referencing the old name silently fails.
 */
final class RuntimeTest {

  @Test
  void valuesCoverEveryDeclaredRuntime() {
    var declared =
        EnumSet.of(
            Runtime.BASH,
            Runtime.PYTHON,
            Runtime.SQL,
            Runtime.JSHELL,
            Runtime.R,
            Runtime.NODE,
            Runtime.CUSTOM);
    assertEquals(declared, Set.of(Runtime.values()));
  }

  @Test
  void valueOfRoundTripsEveryWireName() {
    for (var r : Runtime.values()) {
      assertSame(r, Runtime.valueOf(r.name()));
    }
  }

  @Test
  void valueOfRejectsUnknownName() {
    assertThrows(IllegalArgumentException.class, () -> Runtime.valueOf("DELPHI"));
  }

  @Test
  void valueOfRejectsLowercase() {
    // The Execute tool intentionally Locale.ROOT-upper-cases the wire token before valueOf; this
    // test pins down what happens if a caller forgets that step.
    assertThrows(IllegalArgumentException.class, () -> Runtime.valueOf("bash"));
  }
}
