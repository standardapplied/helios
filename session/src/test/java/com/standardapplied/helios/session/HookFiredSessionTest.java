/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.test.HookInputs.named;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.HookPhase;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A session's {@link QueryEvent.HookFired} names the deciding hook's phase and carries its outcome,
 * every registered secret redacted, while the loop acts on the hook's own outcome.
 */
final class HookFiredSessionTest {

  private static final String SECRET = "s3cr3t-value";
  private static final String MARKER = "<redacted:TOKEN>";

  private final WriteToolSessions sessions = new WriteToolSessions();

  private SessionOptions.Builder options() {
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", SECRET);
    return sessions
        .options(Permission.empty(PermissionMode.BYPASS_PERMISSIONS))
        .withSecretRegistry(secrets);
  }

  private static QueryEvent.HookFired onlyHookFired(WriteToolSessions.Run run) {
    var fired = run.events().eventsOf(QueryEvent.HookFired.class);
    assertEquals(1, fired.size(), "HookFired events");
    return fired.getFirst();
  }

  @Test
  void aBlockingGuardsEventCarriesItsRedactedReasonWhileTheCallStaysBlocked() {
    var guard = named("EtcGuard", (call, ctx) -> HookOutcome.block("no writes with " + SECRET));

    var run = WriteToolSessions.run(options().withHook(guard), WriteToolSessions.write("/etc/a"));

    var fired = onlyHookFired(run);
    assertEquals(HookPhase.PRE_TOOL_USE, fired.phase());
    assertEquals(HookOutcome.block("no writes with " + MARKER), fired.outcome());
    assertEquals(List.of(), sessions.executed);
    assertEquals(
        new ToolOutcome.Rejected("EtcGuard", "no writes with " + MARKER),
        run.onlyResult().outcome());
  }

  @Test
  void aMutatingGuardsEventCarriesRedactedArgumentsWhileTheToolReceivesTheHooksOwn() {
    var mutated = Map.<String, Object>of("path", "/tmp/a", "token", SECRET);
    var guard = named("Stamp", (call, ctx) -> HookOutcome.mutateArgs(mutated));

    var run = WriteToolSessions.run(options().withHook(guard), WriteToolSessions.write("/tmp/a"));

    var fired = onlyHookFired(run);
    assertEquals(HookPhase.PRE_TOOL_USE, fired.phase());
    assertEquals(
        HookOutcome.mutateArgs(Map.of("path", "/tmp/a", "token", MARKER)), fired.outcome());
    assertEquals(List.of(mutated), sessions.executed);
  }

  @Test
  void aPreModelTurnInjectionIsTheEventsOutcome() {
    var injected = new AtomicBoolean();
    PreModelTurnHook reminder =
        (history, ctx) ->
            injected.compareAndSet(false, true)
                ? HookOutcome.inject("remember the budget")
                : HookOutcome.cont();
    var model = ScriptedModel.newBuilder().withTextTurn("done").build();

    var run = WriteToolSessions.run(model, options().withModel(model).withHook(reminder));

    var fired = onlyHookFired(run);
    assertEquals(HookPhase.PRE_MODEL_TURN, fired.phase());
    assertEquals(HookOutcome.inject("remember the budget"), fired.outcome());
    assertTrue(
        run.model().calls().getFirst().stream()
            .anyMatch(message -> "remember the budget".equals(message.content())),
        "the model reads the injected message");
  }
}
