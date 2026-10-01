/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

/**
 * Identifier-validation helpers for host-function and host-parameter names. Names that flow into
 * synthesized JShell method signatures must be valid Java identifiers, otherwise the prelude won't
 * compile inside the sandbox.
 */
final class HostIdentifiers {

  private HostIdentifiers() {}

  /**
   * True iff {@code name} starts with a Java identifier start character and every subsequent
   * character is a Java identifier part. Equivalent to the rule the Java compiler applies to method
   * and parameter names.
   */
  static boolean isValidJavaIdentifier(String name) {
    if (name.isEmpty()) {
      return false;
    }
    if (!Character.isJavaIdentifierStart(name.charAt(0))) {
      return false;
    }
    for (var i = 1; i < name.length(); i++) {
      if (!Character.isJavaIdentifierPart(name.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
