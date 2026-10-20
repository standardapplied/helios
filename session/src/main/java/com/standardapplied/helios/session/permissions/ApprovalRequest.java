/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.util.Objects;
import java.util.Optional;

/**
 * A tool call the policy referred to a {@link ToolApprover}: the exact call that runs if it is
 * approved, after every {@code PreToolUse} hook, and why the policy asked. An approval answers this
 * request only; any other call, however small the difference, is a request of its own.
 *
 * <p>{@code call} is the exact call that runs; {@code arguments} only displays it, so an approver
 * that decides on the arguments reads them from {@code call}.
 *
 * @param call the final, immutable call; non-null
 * @param arguments display text of the call's arguments: compact JSON with every session secret
 *     redacted, cut after 1024 characters with a note of how many were cut; non-null
 * @param key the call's permission key; non-null
 * @param category the tool's category; non-null
 * @param rule the ask rule that matched, empty when the mode or category default asked; non-null
 * @param reason the ASK decision's reason, redacted through the session's secrets; non-blank
 */
public record ApprovalRequest(
    ToolCall call,
    String arguments,
    ToolPermissionKey key,
    ToolCategory category,
    Optional<PermissionRule> rule,
    String reason) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code reason} is blank
   */
  public ApprovalRequest {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(arguments, "arguments must not be null");
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(category, "category must not be null");
    Objects.requireNonNull(rule, "rule must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    if (Strings.isBlank(reason)) {
      throw new IllegalArgumentException("reason must not be blank");
    }
  }
}
