/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class PrincipalTest {

  private static final String REFUSAL =
      " must be non-blank, at most 256 characters and free of control characters";

  @Test
  void aPrincipalKeepsItsTenantAndSubject() {
    var principal = new Principal("acme", "alice@acme.example");
    assertEquals("acme", principal.tenant());
    assertEquals("alice@acme.example", principal.subject());
  }

  @Test
  void principalsAreEqualOnlyWhenBothPartsAre() {
    var alice = new Principal("acme", "alice");
    assertEquals(alice, new Principal("acme", "alice"));
    assertNotEquals(alice, new Principal("acme", "bob"));
    assertNotEquals(alice, new Principal("globex", "alice"));
  }

  @Test
  void aNullTenantIsRefused() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new Principal(null, "alice"));
    assertEquals("tenant" + REFUSAL, ex.getMessage());
  }

  @Test
  void aBlankSubjectIsRefused() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new Principal("acme", " "));
    assertEquals("subject" + REFUSAL, ex.getMessage());
  }

  @Test
  void aPartAtTheLengthLimitIsAcceptedAndOneLongerIsRefused() {
    var atLimit = "a".repeat(Principal.MAX_LENGTH);
    assertEquals(atLimit, new Principal("acme", atLimit).subject());
    assertThrows(IllegalArgumentException.class, () -> new Principal("acme", atLimit + "a"));
  }

  @Test
  void aControlCharacterIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> new Principal("acme", "alice\nadmin"));
  }
}
