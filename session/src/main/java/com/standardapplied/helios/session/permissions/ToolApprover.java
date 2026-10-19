/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.session.ask.QuestionGateway;
import java.util.Objects;

/**
 * Decides a tool call the policy answered with {@link PermissionEffect#ASK}. Only {@link
 * PermissionEffect#ALLOW} admits the call; {@link PermissionEffect#DENY} refuses it with this
 * approver's reason, and {@link PermissionEffect#ASK}, {@code null}, a thrown exception or an
 * interrupt refuse it too. The approver runs on the agent loop's thread and may block it.
 */
@FunctionalInterface
public interface ToolApprover {

  /**
   * Decide one call.
   *
   * @param request the call and why the policy asked; non-null
   * @return the decision for this call alone
   * @throws InterruptedException if the waiting thread is interrupted
   */
  PermissionDecision approve(ApprovalRequest request) throws InterruptedException;

  /**
   * The human approver: asks through {@code gateway} with header {@code Permission}, question
   * {@code Allow <tool>(<key>)?} and options {@code Allow} / {@code Deny}, under a fresh {@code
   * perm-} question id per request. A selection of {@code Allow} (case-insensitive) allows the
   * call; any other answer denies it.
   *
   * @param gateway the channel to the user; non-null
   * @return the approver
   * @throws NullPointerException if {@code gateway} is null
   */
  static ToolApprover askingUser(QuestionGateway gateway) {
    return new UserApprover(Objects.requireNonNull(gateway, "gateway must not be null"));
  }
}
