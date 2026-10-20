/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.WriteToolSessions.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.execution.Runtime;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * {@code Execute}'s permission key is its whole script, and the approval question shows the call's
 * arguments, redacted and bounded: a deny rule sees every command the script runs, and a user who
 * allows a call has seen what runs.
 */
final class ExecutePermissionSessionTest {

  private static final String SECRET = "key-0123456789";
  private static final String DANGEROUS = "ls; rm -rf ~/";

  private final RecordingProvider provider = new RecordingProvider(new CopyOnWriteArrayList<>());
  private final SecretRegistry secrets = new SecretRegistry();

  ExecutePermissionSessionTest() {
    secrets.register("API_KEY", SECRET);
  }

  private SessionOptions.Builder options(Permission permission) {
    return SessionOptions.newBuilder()
        .withTools(new ToolRegistry(List.of(ExecuteTool.binding(provider))))
        .withExecutionProvider(provider)
        .withSecretRegistry(secrets)
        .withPermission(permission);
  }

  /** An {@code Execute} call whose arguments keep the order of {@code namesAndValues}. */
  private static ToolCall execute(Object... namesAndValues) {
    var arguments = new LinkedHashMap<String, Object>();
    for (var i = 0; i < namesAndValues.length; i += 2) {
      arguments.put((String) namesAndValues[i], namesAndValues[i + 1]);
    }
    return new ToolCall("c1", ExecuteTool.NAME, arguments);
  }

  private static String onlyQuestion(WriteToolSessions.Run run) {
    var asked = run.events().eventsOf(QueryEvent.QuestionAsked.class);
    assertEquals(1, asked.size(), "questions");
    return asked.getFirst().request().question();
  }

  @Test
  void aDenyRuleMatchesACommandAfterTheFirstOne() {
    var deny = PermissionRule.withGlob(PermissionEffect.DENY, ExecuteTool.NAME, "BASH/**rm **");
    var permission =
        new Permission(PermissionMode.ACCEPT_EDITS, List.of(), List.of(), List.of(deny));

    var run =
        run(
            options(permission),
            session -> QuestionAnswers.selecting(session, "Allow"),
            execute("runtime", "bash", "script", DANGEROUS));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission", "deny rule Execute(BASH/**rm **) matched Execute(BASH/ls; rm -rf ~/)"),
        run.onlyResult().outcome());
    assertEquals(List.of(), provider.executed);
  }

  @Test
  void theApprovalQuestionShowsTheArgumentsAndAllowRunsExactlyThem() {
    var run =
        run(
            options(Permission.empty(PermissionMode.DEFAULT)),
            session -> QuestionAnswers.selecting(session, "Allow"),
            execute("runtime", "bash", "script", DANGEROUS, "stdin", "y"));

    assertEquals(
        "Allow Execute(BASH/ls; rm -rf ~/)?\n\nArguments:"
            + " {\"runtime\":\"bash\",\"script\":\"ls; rm -rf ~/\",\"stdin\":\"y\"}",
        onlyQuestion(run));
    assertEquals(1, provider.executed.size());
    var request = provider.executed.getFirst();
    assertEquals(
        List.of(Runtime.BASH, DANGEROUS, Optional.of("y")),
        List.of(request.runtime(), request.script(), request.stdin()));
  }

  @Test
  void aSecretInTheEnvironmentIsRedactedInTheQuestionAndReachesTheProvider() {
    var run =
        run(
            options(Permission.empty(PermissionMode.DEFAULT)),
            session -> QuestionAnswers.selecting(session, "Allow"),
            execute("runtime", "bash", "script", "env", "environment", Map.of("TOKEN", SECRET)));

    assertEquals(
        "Allow Execute(BASH/env)?\n\nArguments: {\"runtime\":\"bash\",\"script\":\"env\","
            + "\"environment\":{\"TOKEN\":\"<redacted:API_KEY>\"}}",
        onlyQuestion(run));
    assertEquals(Map.of("TOKEN", SECRET), provider.executed.getFirst().environment());
  }

  @Test
  void longArgumentsAreCutAfterRedactionSoASecretAcrossTheCutIsNeverPartlyShown() {
    var opening = "{\"runtime\":\"bash\",\"script\":\"echo ";
    var closing = "\",\"stdin\":\"";
    var padding = "a".repeat(1020 - opening.length() - closing.length());
    var script = "echo " + padding;

    var run =
        run(
            options(Permission.empty(PermissionMode.DEFAULT)),
            session -> QuestionAnswers.selecting(session, "Deny"),
            execute("runtime", "bash", "script", script, "stdin", SECRET));

    assertEquals(
        "Allow Execute(BASH/"
            + script
            + ")?\n\nArguments: "
            + opening
            + padding
            + closing
            + "<red… [16 more chars]",
        onlyQuestion(run));
    assertEquals(List.of(), provider.executed);
  }

  @Test
  void aSecretInTheScriptRefusesTheCallWithoutAQuestion() {
    var run =
        run(
            options(Permission.empty(PermissionMode.DEFAULT)),
            session -> QuestionAnswers.selecting(session, "Allow"),
            execute("runtime", "bash", "script", "curl -H " + SECRET));

    assertEquals(
        new ToolOutcome.Rejected("Permission", "permission key of Execute carries a secret"),
        run.onlyResult().outcome());
    assertEquals(List.of(), run.events().eventsOf(QueryEvent.QuestionAsked.class));
    assertEquals(List.of(), provider.executed);
  }

  @Test
  void aSecretWhoseEdgeWhitespaceTheKeyStripsStillRefusesTheCallWithoutAQuestion() {
    secrets.register("PADDED", "token-0123456789 ");

    var run =
        run(
            options(Permission.empty(PermissionMode.DEFAULT)),
            session -> QuestionAnswers.selecting(session, "Allow"),
            execute("runtime", "bash", "script", "echo token-0123456789 "));

    assertEquals(
        new ToolOutcome.Rejected("Permission", "permission key of Execute carries a secret"),
        run.onlyResult().outcome());
    assertEquals(List.of(), run.events().eventsOf(QueryEvent.QuestionAsked.class));
    assertEquals(List.of(), provider.executed);
  }
}
