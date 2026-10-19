/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.session.permissions.PermissionDecision;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.util.function.Function;
import java.util.function.Predicate;

/** Permission stages for loop tests. */
final class Authorizations {

  /** A permission stage that admits every call. */
  static final Function<ToolBinding.CanonicalCall, PermissionDecision> ADMIT_ALL =
      canonical -> PermissionDecision.allow("admitted by the test");

  /** A withholding that denies no key. */
  static final Predicate<ToolPermissionKey> WITHHOLD_NONE = key -> false;

  private Authorizations() {}
}
