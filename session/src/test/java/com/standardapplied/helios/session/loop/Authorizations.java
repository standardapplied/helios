/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionDecision;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.tools.ToolBinding;
import java.util.function.Function;

/** Permission stages for loop tests. */
final class Authorizations {

  /** A permission stage that admits every call. */
  static final Function<ToolBinding.CanonicalCall, PermissionDecision> ADMIT_ALL =
      canonical -> PermissionDecision.allow("admitted by the test");

  /** A policy no rule of which decides any key. */
  static final Permission NO_RULE = Permission.empty(PermissionMode.DEFAULT);

  private Authorizations() {}
}
