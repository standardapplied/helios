/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PostToolUseHook;
import com.standardapplied.helios.session.hooks.PreToolUseHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionDecision;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The permission stage between the {@code PreToolUse} hooks and dispatch, run through the loop: it
 * fails closed whatever the check returns or throws, and an ASK goes to the {@link ToolApprover}
 * with the exact call that runs.
 */
final class ToolAuthorizationTest {

  private static final PermissionRule ASK_WRITE = PermissionRule.of("Write", "/tmp/**");
  private static final ToolCall WRITE = new ToolCall("c1", "Write", Map.of("path", "/tmp/a"));

  private final HookLoop loop = new HookLoop();
  private final List<Map<String, Object>> executed = new CopyOnWriteArrayList<>();
  private final List<ApprovalRequest> requests = new CopyOnWriteArrayList<>();

  private ToolRegistry writeTool() {
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
    return new ToolRegistry(
        List.of(
            ToolBinding.newBuilder(tool)
                .withCategory(ToolCategory.WRITE)
                .withPermissionKeyExtractor(
                    args -> new ToolPermissionKey("Write", (String) args.get("path")))
                .build()));
  }

  private ToolOutcome outcomeOf(
      Function<ToolBinding.CanonicalCall, PermissionDecision> authorization, Hook... hooks) {
    var model = ScriptedModel.newBuilder().withToolCallsTurn(WRITE).withTextTurn("done").build();
    loop.run(model, writeTool(), authorization, UserMessage.text("write"), hooks);
    return loop.first(QueryEvent.ToolResult.class).result().outcome();
  }

  private Function<ToolBinding.CanonicalCall, PermissionDecision> asking(
      Permission permission, ToolApprover approver) {
    return new DefaultPermissionEvaluator(
            permission,
            writeTool(),
            request -> {
              requests.add(request);
              return approver.approve(request);
            },
            new SecretRegistry())
        ::authorize;
  }

  private Function<ToolBinding.CanonicalCall, PermissionDecision> askRule(ToolApprover approver) {
    return asking(
        new Permission(PermissionMode.DEFAULT, List.of(), List.of(ASK_WRITE), List.of()), approver);
  }

  // ── the check fails closed ───────────────────────────────────────────────

  @Test
  void aNullDecisionRefusesTheCall() {
    assertEquals(
        new ToolOutcome.Rejected("Permission", "no permission decision for Write"),
        outcomeOf(call -> null));
    assertEquals(List.of(), executed);
  }

