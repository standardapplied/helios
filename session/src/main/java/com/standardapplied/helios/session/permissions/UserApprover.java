/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.permissions;

import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.common.RedactionResult;
import com.standardapplied.helios.session.ask.AskUserQuestionOption;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.QuestionGateway;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
  static final int MAX_SHOWN_CHARS = 1024;

  @Override
  public PermissionDecision approve(ApprovalRequest request) throws InterruptedException {
    var action = DefaultPermissionEvaluator.describe(request.key());
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
   * {@code text} as one line that shows what it holds, cut to {@value #MAX_SHOWN_CHARS} characters:
   * {@code \} and every control, line separator and format character (a bidi override, a zero-width
   * character) escaped, so no part of it can pass for another line or hide.
   */
  static String shown(String text) {
    var bytes = escaped(text, false).getBytes(StandardCharsets.UTF_8);
    return new RedactionResult(bytes, Map.of())
        .truncated(MAX_SHOWN_CHARS, "… [%d more chars]")
        .text();
  }

  /**
   * {@code text} with its controls, line separators and format characters escaped as {@code \n},
   * {@code \r}, {@code \t} or {@code \}{@code uXXXX}, and {@code \} as {@code \\} unless {@code
   * json}: JSON text has its backslashes escaped already.
   */
  static String escaped(String text, boolean json) {
    return text.codePoints().mapToObj(c -> escaped(c, json)).collect(Collectors.joining());
  }

  private static String escaped(int c, boolean json) {
    return switch (c) {
      case '\\' -> json ? "\\" : "\\\\";
      case '\n' -> "\\n";
      case '\r' -> "\\r";
      case '\t' -> "\\t";
      default -> hides(c) ? unicodeEscapes(c) : Character.toString(c);
    };
  }

  private static boolean hides(int c) {
    return Character.isISOControl(c)
        || c == '\u2028'
        || c == '\u2029'
        || Character.getType(c) == Character.FORMAT;
  }

  private static String unicodeEscapes(int c) {
    return Character.toString(c)
        .chars()
        .mapToObj(unit -> "\\u%04x".formatted(unit))
        .collect(Collectors.joining());
  }
}
