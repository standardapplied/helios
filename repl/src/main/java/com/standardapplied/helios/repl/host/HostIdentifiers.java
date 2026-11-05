/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.host;

import com.standardapplied.helios.core.common.Strings;
import javax.lang.model.SourceVersion;

/**
 * Name validation for host functions and host parameters. Names flow into the synthesized JShell
 * wrappers, so each must be a Java identifier that is not a keyword or literal, otherwise the
 * wrappers would not compile inside the sandbox. A {@code __} name is refused too, so no host
 * function or parameter shadows {@code HostBridge.__call} or the wrapper's own locals. Identifier-
 * ignorable characters are refused because the compiler drops them: {@code print} with a zero-width
 * space inside would pass every string check yet compile as {@code print}.
 */
final class HostIdentifiers {

  private HostIdentifiers() {}

  /**
   * Throws unless {@code name} is a valid host name.
   *
   * @param name the name to check
   * @param label what the name is, such as {@code Host function name}, leading each message
   * @throws IllegalArgumentException if {@code name} is null, blank, contains an
   *     identifier-ignorable character, is not a Java identifier, is a keyword or literal, or
   *     starts with {@code __}
   */
  static void requireValid(String name, String label) {
    if (Strings.isBlank(name)) {
      throw new IllegalArgumentException(label + " must not be null or blank");
    }
    if (name.codePoints().anyMatch(Character::isIdentifierIgnorable)) {
      throw new IllegalArgumentException(
          label + " must not contain identifier-ignorable characters: " + name);
    }
    if (!SourceVersion.isIdentifier(name)
        || SourceVersion.isKeyword(name, SourceVersion.latest())
        || name.startsWith("__")) {
      throw new IllegalArgumentException(
          label + " must be a Java identifier, not a keyword, and not start with __: " + name);
    }
  }
}
