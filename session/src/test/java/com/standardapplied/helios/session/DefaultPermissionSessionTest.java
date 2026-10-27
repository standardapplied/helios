/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.WriteToolSessions.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * A session given no permission policy asks before it runs an execution call, so no such call runs
 * without an answered approval, and runs a read without asking.
 */
final class DefaultPermissionSessionTest {

  private final List<String> executed = new CopyOnWriteArrayList<>();

  private ToolBinding recording(String name, ToolCategory category) {
    var tool =
        Tool.newBuilder()
            .withName(name)
            .withDescription(name)
            .withExecutor(
                (args, ctx) -> {
                  executed.add(name);
                  return ToolResult.success("ran");
                })
            .build();
    return ToolBinding.newBuilder(tool).withCategory(category).build();
  }

  private SessionOptions.Builder withoutAPolicy() {
    return SessionOptions.newBuilder()
        .withTools(
            List.of(
                recording("Run", ToolCategory.EXECUTION), recording("Look", ToolCategory.READ)));
  }

  private static ToolCall call(String name) {
    return new ToolCall("c1", name, Map.of());
  }

  private static String onlyQuestionId(WriteToolSessions.Run run) {
    var asked = run.events().eventsOf(QueryEvent.QuestionAsked.class);
    assertEquals(1, asked.size(), "questions");
    return asked.getFirst().request().questionId();
  }

  @Test
  void anExecutionCallAsksAndADenyLeavesItUnrun() {
    var run =
        run(withoutAPolicy(), session -> QuestionAnswers.selecting(session, "Deny"), call("Run"));

    assertTrue(onlyQuestionId(run).startsWith("perm-"), onlyQuestionId(run));
    assertEquals(
        new ToolOutcome.Rejected("Permission", "user denied Run"), run.onlyResult().outcome());
    assertEquals(List.of(), executed);
  }

  @Test
  void anExecutionCallTheUserAllowsRunsOnce() {
    var run =
        run(withoutAPolicy(), session -> QuestionAnswers.selecting(session, "Allow"), call("Run"));

    assertTrue(onlyQuestionId(run).startsWith("perm-"), onlyQuestionId(run));
    assertEquals(new ToolOutcome.Succeeded(), run.onlyResult().outcome());
    assertEquals(List.of("Run"), executed);
  }

  @Test
  void aReadCallRunsWithoutAQuestion() {
    var run = run(withoutAPolicy(), call("Look"));

    assertEquals(List.of(), run.events().eventsOf(QueryEvent.QuestionAsked.class));
    assertEquals(new ToolOutcome.Succeeded(), run.onlyResult().outcome());
    assertEquals(List.of("Look"), executed);
  }
}
