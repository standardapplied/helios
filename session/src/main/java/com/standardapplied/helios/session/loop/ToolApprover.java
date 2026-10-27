/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.session.ask.QuestionGateway;
import com.standardapplied.helios.session.permissions.PermissionDecision;
import java.util.Objects;

/**
 * Decides a tool call the policy answered with {@link PermissionEffect#ASK}. Only {@link
 * PermissionEffect#ALLOW} admits the call; {@link PermissionEffect#DENY} refuses it with this
 * approver's reason, and {@link PermissionEffect#ASK}, {@code null}, a thrown exception or an
 * interrupt refuse it too. The approver runs on the agent loop's thread and may block it. The
 * session does not interrupt it, so an approver that waits must bound or end its wait itself; the
 * human approver's wait ends when the session is cancelled or its wall-clock deadline passes.
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
   * {@code Allow <tool>(<key>)?}, a blank line and {@code Arguments: <arguments>} (see {@link
   * ApprovalRequest#arguments}), and options {@code Allow}, described by the request's reason, and
   * {@code Deny}; the key and the reason show escaped as JSON string text ({@link
   * com.standardapplied.helios.core.common.JsonSnapshot#escape}), cut after 1024 characters. It
   * asks under a fresh {@code perm-} question id per request. A response to that question selecting
   * only {@code Allow} (case-insensitive) allows the call; any other answer, a response to another
   * question or one selecting more than one option denies it.
   *
   * @param gateway the channel to the user; non-null
   * @return the approver
   * @throws NullPointerException if {@code gateway} is null
   */
  static ToolApprover askingUser(QuestionGateway gateway) {
    return new UserApprover(Objects.requireNonNull(gateway, "gateway must not be null"));
  }
}
