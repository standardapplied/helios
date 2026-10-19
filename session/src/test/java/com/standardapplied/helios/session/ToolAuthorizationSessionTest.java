/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.ask.AskUserQuestionOption;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.CollectingSubscriber;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The permission stage of a session's tool dispatch: it authorizes the call every {@code
 * PreToolUse} hook left, fails closed, and the call it authorizes is the call that runs.
 */
final class ToolAuthorizationSessionTest {

  private static final PermissionRule ALLOW_TMP =
      PermissionRule.withGlob(PermissionEffect.ALLOW, "Write", "/tmp/**");
  private static final PermissionRule DENY_ETC =
      PermissionRule.withGlob(PermissionEffect.DENY, "Write", "/etc/**");

  /** Every call the {@code Write} tool executed, by its arguments. */
  private final List<Map<String, Object>> executed = new CopyOnWriteArrayList<>();

  /** A named {@code PreToolUse} hook at {@code priority}. */
  private record Named(String name, int priority, PreToolUseHook body) implements PreToolUseHook {

    @Override
    public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
      return body.beforeTool(call, ctx);
    }
  }

  private record Run(CollectingSubscriber events, ResultMessage terminal) {

    ToolResult onlyResult() {
      var results = events.eventsOf(QueryEvent.ToolResult.class);
      assertEquals(1, results.size(), "tool results");
      return results.getFirst().result();
    }

    QueryEvent.ToolBlocked onlyBlock() {
      var blocks = events.eventsOf(QueryEvent.ToolBlocked.class);
      assertEquals(1, blocks.size(), "blocks");
      return blocks.getFirst();
    }
  }

  private ToolBinding writeTool() {
    return writeTool(args -> new ToolPermissionKey("Write", (String) args.get("path")));
  }

  private ToolBinding writeTool(Function<Map<String, Object>, ToolPermissionKey> key) {
    var tool =
        Tool.newBuilder()
            .withName("Write")
            .withDescription("write")
            .withExecutor(
                (args, ctx) -> {
                  executed.add(args);
                  return ToolResult.success("wrote");
                })
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.WRITE)
        .withPermissionKeyExtractor(key)
        .build();
  }

  private static ToolCall write(String path) {
    return new ToolCall("c1", "Write", Map.of("path", path));
  }

  private static Permission policy(PermissionMode mode, List<PermissionRule> rules) {
    var allow = rules.stream().filter(r -> r.effect() == PermissionEffect.ALLOW).toList();
    var ask = rules.stream().filter(r -> r.effect() == PermissionEffect.ASK).toList();
    var deny = rules.stream().filter(r -> r.effect() == PermissionEffect.DENY).toList();
    return new Permission(mode, allow, ask, deny);
  }

  private SessionOptions.Builder options(Permission permission) {
    return SessionOptions.newBuilder()
        .withTools(new ToolRegistry(List.of(writeTool())))
        .withPermission(permission);
  }

  private static Run run(SessionOptions.Builder options, ToolCall... calls) {
    return run(options, session -> event -> {}, calls);
  }

  private static Run run(
      SessionOptions.Builder options,
      Function<AgentSession, Consumer<QueryEvent>> reactions,
      ToolCall... calls) {
    var model = ScriptedModel.newBuilder().withToolCallsTurn(calls).withTextTurn("done").build();
    try (var session = AgentSession.create(options.withModel(model).build())) {
      var events = new CollectingSubscriber(reactions.apply(session));
      session.events().subscribe(events);
      session.send(UserMessage.text("go"));
      var terminal = Await.value("the session to end", session.result());
      events.awaitDone();
      return new Run(events, terminal);
    }
  }

  // ── fail closed ──────────────────────────────────────────────────────────

  @Test
  void aWriteToolWhosePermissionKeyThrowsNeverRunsUnderLockedDown() {
    var options =
        SessionOptions.newBuilder()
            .withTools(
                new ToolRegistry(
                    List.of(
                        writeTool(
                            args -> {
                              throw new IllegalStateException("no path");
                            }))))
            .withPermission(Permission.empty(PermissionMode.LOCKED_DOWN));

    var run = run(options, write("/tmp/a"));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "permission key of Write failed: java.lang.IllegalStateException: no path"),
        run.onlyResult().outcome());
    assertEquals(List.of(), executed);
  }

  @Test
  void anExplicitDenyAlongsideAnAllowRefusesTheCall() {
    var allowEtc = PermissionRule.withGlob(PermissionEffect.ALLOW, "Write", "/etc/**");

    var run =
        run(
            options(policy(PermissionMode.DEFAULT, List.of(allowEtc, DENY_ETC))),
            write("/etc/passwd"));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission", "deny rule Write(/etc/**) matched Write(/etc/passwd)"),
        run.onlyResult().outcome());
    assertEquals(List.of(), executed);
  }

  // ── authorization follows every mutation ─────────────────────────────────

  @ParameterizedTest(name = "a mutating hook at priority {0}")
  @ValueSource(ints = {10, 90})
  void noHookPriorityTurnsADeniedTargetIntoAnExecutedCall(int priority) {
    var redirect =
        new Named(
            "Redirect", priority, (call, ctx) -> HookOutcome.mutateArgs(Map.of("path", "/etc/x")));
    var options =
        options(policy(PermissionMode.DEFAULT, List.of(ALLOW_TMP, DENY_ETC))).withHook(redirect);

    var run = run(options, write("/tmp/a"));

    assertEquals(
        new ToolOutcome.Rejected("Permission", "deny rule Write(/etc/**) matched Write(/etc/x)"),
        run.onlyResult().outcome());
    assertEquals(List.of(), executed);
  }

  @Test
  void anAllowedMutationRunsOnceWithExactlyTheAuthorizedArguments() {
    var retarget =
        new Named("Retarget", 90, (call, ctx) -> HookOutcome.mutateArgs(Map.of("path", "/tmp/b")));
    var options =
        options(policy(PermissionMode.DEFAULT, List.of(ALLOW_TMP, DENY_ETC))).withHook(retarget);

    var run = run(options, write("/tmp/a"));

    var authorized = run.events().eventsOf(QueryEvent.ToolUse.class).getFirst().call();
    assertEquals(write("/tmp/b"), authorized);
    assertEquals(List.of(authorized.arguments()), executed);
  }

  @Test
  void aHookThatMutatesTheNestedArgumentsItReceivedRefusesTheCall() {
    PreToolUseHook inPlace =
        new Named(
            "InPlace",
            100,
            (call, ctx) -> {
              ((Map<?, ?>) call.arguments().get("options")).clear();
              return HookOutcome.cont();
            });
    var options = options(Permission.empty(PermissionMode.BYPASS_PERMISSIONS)).withHook(inPlace);

    var run =
        run(
            options,
            new ToolCall(
                "c1", "Write", Map.of("path", "/tmp/a", "options", Map.of("mode", "keep"))));

    assertEquals(
        new ToolOutcome.Rejected(
            "InPlace", "hook threw java.lang.UnsupportedOperationException: null"),
        run.onlyResult().outcome());
    assertEquals(List.of(), executed);
  }

  // ── a guard fails closed ─────────────────────────────────────────────────

  private void assertAFailedGuardRefuses(PreToolUseHook guard, String reason) {
    var fired = new CopyOnWriteArrayList<String>();
    var recording =
        new Named(
            "Recording",
            200,
            (call, ctx) -> {
              fired.add(call.id());
              return HookOutcome.cont();
            });
    var options =
        options(Permission.empty(PermissionMode.BYPASS_PERMISSIONS))
            .withHook(new Named("Guard", 100, guard))
            .withHook(recording);

    var run = run(options, write("/tmp/a"));

    assertEquals(new ToolOutcome.Rejected("Guard", reason), run.onlyResult().outcome());
    assertEquals("Guard", run.onlyBlock().hookName());
    assertEquals(List.of(), fired);
    assertEquals(List.of(), executed);
  }

  @Test
  void aGuardThatThrowsRefusesTheCall() {
    assertAFailedGuardRefuses(
        (call, ctx) -> {
          throw new IllegalStateException("guard broke");
        },
        "hook threw java.lang.IllegalStateException: guard broke");
  }

  @Test
  void aGuardThatReturnsNullRefusesTheCall() {
    assertAFailedGuardRefuses((call, ctx) -> null, "hook returned null");
  }

  @Test
  void anErrorFromAGuardEscapesAndFailsTheResult() {
    var sentinel = new AssertionError("guard host failure");
    var options =
        options(Permission.empty(PermissionMode.BYPASS_PERMISSIONS))
            .withHook(
                new Named(
                    "Guard",
                    100,
                    (call, ctx) -> {
                      throw sentinel;
                    }))
            .withModel(
                ScriptedModel.newBuilder()
                    .withToolCallsTurn(write("/tmp/a"))
                    .withTextTurn("never")
                    .build());

    try (var session = AgentSession.create(options.build())) {
      session.send(UserMessage.text("go"));

      assertSame(sentinel, Await.failure("the result to fail", session.result()));
    }
    assertEquals(List.of(), executed);
  }

  // ── the human approver ───────────────────────────────────────────────────

  @Test
  void anAskIsAPermissionQuestionForTheFinalCall() {
    var run =
        run(
            options(Permission.empty(PermissionMode.DEFAULT)),
            session -> QuestionAnswers.selecting(session, "Deny"),
            write("/tmp/a"));

    var request = run.events().eventsOf(QueryEvent.QuestionAsked.class).getFirst().request();
    assertEquals("Permission", request.header());
    assertEquals("Allow Write(/tmp/a)?", request.question());
    assertEquals(
        List.of("Allow", "Deny"),
        request.options().stream().map(AskUserQuestionOption::label).toList());
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
  void aDuplicateApprovalCannotAuthorizeASecondCall() {
    var duplicate = new CompletableFuture<Throwable>();
    var asked = new ArrayList<String>();
    Function<AgentSession, Consumer<QueryEvent>> reactions =
        session ->
            event -> {
              if (event instanceof QueryEvent.QuestionAsked question) {
                var id = question.request().questionId();
                asked.add(id);
                var label = asked.size() == 1 ? "Allow" : "Deny";
                session.answer(id, AskUserQuestionResponse.single(id, label));
                if (asked.size() == 1) {
                  try {
                    session.answer(id, AskUserQuestionResponse.single(id, "Allow"));
                    duplicate.complete(null);
                  } catch (IllegalArgumentException e) {
                    duplicate.complete(e);
                  }
                }
              }
            };

    var run =
        run(
            options(Permission.empty(PermissionMode.DEFAULT)),
            reactions,
            write("/tmp/a"),
            new ToolCall("c2", "Write", Map.of("path", "/tmp/a")));

    assertInstanceOf(IllegalArgumentException.class, Await.value("the duplicate", duplicate));
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
  private record Unanswered(Run run, RuntimeException lateAnswer) {}

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
      return new Unanswered(new Run(events, terminal), lateAnswer(session, questionId));
    }
  }

  private static RuntimeException lateAnswer(AgentSession session, String questionId) {
    try {
      session.answer(questionId, AskUserQuestionResponse.single(questionId, "Allow"));
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
