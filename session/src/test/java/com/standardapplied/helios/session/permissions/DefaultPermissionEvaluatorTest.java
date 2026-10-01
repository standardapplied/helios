/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.ToolCall;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.hooks.DefaultHookContext;
import ai.singlr.session.hooks.HookContext;
import ai.singlr.session.hooks.HookOutcome;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolPermissionKey;
import ai.singlr.session.tools.ToolRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class DefaultPermissionEvaluatorTest {

  private static final Model STUB_MODEL =
      new Model() {
        @Override
        public Response<Void> chat(List<Message> messages, List<ai.singlr.core.tool.Tool> tools) {
          return Response.newBuilder().build();
        }

        @Override
        public String id() {
          return "stub";
        }

        @Override
        public String provider() {
          return "stub";
        }
      };

  private static HookContext ctx() {
    return new DefaultHookContext("sess", 0, new CancellationToken(), STUB_MODEL);
  }

  private static Tool tool(String name) {
    return Tool.newBuilder()
        .withName(name)
        .withDescription("test")
        .withExecutor((args, ctx) -> ToolResult.success("ok"))
        .build();
  }

  private static ToolBinding binding(String name, ToolCategory category) {
    return ToolBinding.newBuilder(tool(name)).withCategory(category).build();
  }

  private static ToolBinding bindingWithPathKey(String name, ToolCategory category) {
    return ToolBinding.newBuilder(tool(name))
        .withCategory(category)
        .withPermissionKeyExtractor(
            args -> new ToolPermissionKey(name, String.valueOf(args.getOrDefault("path", ""))))
        .build();
  }

  // ── construction ─────────────────────────────────────────────────────────

  @Test
  void constructorRejectsNullPermission() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new DefaultPermissionEvaluator(null, ToolRegistry.empty()));
    assertEquals("permission must not be null", ex.getMessage());
  }

  @Test
  void constructorRejectsNullTools() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> new DefaultPermissionEvaluator(Permission.defaultInWorkspace(), null));
    assertEquals("tools must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsNegativePriority() {
    var b =
        DefaultPermissionEvaluator.newBuilder(
            Permission.defaultInWorkspace(), ToolRegistry.empty());
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withPriority(-1));
    assertEquals("priority must be non-negative, got -1", ex.getMessage());
  }

  @Test
  void defaultPriorityIsFifty() {
    var e = new DefaultPermissionEvaluator(Permission.defaultInWorkspace(), ToolRegistry.empty());
    assertEquals(50, e.priority());
  }

  @Test
  void builderCustomPriorityRetained() {
    var e =
        DefaultPermissionEvaluator.newBuilder(Permission.defaultInWorkspace(), ToolRegistry.empty())
            .withPriority(25)
            .build();
    assertEquals(25, e.priority());
  }

  @Test
  void builderRejectsNullPermission() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> DefaultPermissionEvaluator.newBuilder(null, ToolRegistry.empty()));
    assertEquals("permission must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsNullTools() {
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> DefaultPermissionEvaluator.newBuilder(Permission.defaultInWorkspace(), null));
    assertEquals("tools must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsNullQuestionGateway() {
    var b =
        DefaultPermissionEvaluator.newBuilder(
            Permission.defaultInWorkspace(), ToolRegistry.empty());
    var ex = assertThrows(NullPointerException.class, () -> b.withQuestionGateway(null));
    assertEquals("questionGateway must not be null", ex.getMessage());
  }

  @Test
  void builderWithAllOptionsAppliesEverySetter() {
    // Exercise every Builder setter in one go so a missed default propagation surfaces here.
    var perm = Permission.defaultInWorkspace();
    var tools = ToolRegistry.empty();
    var gateway =
        new ai.singlr.session.ask.QuestionGateway() {
          @Override
          public ai.singlr.session.ask.AskUserQuestionResponse ask(
              ai.singlr.session.ask.AskUserQuestionRequest request) {
            throw new UnsupportedOperationException("not invoked by this test");
          }
        };
    var e =
        DefaultPermissionEvaluator.newBuilder(perm, tools)
            .withPriority(12)
            .withQuestionGateway(gateway)
            .build();
    assertSame(perm, e.permission());
    assertEquals(12, e.priority());
    // Gateway presence drives the ASK code path; cover via the dedicated ASK tests elsewhere.
  }

  @Test
  void nameIsClassSimpleName() {
    var e = new DefaultPermissionEvaluator(Permission.defaultInWorkspace(), ToolRegistry.empty());
    assertEquals("DefaultPermissionEvaluator", e.name());
  }

  @Test
  void permissionAccessorReturnsConstructorValue() {
    var perm = Permission.defaultInWorkspace();
    var e = new DefaultPermissionEvaluator(perm, ToolRegistry.empty());
    assertSame(perm, e.permission());
  }

  // ── beforeTool: unknown tool falls through ────────────────────────────────

  @Test
  void unknownToolReturnsContinue() {
    var e = new DefaultPermissionEvaluator(Permission.defaultInWorkspace(), ToolRegistry.empty());
    var outcome = e.beforeTool(new ToolCall("c", "unknown", Map.of()), ctx());
    assertInstanceOf(HookOutcome.Continue.class, outcome);
  }

  @Test
  void beforeToolRejectsNullCall() {
    var e = new DefaultPermissionEvaluator(Permission.defaultInWorkspace(), ToolRegistry.empty());
    assertThrows(NullPointerException.class, () -> e.beforeTool(null, ctx()));
  }

  @Test
  void beforeToolRejectsNullCtx() {
    var e = new DefaultPermissionEvaluator(Permission.defaultInWorkspace(), ToolRegistry.empty());
    var call = new ToolCall("c", "x", Map.of());
    assertThrows(NullPointerException.class, () -> e.beforeTool(call, null));
  }

  // ── deny rules win first ─────────────────────────────────────────────────

  @Test
  void denyRuleBlocksEvenWithAllowMatch() {
    var perm =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.any(PermissionEffect.ALLOW, "Read")),
            List.of(),
            List.of(PermissionRule.any(PermissionEffect.DENY, "Read")));
    var tools = new ToolRegistry(List.of(binding("Read", ToolCategory.READ)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    var outcome = e.beforeTool(new ToolCall("c", "Read", Map.of()), ctx());
    var block = assertInstanceOf(HookOutcome.Block.class, outcome);
    assertTrue(block.reason().contains("deny rule"));
  }

  // ── BYPASS_PERMISSIONS skips rules ──────────────────────────────────────

  @Test
  void bypassPermissionsAllowsEverythingExceptDeny() {
    var perm = Permission.empty(PermissionMode.BYPASS_PERMISSIONS);
    var tools = new ToolRegistry(List.of(binding("Execute", ToolCategory.EXECUTION)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    var outcome = e.beforeTool(new ToolCall("c", "Execute", Map.of()), ctx());
    assertInstanceOf(HookOutcome.Continue.class, outcome);
  }

  @Test
  void bypassPermissionsRespectsExplicitDeny() {
    var perm =
        new Permission(
            PermissionMode.BYPASS_PERMISSIONS,
            List.of(),
            List.of(),
            List.of(PermissionRule.any(PermissionEffect.DENY, "Execute")));
    var tools = new ToolRegistry(List.of(binding("Execute", ToolCategory.EXECUTION)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    var outcome = e.beforeTool(new ToolCall("c", "Execute", Map.of()), ctx());
    assertInstanceOf(HookOutcome.Block.class, outcome);
  }

  // ── allow rule ───────────────────────────────────────────────────────────

  @Test
  void allowRuleProducesContinue() {
    var perm =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.any(PermissionEffect.ALLOW, "Read")),
            List.of(),
            List.of());
    var tools = new ToolRegistry(List.of(binding("Read", ToolCategory.READ)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    var outcome = e.beforeTool(new ToolCall("c", "Read", Map.of()), ctx());
    assertInstanceOf(HookOutcome.Continue.class, outcome);
  }

  @Test
  void allowRuleWithGlobMatchesPath() {
    var perm =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "/workspace/**")),
            List.of(),
            List.of());
    var tools = new ToolRegistry(List.of(bindingWithPathKey("Read", ToolCategory.READ)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    var outcome =
        e.beforeTool(new ToolCall("c", "Read", Map.of("path", "/workspace/foo.txt")), ctx());
    assertInstanceOf(HookOutcome.Continue.class, outcome);
  }

  @Test
  void allowRuleGlobMissBlocksViaDefaultPolicy() {
    var perm =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "/workspace/**")),
            List.of(),
            List.of());
    var tools = new ToolRegistry(List.of(bindingWithPathKey("Read", ToolCategory.READ)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    // /etc/foo.txt doesn't match /workspace/** → falls through to default category policy →
    // READ → Continue.
    var outcome = e.beforeTool(new ToolCall("c", "Read", Map.of("path", "/etc/foo.txt")), ctx());
    assertInstanceOf(HookOutcome.Continue.class, outcome);
  }

  // ── ask rule → Block in Phase 2 ─────────────────────────────────────────

  @Test
  void askRuleFallsBackToBlockUntilPhase2Part5() {
    var perm =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(),
            List.of(PermissionRule.any(PermissionEffect.ASK, "Write")),
            List.of());
    var tools = new ToolRegistry(List.of(binding("Write", ToolCategory.WRITE)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    var outcome = e.beforeTool(new ToolCall("c", "Write", Map.of()), ctx());
    var block = assertInstanceOf(HookOutcome.Block.class, outcome);
    assertTrue(block.reason().contains("ASK rule without handler"));
  }

  // ── default-by-category ─────────────────────────────────────────────────

  @Test
  void defaultReadIsAllowedUnderDEFAULTMode() {
    var perm = Permission.empty(PermissionMode.DEFAULT);
    var tools = new ToolRegistry(List.of(binding("Read", ToolCategory.READ)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    assertInstanceOf(
        HookOutcome.Continue.class, e.beforeTool(new ToolCall("c", "Read", Map.of()), ctx()));
  }

  @Test
  void defaultSearchIsAllowedUnderDEFAULTMode() {
    var perm = Permission.empty(PermissionMode.DEFAULT);
    var tools = new ToolRegistry(List.of(binding("KbSearch", ToolCategory.SEARCH)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    assertInstanceOf(
        HookOutcome.Continue.class, e.beforeTool(new ToolCall("c", "KbSearch", Map.of()), ctx()));
  }

  @Test
  void defaultWriteIsAskUnderDEFAULTMode() {
    var perm = Permission.empty(PermissionMode.DEFAULT);
    var tools = new ToolRegistry(List.of(binding("Write", ToolCategory.WRITE)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    // ASK without handler → Block.
    assertInstanceOf(
        HookOutcome.Block.class, e.beforeTool(new ToolCall("c", "Write", Map.of()), ctx()));
  }

  @Test
  void defaultWriteIsAllowedUnderACCEPT_EDITSMode() {
    var perm = Permission.empty(PermissionMode.ACCEPT_EDITS);
    var tools = new ToolRegistry(List.of(binding("Write", ToolCategory.WRITE)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    assertInstanceOf(
        HookOutcome.Continue.class, e.beforeTool(new ToolCall("c", "Write", Map.of()), ctx()));
  }

  @Test
  void defaultWriteIsDeniedUnderPLANMode() {
    var perm = Permission.empty(PermissionMode.PLAN);
    var tools = new ToolRegistry(List.of(binding("Write", ToolCategory.WRITE)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    var outcome = e.beforeTool(new ToolCall("c", "Write", Map.of()), ctx());
    var block = assertInstanceOf(HookOutcome.Block.class, outcome);
    assertTrue(block.reason().contains("PLAN mode forbids writes"));
  }

  @Test
  void defaultExecutionIsAskUnderDEFAULTMode() {
    var perm = Permission.empty(PermissionMode.DEFAULT);
    var tools = new ToolRegistry(List.of(binding("Execute", ToolCategory.EXECUTION)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    assertInstanceOf(
        HookOutcome.Block.class, e.beforeTool(new ToolCall("c", "Execute", Map.of()), ctx()));
  }

  @Test
  void defaultExecutionIsDeniedUnderPLAN() {
    var perm = Permission.empty(PermissionMode.PLAN);
    var tools = new ToolRegistry(List.of(binding("Execute", ToolCategory.EXECUTION)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    var outcome = e.beforeTool(new ToolCall("c", "Execute", Map.of()), ctx());
    var block = assertInstanceOf(HookOutcome.Block.class, outcome);
    assertTrue(block.reason().contains("PLAN mode forbids execution"));
  }

  @Test
  void defaultNetworkIsAsk() {
    var perm = Permission.empty(PermissionMode.DEFAULT);
    var tools = new ToolRegistry(List.of(binding("Fetch", ToolCategory.NETWORK)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    assertInstanceOf(
        HookOutcome.Block.class, e.beforeTool(new ToolCall("c", "Fetch", Map.of()), ctx()));
  }

  @Test
  void defaultControlIsAllow() {
    var perm = Permission.empty(PermissionMode.DEFAULT);
    var tools = new ToolRegistry(List.of(binding("Ask", ToolCategory.CONTROL)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    assertInstanceOf(
        HookOutcome.Continue.class, e.beforeTool(new ToolCall("c", "Ask", Map.of()), ctx()));
  }

  @Test
  void defaultDelegationIsAllow() {
    var perm = Permission.empty(PermissionMode.DEFAULT);
    var tools = new ToolRegistry(List.of(binding("SubAgent", ToolCategory.DELEGATION)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    assertInstanceOf(
        HookOutcome.Continue.class, e.beforeTool(new ToolCall("c", "SubAgent", Map.of()), ctx()));
  }

  // ── evaluate() direct ────────────────────────────────────────────────────

  @Test
  void evaluateRejectsNullArgs() {
    var e = new DefaultPermissionEvaluator(Permission.defaultInWorkspace(), ToolRegistry.empty());
    assertThrows(NullPointerException.class, () -> e.evaluate(null, ToolCategory.READ));
    assertThrows(NullPointerException.class, () -> e.evaluate(ToolPermissionKey.of("Read"), null));
  }

  // ── LOCKED_DOWN ──────────────────────────────────────────────────────────

  @Test
  void lockedDownDeniesEveryCategoryByDefault() {
    var perm = Permission.empty(PermissionMode.LOCKED_DOWN);
    var tools =
        new ToolRegistry(
            List.of(
                binding("Read", ToolCategory.READ),
                binding("Glob", ToolCategory.SEARCH),
                binding("Write", ToolCategory.WRITE),
                binding("Execute", ToolCategory.EXECUTION),
                binding("Fetch", ToolCategory.NETWORK),
                binding("Ask", ToolCategory.CONTROL),
                binding("SubAgent", ToolCategory.DELEGATION)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    for (var name : List.of("Read", "Glob", "Write", "Execute", "Fetch", "Ask", "SubAgent")) {
      var outcome = e.beforeTool(new ToolCall("c", name, Map.of()), ctx());
      assertInstanceOf(
          HookOutcome.Block.class, outcome, "LOCKED_DOWN should block " + name + " by default");
    }
  }

  @Test
  void lockedDownAllowRuleOpensASingleTool() {
    var perm = Permission.lockedDown();
    var tools =
        new ToolRegistry(
            List.of(
                binding("Read", ToolCategory.READ),
                binding("Execute", ToolCategory.EXECUTION),
                binding("AskUserQuestion", ToolCategory.CONTROL)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    assertInstanceOf(
        HookOutcome.Continue.class,
        e.beforeTool(new ToolCall("c1", "Execute", Map.of()), ctx()),
        "Execute is explicitly allowed");
    assertInstanceOf(
        HookOutcome.Continue.class,
        e.beforeTool(new ToolCall("c2", "AskUserQuestion", Map.of()), ctx()),
        "AskUserQuestion is explicitly allowed");
    assertInstanceOf(
        HookOutcome.Block.class,
        e.beforeTool(new ToolCall("c3", "Read", Map.of()), ctx()),
        "Read is not allowed under lockedDown");
  }

  @Test
  void lockedDownDenyRuleStillWinsOverAllow() {
    var perm =
        new Permission(
            PermissionMode.LOCKED_DOWN,
            List.of(PermissionRule.any(PermissionEffect.ALLOW, "Execute")),
            List.of(),
            List.of(PermissionRule.any(PermissionEffect.DENY, "Execute")));
    var tools = new ToolRegistry(List.of(binding("Execute", ToolCategory.EXECUTION)));
    var e = new DefaultPermissionEvaluator(perm, tools);
    assertInstanceOf(
        HookOutcome.Block.class, e.beforeTool(new ToolCall("c", "Execute", Map.of()), ctx()));
  }
}
