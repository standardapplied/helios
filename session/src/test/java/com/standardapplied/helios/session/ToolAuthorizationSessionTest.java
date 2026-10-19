/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.WriteToolSessions.run;
import static com.standardapplied.helios.session.WriteToolSessions.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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

  private final WriteToolSessions sessions = new WriteToolSessions();
  private final List<Map<String, Object>> executed = sessions.executed;

  /** A named {@code PreToolUse} hook at {@code priority}. */
  private record Named(String name, int priority, PreToolUseHook body) implements PreToolUseHook {

    @Override
    public HookOutcome beforeTool(ToolCall call, HookContext ctx) {
      return body.beforeTool(call, ctx);
    }
  }

  private static Permission policy(PermissionMode mode, List<PermissionRule> rules) {
    var allow = rules.stream().filter(r -> r.effect() == PermissionEffect.ALLOW).toList();
    var ask = rules.stream().filter(r -> r.effect() == PermissionEffect.ASK).toList();
    var deny = rules.stream().filter(r -> r.effect() == PermissionEffect.DENY).toList();
    return new Permission(mode, allow, ask, deny);
  }

  private SessionOptions.Builder options(Permission permission) {
    return sessions.options(permission);
  }

  // ── fail closed ──────────────────────────────────────────────────────────

  @Test
  void aWriteToolWhosePermissionKeyThrowsNeverRunsUnderLockedDown() {
    var options =
        SessionOptions.newBuilder()
            .withTools(
                new ToolRegistry(
                    List.of(
                        sessions.writeTool(
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
}
