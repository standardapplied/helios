/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.common.JsonSnapshot;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.session.ask.AskUserQuestionOption;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.QuestionGateway;
import com.standardapplied.helios.session.permissions.PermissionDecision;
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
  private static final int MAX_SHOWN_CHARS = 1024;

  @Override
  public PermissionDecision approve(ApprovalRequest request) throws InterruptedException {
    var action = request.key().describe();
    var question =
        new AskUserQuestionRequest(
            "perm-" + Ids.newId(),
            "Permission",
            "Allow " + shown(action) + "?\n\nArguments: " + request.arguments(),
            List.of(
                new AskUserQuestionOption(ALLOW_LABEL, shown(request.reason())),
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

  /**
   * {@code text} as one line that shows what it holds ({@link JsonSnapshot#escape}), cut to {@value
   * #MAX_SHOWN_CHARS} characters around a note, so no part of it can pass for another line or hide
   * and its end, where a key names the extras a call sets, always shows.
   */
  static String shown(String text) {
    return Strings.truncatedMiddle(
        JsonSnapshot.escape(text), MAX_SHOWN_CHARS, "… [%d more chars] …");
  }
}
