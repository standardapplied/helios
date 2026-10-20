/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.WriteToolSessions.run;
import static com.standardapplied.helios.session.WriteToolSessions.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.session.ask.AskUserQuestionOption;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * An ASK the permission policy makes in a session goes to the user through the session's questions:
 * only the user's {@code Allow} for this very call runs it, and an answer that is duplicated, late,
 * or never comes before the session is closed or its wall-clock deadline expires refuses it.
 */
final class ToolApprovalSessionTest {

  private final WriteToolSessions sessions = new WriteToolSessions();
  private final List<Map<String, Object>> executed = sessions.executed;

  private SessionOptions.Builder options(Permission permission) {
    return sessions.options(permission);
  }

  @Test
  void anAskIsAPermissionQuestionForTheFinalCall() {
    var run =
        run(
            options(Permission.empty(PermissionMode.DEFAULT)),
            session -> QuestionAnswers.selecting(session, "Deny"),
            write("/tmp/a"));

    var request = run.events().eventsOf(QueryEvent.QuestionAsked.class).getFirst().request();
    assertEquals("Permission", request.header());
    assertEquals("Allow Write(/tmp/a)?\n\nArguments: {\"path\":\"/tmp/a\"}", request.question());
    assertEquals(
        List.of("Allow", "Deny"),
        request.options().stream().map(AskUserQuestionOption::label).toList());
  }

  @Test
  void anAskRulePatternCarryingASecretIsRedactedInTheQuestion() {
    var askSecret = PermissionRule.withGlob(PermissionEffect.ASK, "Write", "secret*value");
    var secrets = new SecretRegistry();
    secrets.register("PASSWORD", "secret*value");
    var run =
        run(
            options(
                    new Permission(
                        PermissionMode.DEFAULT, List.of(), List.of(askSecret), List.of()))
                .withSecretRegistry(secrets),
            session -> QuestionAnswers.selecting(session, "Deny"),
            write("secretXvalue"));

    var request = run.events().eventsOf(QueryEvent.QuestionAsked.class).getFirst().request();
    assertEquals(
        "ask rule Write(<redacted:PASSWORD>) matched Write(secretXvalue)",
        request.options().getFirst().description());
    assertFalse(request.toString().contains("secret*value"));
  }

  @Test
  void theUserAllowingAnAskRunsTheCallOnce() {
    var run =
        run(
            options(Permission.empty(PermissionMode.DEFAULT)),
            session -> QuestionAnswers.selecting(session, "Allow"),
            write("/tmp/a"));

    assertEquals(new ToolOutcome.Succeeded(), run.onlyResult().outcome());
    assertEquals(List.of(Map.<String, Object>of("path", "/tmp/a")), executed);
  }

  @Test
  void theUserDenyingAnAskRefusesTheCall() {
    var run =
        run(
            options(Permission.empty(PermissionMode.DEFAULT)),
            session -> QuestionAnswers.selecting(session, "Deny"),
            write("/tmp/a"));

    assertEquals(
        new ToolOutcome.Rejected("Permission", "user denied Write(/tmp/a)"),
        run.onlyResult().outcome());
    assertEquals(List.of(), executed);
  }

