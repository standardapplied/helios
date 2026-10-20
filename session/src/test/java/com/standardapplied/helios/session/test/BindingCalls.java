/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.test;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;

/** A tool binding called outside a session: the key a call is authorised on, and its context. */
public final class BindingCalls {

  private BindingCalls() {}

  /** The permission key of a call of {@code binding} with {@code args}. */
  public static ToolPermissionKey keyOf(ToolBinding binding, Map<String, Object> args) {
    return binding.canonicalize(new ToolCall("call-1", binding.name(), args)).key();
  }

  /** A call context whose policy withholds every key {@code withheld} matches. */
  public static ToolContext withholding(Predicate<String> withheld) {
    return new ToolContext(
        SessionContext.forTesting("withholding"),
        new CancellationToken(),
        Duration.ofMinutes(1),
        withheld);
  }
}
