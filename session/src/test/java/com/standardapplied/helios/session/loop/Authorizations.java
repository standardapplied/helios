/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.permissions.PermissionDecision;
import java.util.function.Function;

/** Permission stages for loop tests. */
final class Authorizations {

  /** A permission stage that admits every call. */
  static final Function<ToolCall, PermissionDecision> ADMIT_ALL =
      call -> PermissionDecision.allow("admitted by the test");

  private Authorizations() {}
}
