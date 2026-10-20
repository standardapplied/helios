/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.session.ask.AskUserQuestionOption;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.QuestionGateway;
import java.util.List;

/**
 * The {@link ToolApprover} that asks the user through a {@link QuestionGateway}; see {@link
 * ToolApprover#askingUser}. The question names the call by its permission key and shows its
 * arguments, redacted and bounded, so the user sees what runs, not only what the rules saw. Each
 * request gets its own question id, so an answer resolves one call: a response to another question
 * id, or one that selects other than exactly one option, denies it.
 */
record UserApprover(QuestionGateway gateway) implements ToolApprover {

  static final String ALLOW_LABEL = "Allow";
  static final String DENY_LABEL = "Deny";

  @Override
  public PermissionDecision approve(ApprovalRequest request) throws InterruptedException {
    var action = DefaultPermissionEvaluator.describe(request.key());
    var question =
        new AskUserQuestionRequest(
            "perm-" + Ids.newId(),
            "Permission",
            "Allow " + action + "?\n\nArguments: " + request.arguments(),
            List.of(
                new AskUserQuestionOption(ALLOW_LABEL, request.reason()),
                new AskUserQuestionOption(DENY_LABEL, "Refuse this tool call")),
            false);
    var response = gateway.ask(question);
    if (!question.questionId().equals(response.questionId())) {
      return PermissionDecision.deny(
          "approval of " + action + " answered another question: " + response.questionId());
    }
    if (response.selectedLabels().size() != 1) {
      return PermissionDecision.deny(
          "approval of "
              + action
              + " selected "
              + response.selectedLabels().size()
              + " options, not one");
    }
    return response.selectedLabels().getFirst().trim().equalsIgnoreCase(ALLOW_LABEL)
        ? PermissionDecision.allow("user allowed " + action)
        : PermissionDecision.deny("user denied " + action);
  }
}
