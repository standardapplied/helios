/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.WriteToolSessions.run;
import static com.standardapplied.helios.session.WriteToolSessions.write;
import static com.standardapplied.helios.session.test.HookInputs.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookRegistry;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.test.LogCapture;
import com.standardapplied.helios.session.test.RedactedLogs;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.session.tools.ToolRegistry;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The permission stage of a session's tool dispatch: it authorizes the call every {@code
 * PreToolUse} hook left, fails closed, and the call it authorizes is the call that runs.
 */
final class ToolAuthorizationSessionTest {

  private static final String SECRET = "test-registered-secret-123";
  private static final PermissionRule ALLOW_TMP =
      PermissionRule.withGlob(PermissionEffect.ALLOW, "Write", "/tmp/**");
  private static final PermissionRule DENY_ETC =
      PermissionRule.withGlob(PermissionEffect.DENY, "Write", "/etc/**");

  private final WriteToolSessions sessions = new WriteToolSessions();
  private final List<Map<String, Object>> executed = sessions.executed;

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
        new ToolOutcome.Rejected("Permission", "Write: no path"), run.onlyResult().outcome());
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

  @Test
  void aDenyRuleCoversATargetWhoseKeyHoldsALineTerminator() {
    var run =
        run(
            options(policy(PermissionMode.ACCEPT_EDITS, List.of(DENY_ETC))),
            write("/etc/cron.d/x\ny"));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission", "deny rule Write(/etc/**) matched Write(/etc/cron.d/x\ny)"),
        run.onlyResult().outcome());
    assertEquals(List.of(), executed);
  }

  @ParameterizedTest(name = "the secret in its tool name: {0}")
  @ValueSource(booleans = {false, true})
  void aPermissionKeyCarryingASecretIsRefusedBeforeAnyoneIsAskedAboutIt(boolean inToolName) {
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", SECRET);
    var key =
        inToolName
            ? new ToolPermissionKey(SECRET, "/tmp/a")
            : new ToolPermissionKey("Write", SECRET);
    var options =
        SessionOptions.newBuilder()
            .withTools(new ToolRegistry(List.of(sessions.writeTool(args -> key))))
            .withPermission(Permission.empty(PermissionMode.DEFAULT))
            .withSecretRegistry(secrets);

    var run = run(options, write("/tmp/a"));

    assertEquals(
        new ToolOutcome.Rejected("Permission", "permission key of Write carries a secret"),
        run.onlyResult().outcome());
    assertEquals(List.of(), run.events().eventsOf(QueryEvent.QuestionAsked.class));
    assertEquals(List.of(), executed);
  }

  @ParameterizedTest(name = "under a policy: {0}")
  @ValueSource(booleans = {true, false})
  void aCallNamingNoRegisteredToolIsNotFoundWithOrWithoutAPolicy(boolean policed) {
    var options =
        SessionOptions.newBuilder().withTools(new ToolRegistry(List.of(sessions.writeTool())));
    if (policed) {
      options.withPermission(Permission.empty(PermissionMode.LOCKED_DOWN));
    }

    var run = run(options, new ToolCall("c1", "Missing", Map.of()));

    assertEquals(
        new ToolResult(new ToolOutcome.NotFound(), "tool not found: Missing", List.of()),
        run.onlyResult());
    assertEquals(List.of(), run.events().eventsOf(QueryEvent.ToolBlocked.class));
    assertEquals(List.of(), run.events().eventsOf(QueryEvent.QuestionAsked.class));
    assertEquals(List.of(), executed);
  }

  // ── a refusal's diagnostic ───────────────────────────────────────────────

  private static final String KEY_FAILED = "Write: ";

  /** A run whose {@code Write} key throws {@code message}, with {@link #SECRET} registered. */
  private WriteToolSessions.Run keyThrowing(String message) {
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", SECRET);
    var options =
        SessionOptions.newBuilder()
            .withTools(
                new ToolRegistry(
                    List.of(
                        sessions.writeTool(
                            args -> {
                              throw new IllegalStateException(message);
                            }))))
            .withPermission(Permission.empty(PermissionMode.BYPASS_PERMISSIONS))
            .withSecretRegistry(secrets);
    return run(options, write("/tmp/a"));
  }

  @Test
  void aRefusalReasonHasEverySecretRedactedAndTheCallIsNeverAnnounced() {
    var run = keyThrowing("path is " + SECRET);

    var reason = KEY_FAILED + "path is <redacted:TOKEN>";
    assertEquals(new ToolOutcome.Rejected("Permission", reason), run.onlyResult().outcome());
    assertEquals(reason, run.onlyBlock().reason());
    assertEquals(
        Map.of("TOKEN", 1),
        run.events().eventsOf(QueryEvent.ToolResult.class).getFirst().redactions());
    assertEquals(List.of(), run.events().eventsOf(QueryEvent.ToolUse.class));
  }

  @Test
  void aRefusalReasonIsCutAfter1024Chars() {
    var reason = KEY_FAILED + "x".repeat(2000);

    var run = keyThrowing("x".repeat(2000));

    var cut = reason.substring(0, 1024) + "… [" + (reason.length() - 1024) + " more chars]";
    assertEquals(new ToolOutcome.Rejected("Permission", cut), run.onlyResult().outcome());
    assertEquals(cut, run.onlyBlock().reason());
  }

  @Test
  void aSecretAcrossTheCutIsRedactedBeforeTheReasonIsCut() {
    var padding = "x".repeat(1020 - KEY_FAILED.length());

    var run = keyThrowing(padding + SECRET);

    var redacted = KEY_FAILED + padding + "<redacted:TOKEN>";
    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            redacted.substring(0, 1024) + "… [" + (redacted.length() - 1024) + " more chars]"),
        run.onlyResult().outcome());
    assertEquals(
        Map.of("TOKEN", 1),
        run.events().eventsOf(QueryEvent.ToolResult.class).getFirst().redactions());
  }

  // ── authorization follows every mutation ─────────────────────────────────

  @ParameterizedTest(name = "a mutating hook at priority {0}")
  @ValueSource(ints = {10, 90})
  void noHookPriorityTurnsADeniedTargetIntoAnExecutedCall(int priority) {
    var redirect =
        named(
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
        named("Retarget", 90, (call, ctx) -> HookOutcome.mutateArgs(Map.of("path", "/tmp/b")));
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
        named(
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
        named(
            "Recording",
            200,
            (call, ctx) -> {
              fired.add(call.id());
              return HookOutcome.cont();
            });
    var options =
        options(Permission.empty(PermissionMode.BYPASS_PERMISSIONS))
            .withHook(named("Guard", 100, guard))
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
  void aGuardThatThrowsASecretLogsARedactedCopyOfItsException() {
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", SECRET);
    var failure = new IllegalStateException("guard saw " + SECRET);
    var options =
        options(Permission.empty(PermissionMode.BYPASS_PERMISSIONS))
            .withSecretRegistry(secrets)
            .withHook(
                named(
                    "Guard",
                    100,
                    (call, ctx) -> {
                      throw failure;
                    }));
    var logged = new CopyOnWriteArrayList<LogRecord>();

    WriteToolSessions.Run run;
    try (var ignored = LogCapture.of(HookRegistry.class, logged)) {
      run = run(options, write("/tmp/a"));
    }

    assertEquals(
        new ToolOutcome.Rejected(
            "Guard", "hook threw java.lang.IllegalStateException: guard saw <redacted:TOKEN>"),
        run.onlyResult().outcome());
    assertEquals(1, logged.size(), "log records");
    var only = logged.getFirst();
    assertEquals(
        List.of(
            Level.WARNING,
            "PreToolUse hook Guard threw java.lang.IllegalStateException; refusing the call"),
        List.of(only.getLevel(), only.getMessage()));
    RedactedLogs.assertCopyOf(
        failure,
        only.getThrown(),
        List.of("java.lang.IllegalStateException: guard saw <redacted:TOKEN>"));
    assertFalse(RedactedLogs.text(only).contains(SECRET));
  }

  @Test
  void anErrorFromAGuardEscapesAndFailsTheResult() {
    var sentinel = new AssertionError("guard host failure");
    var options =
        options(Permission.empty(PermissionMode.BYPASS_PERMISSIONS))
            .withHook(
                named(
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