  @Test
  void anAnswerSelectingBothAllowAndDenyRefusesTheCall() {
    Function<AgentSession, Consumer<QueryEvent>> reactions =
        session ->
            event -> {
              if (event instanceof QueryEvent.QuestionAsked question) {
                var id = question.request().questionId();
                session.answer(id, new AskUserQuestionResponse(id, List.of("Allow", "Deny"), ""));
              }
            };

    var run = run(options(Permission.empty(PermissionMode.DEFAULT)), reactions, write("/tmp/a"));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission", "approval of Write(/tmp/a) selected 2 options, not one"),
        run.onlyResult().outcome());
    assertEquals(List.of(), executed);
  }

  @Test
  void aDuplicateApprovalCannotAuthorizeASecondCall() {
    var duplicate = new CompletableFuture<RuntimeException>();
    var asked = new CopyOnWriteArrayList<String>();
    Function<AgentSession, Consumer<QueryEvent>> reactions =
        session ->
            event -> {
              if (event instanceof QueryEvent.QuestionAsked question) {
                var id = question.request().questionId();
                if (asked.isEmpty()) {
                  asked.add(id);
                  answer(session, id, "Allow");
                } else {
                  duplicate.complete(answer(session, asked.getFirst(), "Allow"));
                  answer(session, id, "Deny");
                }
              }
            };

    var run =
        run(
            options(Permission.empty(PermissionMode.DEFAULT)),
            reactions,
            write("/tmp/a"),
            new ToolCall("c2", "Write", Map.of("path", "/tmp/a")));

    assertEquals(
        "no pending question with id '" + asked.getFirst() + "' — already answered or unknown",
        Await.value("the duplicate", duplicate).getMessage());
    assertEquals(List.of(Map.<String, Object>of("path", "/tmp/a")), executed);
    assertEquals(
        List.of(
            new ToolOutcome.Succeeded(),
            new ToolOutcome.Rejected("Permission", "user denied Write(/tmp/a)")),
        run.events().eventsOf(QueryEvent.ToolResult.class).stream()
            .map(result -> result.result().outcome())
            .toList());
  }

  // ── an approval that never comes ─────────────────────────────────────────

  /**
   * A session asking about one {@code Write}, ended by {@code end} while the question is open, and
   * what an {@code Allow} answer to that question threw once the session had ended.
   */
  private record Unanswered(WriteToolSessions.Run run, RuntimeException lateAnswer) {}

  private Unanswered endedWhileAsking(InstantSource clock, Consumer<AgentSession> end) {
    var asked = new CompletableFuture<String>();
    var model =
        ScriptedModel.newBuilder().withToolCallsTurn(write("/tmp/a")).withTextTurn("never").build();
    var events =
        new CollectingSubscriber(
            event -> {
              if (event instanceof QueryEvent.QuestionAsked question) {
                asked.complete(question.request().questionId());
              }
            });
    var options =
        options(Permission.empty(PermissionMode.DEFAULT)).withModel(model).withClock(clock);
    try (var session = AgentSession.create(options.build())) {
      session.events().subscribe(events);
      session.send(UserMessage.text("go"));
      var questionId = Await.value("the approval question", asked);
      end.accept(session);
      var terminal = Await.value("the session to end", session.result());
      events.awaitDone();
      return new Unanswered(
          new WriteToolSessions.Run(events, model, terminal), answer(session, questionId, "Allow"));
    }
  }

  /** What answering {@code questionId} with {@code label} threw, or {@code null}. */
  private static RuntimeException answer(AgentSession session, String questionId, String label) {
    try {
      session.answer(questionId, AskUserQuestionResponse.single(questionId, label));
      return null;
    } catch (RuntimeException e) {
      return e;
    }
  }

  @Test
  void anApprovalCancelledByClosingTheSessionRefusesTheCall() {
    var unanswered = endedWhileAsking(Clock.systemUTC(), AgentSession::close);

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "approval of default-ask on WRITE (Write(/tmp/a)) cancelled: session closed"),
        unanswered.run().onlyResult().outcome());
    assertEquals(List.of(), executed);
  }

  @Test
  void aLateApprovalCannotAuthorizeTheCall() {
    var unanswered = endedWhileAsking(Clock.systemUTC(), AgentSession::close);

    assertInstanceOf(IllegalStateException.class, unanswered.lateAnswer());
    assertEquals("session is closed", unanswered.lateAnswer().getMessage());
    assertEquals(List.of(), executed);
  }

  @Test
  void anApprovalEndedByTheWallClockDeadlineRefusesTheCall() {
    var start = Instant.parse("2026-10-19T00:00:00Z");
    var now = new AtomicReference<>(start);
    var unanswered =
        endedWhileAsking(
            now::get,
            session -> {
              now.set(start.plus(SessionLimits.defaults().maxWallClock()).plusSeconds(1));
              ((AgentSessionImpl) session).lifecycleForTests().expireWallClock();
            });

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "approval of default-ask on WRITE (Write(/tmp/a)) cancelled:"
                + " maxWallClock exceeded after 3600000ms"),
        unanswered.run().onlyResult().outcome());
    assertInstanceOf(ResultMessage.ErrorMaxWallClock.class, unanswered.run().terminal());
    assertEquals(List.of(), executed);
    assertEquals(
        "no pending question with id '"
            + unanswered
                .run()
                .events()
                .eventsOf(QueryEvent.QuestionAsked.class)
                .getFirst()
                .request()
                .questionId()
            + "' — already answered or unknown",
        unanswered.lateAnswer().getMessage());
  }
}
