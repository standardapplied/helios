/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.ask.AskUserQuestionOption;
import com.standardapplied.helios.session.ask.AskUserQuestionRequest;
import com.standardapplied.helios.session.ask.AskUserQuestionResponse;
import com.standardapplied.helios.session.ask.QuestionGateway;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class DefaultPermissionEvaluatorTest {

  private static final PermissionRule ASK_WRITE =
      PermissionRule.withGlob(PermissionEffect.ASK, "Write", "/tmp/**");

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

  private static ToolBinding pathKeyed(String name, ToolCategory category) {
    return ToolBinding.newBuilder(tool(name))
        .withCategory(category)
        .withPermissionKeyExtractor(
            args -> new ToolPermissionKey(name, String.valueOf(args.getOrDefault("path", ""))))
        .build();
  }

  private static ToolRegistry tools(ToolBinding... bindings) {
    return new ToolRegistry(List.of(bindings));
  }

  private static Permission rules(
      PermissionMode mode,
      List<PermissionRule> allow,
      List<PermissionRule> ask,
      List<PermissionRule> deny) {
    return new Permission(mode, allow, ask, deny);
  }

  private static ToolCall call(String name) {
    return new ToolCall("c", name, Map.of());
  }

  private static ToolCall writeCall(String path) {
    return new ToolCall("c", "Write", Map.of("path", path));
  }

  /** An evaluator whose ask rule {@link #ASK_WRITE} goes to {@code approver}. */
  private static DefaultPermissionEvaluator asking(ToolApprover approver) {
    return DefaultPermissionEvaluator.newBuilder(
            rules(PermissionMode.DEFAULT, List.of(), List.of(ASK_WRITE), List.of()),
            tools(pathKeyed("Write", ToolCategory.WRITE)))
        .withApprover(approver)
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
  void builderRejectsNullApprover() {
    var b =
        DefaultPermissionEvaluator.newBuilder(
            Permission.defaultInWorkspace(), ToolRegistry.empty());
    var ex = assertThrows(NullPointerException.class, () -> b.withApprover(null));
    assertEquals("approver must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsNullSecretRegistry() {
    var b =
        DefaultPermissionEvaluator.newBuilder(
            Permission.defaultInWorkspace(), ToolRegistry.empty());
    var ex = assertThrows(NullPointerException.class, () -> b.withSecretRegistry(null));
    assertEquals("secrets must not be null", ex.getMessage());
  }

  @Test
  void permissionAccessorReturnsConstructorValue() {
    var perm = Permission.defaultInWorkspace();
    assertSame(perm, new DefaultPermissionEvaluator(perm, ToolRegistry.empty()).permission());
  }

  @Test
  void authorizeRejectsNullCall() {
    var e = new DefaultPermissionEvaluator(Permission.defaultInWorkspace(), ToolRegistry.empty());
    assertThrows(NullPointerException.class, () -> e.authorize(null));
  }

  @Test
  void evaluateRejectsNullArgs() {
    var e = new DefaultPermissionEvaluator(Permission.defaultInWorkspace(), ToolRegistry.empty());
    assertThrows(NullPointerException.class, () -> e.evaluate(null, ToolCategory.READ));
    assertThrows(NullPointerException.class, () -> e.evaluate(ToolPermissionKey.of("Read"), null));
  }

  // ── fail closed ──────────────────────────────────────────────────────────

  @Test
  void aCallNamingNoRegisteredToolIsDenied() {
    var e =
        new DefaultPermissionEvaluator(
            Permission.empty(PermissionMode.BYPASS_PERMISSIONS), ToolRegistry.empty());
    assertEquals(
        PermissionDecision.deny("no tool named 'unknown' is registered"),
        e.authorize(call("unknown")));
  }

  @Test
  void aPermissionKeyThatThrowsDeniesTheCall() {
    var throwing =
        ToolBinding.newBuilder(tool("Write"))
            .withCategory(ToolCategory.WRITE)
            .withPermissionKeyExtractor(
                args -> {
                  throw new IllegalArgumentException("bad path");
                })
            .build();
    var e =
        new DefaultPermissionEvaluator(
            Permission.empty(PermissionMode.BYPASS_PERMISSIONS), tools(throwing));
    assertEquals(
        PermissionDecision.deny(
            "permission key of Write failed: java.lang.IllegalArgumentException: bad path"),
        e.authorize(call("Write")));
  }

  @Test
  void aPermissionKeyCarryingASecretIsDeniedBeforeTheApproverIsAsked() {
    var secrets = new SecretRegistry();
    secrets.register("TOKEN", "test-registered-secret-123");
    var requests = new ArrayList<ApprovalRequest>();
    var e =
        DefaultPermissionEvaluator.newBuilder(
                rules(PermissionMode.DEFAULT, List.of(), List.of(ASK_WRITE), List.of()),
                tools(pathKeyed("Write", ToolCategory.WRITE)))
            .withApprover(
                request -> {
                  requests.add(request);
                  return PermissionDecision.allow("approved");
                })
            .withSecretRegistry(secrets)
            .build();

    assertEquals(
        PermissionDecision.deny("permission key of Write carries a secret"),
        e.authorize(writeCall("/tmp/test-registered-secret-123")));
    assertEquals(List.of(), requests);
  }

  @Test
  void aNullPermissionKeyDeniesTheCall() {
    var nullKey =
        ToolBinding.newBuilder(tool("Write"))
            .withCategory(ToolCategory.WRITE)
            .withPermissionKeyExtractor(args -> null)
            .build();
    var e =
        new DefaultPermissionEvaluator(
            Permission.empty(PermissionMode.BYPASS_PERMISSIONS), tools(nullKey));
    assertEquals(
        PermissionDecision.deny(
            "permission key of Write failed: java.lang.NullPointerException:"
                + " permissionKeyExtractor returned null"),
        e.authorize(call("Write")));
  }

  @Test
  void aPermissionKeyNamingAnotherToolDeniesTheCall() {
    var impostor =
        ToolBinding.newBuilder(tool("Write"))
            .withCategory(ToolCategory.WRITE)
            .withPermissionKeyExtractor(args -> new ToolPermissionKey("Read", "/etc"))
            .build();
    var e =
        new DefaultPermissionEvaluator(
            rules(
                PermissionMode.DEFAULT,
                List.of(PermissionRule.any(PermissionEffect.ALLOW, "Read")),
                List.of(),
                List.of()),
            tools(impostor));
    assertEquals(
        PermissionDecision.deny("permission key of Write names another tool: Read(/etc)"),
        e.authorize(call("Write")));
  }

  // ── rules ────────────────────────────────────────────────────────────────

  @Test
  void anExplicitDenyWinsOverAnAllowForTheSameCall() {
    var deny = PermissionRule.any(PermissionEffect.DENY, "Read");
    var e =
        new DefaultPermissionEvaluator(
            rules(
                PermissionMode.DEFAULT,
                List.of(PermissionRule.any(PermissionEffect.ALLOW, "Read")),
                List.of(),
                List.of(deny)),
            tools(binding("Read", ToolCategory.READ)));
    assertEquals(
        PermissionDecision.byRule(deny, "deny rule Read matched Read"), e.authorize(call("Read")));
  }

  @Test
  void bypassPermissionsAllowsEveryCategory() {
    var e =
        new DefaultPermissionEvaluator(
            Permission.empty(PermissionMode.BYPASS_PERMISSIONS),
            tools(binding("Execute", ToolCategory.EXECUTION)));
    assertEquals(
        PermissionDecision.allow("BYPASS_PERMISSIONS allows Execute"),
        e.authorize(call("Execute")));
  }

  @Test
  void bypassPermissionsKeepsAnExplicitDeny() {
    var deny = PermissionRule.any(PermissionEffect.DENY, "Execute");
    var e =
        new DefaultPermissionEvaluator(
            rules(PermissionMode.BYPASS_PERMISSIONS, List.of(), List.of(), List.of(deny)),
            tools(binding("Execute", ToolCategory.EXECUTION)));
    assertEquals(
        PermissionDecision.byRule(deny, "deny rule Execute matched Execute"),
        e.authorize(call("Execute")));
  }

  @Test
  void anAllowRuleOverridesTheCategoryDefault() {
    var allow = PermissionRule.withGlob(PermissionEffect.ALLOW, "Write", "/workspace/**");
    var e =
        new DefaultPermissionEvaluator(
            rules(PermissionMode.DEFAULT, List.of(allow), List.of(), List.of()),
            tools(pathKeyed("Write", ToolCategory.WRITE)));
    assertEquals(
        PermissionDecision.byRule(
            allow, "allow rule Write(/workspace/**) matched Write(/workspace/a.txt)"),
        e.authorize(writeCall("/workspace/a.txt")));
  }

  @Test
  void aCallTheAllowGlobMissesFallsToTheCategoryDefault() {
    var allow = PermissionRule.withGlob(PermissionEffect.ALLOW, "Write", "/workspace/**");
    var e =
        new DefaultPermissionEvaluator(
            rules(PermissionMode.DEFAULT, List.of(allow), List.of(), List.of()),
            tools(pathKeyed("Write", ToolCategory.WRITE)));
    assertEquals(
        PermissionDecision.deny("no approver for default-ask on WRITE (Write(/etc/passwd))"),
        e.authorize(writeCall("/etc/passwd")));
  }

  @Test
  void anAskRuleWithNoApproverIsDenied() {
    var e =
        new DefaultPermissionEvaluator(
            rules(PermissionMode.DEFAULT, List.of(), List.of(ASK_WRITE), List.of()),
            tools(pathKeyed("Write", ToolCategory.WRITE)));
    assertEquals(
        PermissionDecision.deny("no approver for ask rule Write(/tmp/**) matched Write(/tmp/a)"),
        e.authorize(writeCall("/tmp/a")));
  }

  @Test
  void anAskRuleOverridesAnAllowingCategoryDefault() {
    var ask = PermissionRule.any(PermissionEffect.ASK, "Read");
    var e =
        new DefaultPermissionEvaluator(
            rules(PermissionMode.DEFAULT, List.of(), List.of(ask), List.of()),
            tools(binding("Read", ToolCategory.READ)));
    assertEquals(
        PermissionDecision.byRule(ask, "ask rule Read matched Read"),
        e.evaluate(ToolPermissionKey.of("Read"), ToolCategory.READ));
  }

  @Test
  void lockedDownAllowRuleOpensASingleTool() {
    var e =
        new DefaultPermissionEvaluator(
            Permission.lockedDown(),
            tools(binding("Read", ToolCategory.READ), binding("Execute", ToolCategory.EXECUTION)));
    assertEquals(PermissionEffect.ALLOW, e.authorize(call("Execute")).effect());
    assertEquals(PermissionEffect.DENY, e.authorize(call("Read")).effect());
  }

  // ── category defaults per mode ───────────────────────────────────────────

  @ParameterizedTest(name = "{0} {1} -> {2}")
  @CsvSource({
    "DEFAULT, READ, ALLOW, default-allow READ/SEARCH (T)",
    "DEFAULT, SEARCH, ALLOW, default-allow READ/SEARCH (T)",
    "DEFAULT, WRITE, ASK, default-ask on WRITE (T)",
    "DEFAULT, EXECUTION, ASK, default-ask on EXECUTION (T)",
    "DEFAULT, NETWORK, ASK, default-ask on NETWORK (T)",
    "DEFAULT, CONTROL, ALLOW, default-allow CONTROL (T)",
    "DEFAULT, DELEGATION, ALLOW, default-allow DELEGATION (T)",
    "PLAN, READ, ALLOW, default-allow under PLAN (T)",
    "PLAN, SEARCH, ALLOW, default-allow under PLAN (T)",
    "PLAN, WRITE, DENY, PLAN mode forbids writes (T)",
    "PLAN, EXECUTION, DENY, PLAN mode forbids execution (T)",
    "PLAN, NETWORK, ASK, default-ask on NETWORK (T)",
    "PLAN, CONTROL, ALLOW, default-allow CONTROL (T)",
    "PLAN, DELEGATION, ALLOW, default-allow DELEGATION (T)",
    "ACCEPT_EDITS, WRITE, ALLOW, ACCEPT_EDITS allows writes (T)",
    "ACCEPT_EDITS, EXECUTION, ASK, default-ask on EXECUTION (T)",
    "BYPASS_PERMISSIONS, READ, ALLOW, BYPASS_PERMISSIONS allows T",
    "BYPASS_PERMISSIONS, SEARCH, ALLOW, BYPASS_PERMISSIONS allows T",
    "BYPASS_PERMISSIONS, WRITE, ALLOW, BYPASS_PERMISSIONS allows T",
    "BYPASS_PERMISSIONS, EXECUTION, ALLOW, BYPASS_PERMISSIONS allows T",
    "BYPASS_PERMISSIONS, NETWORK, ALLOW, BYPASS_PERMISSIONS allows T",
    "BYPASS_PERMISSIONS, CONTROL, ALLOW, BYPASS_PERMISSIONS allows T",
    "BYPASS_PERMISSIONS, DELEGATION, ALLOW, BYPASS_PERMISSIONS allows T"
  })
  void theModeAndCategoryDecideWhenNoRuleMatches(
      PermissionMode mode, ToolCategory category, PermissionEffect effect, String reason) {
    var e = new DefaultPermissionEvaluator(Permission.empty(mode), ToolRegistry.empty());
    assertEquals(
        new PermissionDecision(effect, reason, Optional.empty()),
        e.evaluate(ToolPermissionKey.of("T"), category));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"READ", "SEARCH", "WRITE", "EXECUTION", "NETWORK", "CONTROL", "DELEGATION"})
  void lockedDownDeniesEveryCategoryByDefault(ToolCategory category) {
    var e =
        new DefaultPermissionEvaluator(
            Permission.empty(PermissionMode.LOCKED_DOWN), ToolRegistry.empty());
    assertEquals(
        PermissionDecision.deny(
            "LOCKED_DOWN mode forbids "
                + category
                + " by default (T) — add an explicit allow rule to open this tool"),
        e.evaluate(ToolPermissionKey.of("T"), category));
  }

  // ── askingUser ───────────────────────────────────────────────────────────

  /** A gateway that records each question and answers it with {@code label}. */
  private static final class AnsweringGateway implements QuestionGateway {

    private final List<AskUserQuestionRequest> asked = new ArrayList<>();
    private final String label;

    AnsweringGateway(String label) {
      this.label = label;
    }

    @Override
    public AskUserQuestionResponse ask(AskUserQuestionRequest request) {
      asked.add(request);
      return AskUserQuestionResponse.single(request.questionId(), label);
    }
  }

  @Test
  void askingUserRejectsANullGateway() {
    var ex = assertThrows(NullPointerException.class, () -> ToolApprover.askingUser(null));
    assertEquals("gateway must not be null", ex.getMessage());
  }

  @Test
  void askingUserAsksAPermissionQuestionForTheCall() {
    var gateway = new AnsweringGateway("Deny");
    asking(ToolApprover.askingUser(gateway)).authorize(writeCall("/tmp/a"));

    var question = gateway.asked.getFirst();
    assertTrue(question.questionId().startsWith("perm-"));
    assertEquals("Permission", question.header());
    assertEquals("Allow Write(/tmp/a)?", question.question());
    assertEquals(
        List.of(
            new AskUserQuestionOption("Allow", "ask rule Write(/tmp/**) matched Write(/tmp/a)"),
            new AskUserQuestionOption("Deny", "Refuse this tool call")),
        question.options());
  }

  @Test
  void askingUserAsksEachRequestUnderAFreshQuestionId() {
    var gateway = new AnsweringGateway("Allow");
    var e = asking(ToolApprover.askingUser(gateway));
    e.authorize(writeCall("/tmp/a"));
    e.authorize(writeCall("/tmp/a"));

    assertEquals(2, gateway.asked.size());
    assertNotEquals(gateway.asked.get(0).questionId(), gateway.asked.get(1).questionId());
  }

  @Test
  void askingUserOmitsTheParenthesesOfAnEmptyKey() {
    var gateway = new AnsweringGateway("Deny");
    DefaultPermissionEvaluator.newBuilder(
            Permission.empty(PermissionMode.DEFAULT), tools(binding("Fetch", ToolCategory.NETWORK)))
        .withApprover(ToolApprover.askingUser(gateway))
        .build()
        .authorize(call("Fetch"));

    assertEquals("Allow Fetch?", gateway.asked.getFirst().question());
  }

  @Test
  void askingUserDeniesAResponseToAnotherQuestion() {
    QuestionGateway replaying = request -> AskUserQuestionResponse.single("perm-earlier", "Allow");

    assertEquals(
        PermissionDecision.deny(
            "approval of Write(/tmp/a) answered another question: perm-earlier"),
        asking(ToolApprover.askingUser(replaying)).authorize(writeCall("/tmp/a")));
  }

  @Test
  void askingUserDeniesAResponseSelectingMoreThanOneOption() {
    QuestionGateway ambiguous =
        request -> new AskUserQuestionResponse(request.questionId(), List.of("Allow", "Allow"), "");

    assertEquals(
        PermissionDecision.deny("approval of Write(/tmp/a) selected 2 options, not one"),
        asking(ToolApprover.askingUser(ambiguous)).authorize(writeCall("/tmp/a")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Allow", "allow", " ALLOW "})
  void askingUserAllowsOnAnAllowSelection(String label) {
    assertEquals(
        PermissionDecision.allow("user allowed Write(/tmp/a)"),
        asking(ToolApprover.askingUser(new AnsweringGateway(label)))
            .authorize(writeCall("/tmp/a")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Deny", "Something else"})
  void askingUserDeniesOnAnyOtherSelection(String label) {
    assertEquals(
        PermissionDecision.deny("user denied Write(/tmp/a)"),
        asking(ToolApprover.askingUser(new AnsweringGateway(label)))
            .authorize(writeCall("/tmp/a")));
  }
}
