/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.hooks;

/**
 * The default {@link Hook#name()} every phase interface shares: the implementing class's simple
 * name, or its binary name when the class is anonymous and so has an empty simple name.
 */
final class HookNames {

  private HookNames() {}

  static String defaultName(Hook hook) {
    var type = hook.getClass();
    var simpleName = type.getSimpleName();
    return simpleName.isEmpty() ? type.getName() : simpleName;
  }
}
