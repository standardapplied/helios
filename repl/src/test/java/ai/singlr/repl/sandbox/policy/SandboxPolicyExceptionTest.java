/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SandboxPolicyExceptionTest {

  @Test
  void carriesOwnerMemberAndRule() {
    var e = new SandboxPolicyException("java/lang/ProcessBuilder", "start", "deniedClasses");
    assertEquals("java/lang/ProcessBuilder", e.deniedOwner());
    assertEquals("start", e.deniedMember());
    assertEquals("deniedClasses", e.rule());
    assertTrue(e.getMessage().contains("java.lang.ProcessBuilder"));
    assertTrue(e.getMessage().contains("#start"));
    assertTrue(e.getMessage().contains("deniedClasses"));
  }

  @Test
  void messageHandlesNullOwner() {
    var e = new SandboxPolicyException(null, "lookup", "denyReflection");
    assertTrue(e.getMessage().contains("lookup"));
    assertTrue(e.getMessage().contains("denyReflection"));
    assertNull(e.deniedOwner());
  }

  @Test
  void messageHandlesNullMember() {
    var e = new SandboxPolicyException("java/net/http/HttpClient", null, "deniedPackages");
    assertTrue(e.getMessage().contains("java.net.http.HttpClient"));
    assertTrue(e.getMessage().contains("deniedPackages"));
    assertNull(e.deniedMember());
  }

  @Test
  void messageHandlesAllNull() {
    var e = new SandboxPolicyException(null, null, null);
    assertTrue(e.getMessage().contains("operation"));
    assertNull(e.rule());
  }

  @Test
  void messageOmitsRuleSuffixWhenNull() {
    var e = new SandboxPolicyException("java/lang/Runtime", "exec", null);
    assertTrue(e.getMessage().contains("java.lang.Runtime"));
    assertTrue(e.getMessage().contains("#exec"));
    assertFalseContains(e.getMessage(), "[rule:");
  }

  private static void assertFalseContains(String haystack, String needle) {
    if (haystack.contains(needle)) {
      throw new AssertionError("Expected message not to contain '" + needle + "': " + haystack);
    }
  }
}
