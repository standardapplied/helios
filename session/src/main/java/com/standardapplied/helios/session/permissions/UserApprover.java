/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.session.ask.AskUserQuestionOption;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.QuestionGateway;
import java.util.List;

/**
 * The {@link ToolApprover} that asks the user through a {@link QuestionGateway}; see {@link
 * ToolApprover#askingUser}. Each request gets its own question id, so an answer resolves one call.
 */
record UserApprover(QuestionGateway gateway) implements ToolApprover {

  static final String ALLOW_LABEL = "Allow";
  static final String DENY_LABEL = "Deny";

  @Override
  public PermissionDecision approve(ApprovalRequest request) throws InterruptedException {
    var key = request.key();
    var question =
        new AskUserQuestionRequest(
            "perm-" + Ids.newId(),
            "Permission",
            "Allow " + DefaultPermissionEvaluator.describe(key) + "?",
            List.of(
                new AskUserQuestionOption(ALLOW_LABEL, request.reason()),
                new AskUserQuestionOption(DENY_LABEL, "Refuse this tool call")),
            false);
    var picked = gateway.ask(question).selectedLabels().getFirst().trim();
    return picked.equalsIgnoreCase(ALLOW_LABEL)
        ? PermissionDecision.allow("user allowed " + DefaultPermissionEvaluator.describe(key))
        : PermissionDecision.deny("user denied " + DefaultPermissionEvaluator.describe(key));
  }
}