  @Test
  void aCheckThatThrowsRefusesTheCall() {
    Function<ToolBinding.CanonicalCall, PermissionDecision> throwing =
        call -> {
          throw new IllegalStateException("policy store down");
        };
    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "permission check of Write threw java.lang.IllegalStateException: policy store down"),
        outcomeOf(throwing));
    assertEquals(List.of(), executed);
  }

  @Test
  void anAskDecisionReachingTheStageRefusesTheCall() {
    assertEquals(
        new ToolOutcome.Rejected("Permission", "unresolved"),
        outcomeOf(call -> PermissionDecision.ask("unresolved")));
    assertEquals(List.of(), executed);
  }

  // ── the approver ─────────────────────────────────────────────────────────

  @Test
  void theApproverIsAskedAboutTheFinalCallAndItsAllowRunsItOnce() {
    PreToolUseHook retarget = (call, ctx) -> HookOutcome.mutateArgs(Map.of("path", "/tmp/b"));
    var finalCall = new ToolCall("c1", "Write", Map.of("path", "/tmp/b"));

    assertEquals(
        new ToolOutcome.Succeeded(),
        outcomeOf(askRule(request -> PermissionDecision.allow("reviewed")), retarget));
    assertEquals(
        List.of(
            new ApprovalRequest(
                finalCall,
                "{\"path\":\"/tmp/b\"}",
                new ToolPermissionKey("Write", "/tmp/b"),
                ToolCategory.WRITE,
                Optional.of(ASK_WRITE),
                "ask rule Write(/tmp/**) matched Write(/tmp/b)")),
        requests);
    assertEquals(List.of(finalCall.arguments()), executed);
  }

  @Test
  void theApproverIsAskedAboutTheCanonicalCallThatRuns(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve("docs"));
    var write =
        Tool.newBuilder()
            .withName("Write")
            .withDescription("write")
            .withExecutor(
                (args, ctx) -> {
                  executed.add(args);
                  return ToolResult.success("wrote");
                })
            .build();
    var tools =
        new ToolRegistry(
            List.of(
                ToolBinding.newBuilder(write)
                    .withCategory(ToolCategory.WRITE)
                    .withPathArgument(
                        ToolBinding.PathArgument.required("path", WorkspaceRoot.of(tmp)))
                    .build()));
    var askDocs = PermissionRule.of("Write", "./docs/**");
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "Write", Map.of("path", "docs/../docs/a.txt")))
            .withTextTurn("done")
            .build();

    loop.run(
        model,
        tools,
        new DefaultPermissionEvaluator(
                new Permission(PermissionMode.DEFAULT, List.of(), List.of(askDocs), List.of()),
                tools,
                request -> {
                  requests.add(request);
                  return PermissionDecision.allow("reviewed");
                },
                new SecretRegistry())
            ::authorize,
        UserMessage.text("write"));

    var canonical = new ToolCall("c1", "Write", Map.of("path", "./docs/a.txt"));
    assertEquals(List.of(canonical), requests.stream().map(ApprovalRequest::call).toList());
    assertEquals(List.of(canonical.arguments()), executed);
  }

  @Test
  void theApproversDenyRefusesWithItsReason() {
    assertEquals(
        new ToolOutcome.Rejected("Permission", "no"),
        outcomeOf(askRule(request -> PermissionDecision.deny("no"))));
    assertEquals(List.of(), executed);
  }

  @Test
  void aCategoryDefaultAskReachesTheApproverWithNoRule() {
    outcomeOf(
        asking(Permission.empty(PermissionMode.DEFAULT), request -> PermissionDecision.deny("no")));
    assertEquals(Optional.empty(), requests.getFirst().rule());
  }

  @Test
  void anApproverReturningNullRefusesTheCall() {
    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "approver returned null for ask rule Write(/tmp/**) matched Write(/tmp/a)"),
        outcomeOf(askRule(request -> null)));
    assertEquals(List.of(), executed);
  }

  @Test
  void anApproverReturningAskRefusesTheCall() {
    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "approver returned ASK for ask rule Write(/tmp/**) matched Write(/tmp/a)"),
        outcomeOf(askRule(request -> PermissionDecision.ask("unsure"))));
    assertEquals(List.of(), executed);
  }

  @Test
  void anApproverThatThrowsRefusesTheCall() {
    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "approver threw on ask rule Write(/tmp/**) matched Write(/tmp/a):"
                + " java.lang.IllegalStateException: reviewer down"),
        outcomeOf(
            askRule(
                request -> {
                  throw new IllegalStateException("reviewer down");
                })));
    assertEquals(List.of(), executed);
  }

  @Test
  void aCancelledApprovalRefusesTheCall() {
    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "approval of ask rule Write(/tmp/**) matched Write(/tmp/a) cancelled: withdrawn"),
        outcomeOf(
            askRule(
                request -> {
                  throw new CancellationException("withdrawn");
                })));
    assertEquals(List.of(), executed);
  }

  @Test
  void anInterruptedApprovalRefusesTheCallAndRestoresTheLoopThreadsInterrupt() {
    var interruptedAfterRefusal = new CopyOnWriteArrayList<Boolean>();
    PostToolUseHook observe =
        (call, result, ctx) -> {
          interruptedAfterRefusal.add(Thread.interrupted());
          return HookOutcome.cont();
        };

    var outcome =
        outcomeOf(
            askRule(
                request -> {
                  throw new InterruptedException();
                }),
            observe);

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "interrupted while awaiting approval of ask rule Write(/tmp/**) matched"
                + " Write(/tmp/a)"),
        outcome);
    assertEquals(List.of(true), interruptedAfterRefusal);
    assertEquals(List.of(), executed);
  }

  // ── a call naming no registered tool ─────────────────────────────────────

  @Test
  void aCallNamingNoRegisteredToolSkipsTheStageAndWithholdsNothing() {
    var call = new ToolCall("c9", "Missing", Map.of("path", "../outside"));

    var verdict =
        ToolAuthorization.admit(
            writeTool(),
            canonical -> {
              throw new AssertionError("authorization must not run");
            },
            new Permission(
                PermissionMode.DEFAULT,
                List.of(),
                List.of(),
                List.of(PermissionRule.any("Missing"))),
            new SecretRegistry(),
            call);

    var admitted = assertInstanceOf(ToolAuthorization.Admitted.class, verdict);
    assertSame(call, admitted.call());
    assertFalse(admitted.withheld().test("./secrets"));
  }

  // ── diagnostics ──────────────────────────────────────────────────────────

  @Test
  void aDiagnosticHasEverySecretRedacted() {
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", "tok-0123456789");
    var diagnostic = ToolAuthorization.diagnostic(secrets, "key Write(tok-0123456789) denied");
    assertEquals("key Write(<redacted:TOKEN>) denied", diagnostic.text());
    assertEquals(Map.of("TOKEN", 1), diagnostic.counts());
  }

  @Test
  void aLongerDiagnosticIsCutAtTheBound() {
    var reason = "r".repeat(ToolAuthorization.MAX_REASON_CHARS + 5);
    assertEquals(
        "r".repeat(ToolAuthorization.MAX_REASON_CHARS) + "… [5 more chars]",
        ToolAuthorization.diagnostic(new SecretRegistry(), reason).text());
  }
}
