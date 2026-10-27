/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionDecision;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class DefaultPermissionEvaluatorTest {

  private static final PermissionRule ASK_WRITE = PermissionRule.of("Write", "/tmp/**");

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

  private static PermissionDecision byRule(
      PermissionEffect effect, PermissionRule rule, String reason) {
    return new PermissionDecision(effect, reason, Optional.of(rule));
  }

  private static ToolBinding.CanonicalCall call(String name) {
    return new ToolBinding.CanonicalCall(
        new ToolCall("c", name, Map.of()), ToolPermissionKey.of(name));
  }

  private static ToolBinding.CanonicalCall writeCall(String path) {
    return new ToolBinding.CanonicalCall(
        new ToolCall("c", "Write", Map.of("path", path)), new ToolPermissionKey("Write", path));
  }

  private static final ToolApprover REFUSING =
      request -> PermissionDecision.deny("refused by the test: " + request.reason());

  private static DefaultPermissionEvaluator evaluator(Permission permission, ToolRegistry tools) {
    return evaluator(permission, tools, REFUSING, new SecretRegistry());
  }

  private static DefaultPermissionEvaluator evaluator(
      Permission permission, ToolRegistry tools, ToolApprover approver, SecretRegistry secrets) {
    return new DefaultPermissionEvaluator(permission, tools, approver, secrets);
  }

  /** An evaluator whose ask rule {@link #ASK_WRITE} goes to {@code approver}. */
  private static DefaultPermissionEvaluator asking(ToolApprover approver) {
    return evaluator(
        rules(PermissionMode.DEFAULT, List.of(), List.of(ASK_WRITE), List.of()),
        tools(pathKeyed("Write", ToolCategory.WRITE)),
        approver,
        new SecretRegistry());
  }

  // ── fail closed ──────────────────────────────────────────────────────────

  @Test
  void aCallNamingNoRegisteredToolIsDenied() {
    var e = evaluator(Permission.empty(PermissionMode.BYPASS_PERMISSIONS), ToolRegistry.empty());
    assertEquals(
        PermissionDecision.deny("no tool named 'unknown' is registered"),
        e.authorize(call("unknown")));
  }

  @Test
  void anAskRulePatternCarryingASecretReachesTheApproverRedactedWithTheRuleIntact() {
    var secrets = new SecretRegistry();
    secrets.register("PASSWORD", "secret*value");
    var askSecret = PermissionRule.of("Write", "secret*value");
    var requests = new ArrayList<ApprovalRequest>();
    var e =
        evaluator(
            rules(PermissionMode.DEFAULT, List.of(), List.of(askSecret), List.of()),
            tools(pathKeyed("Write", ToolCategory.WRITE)),
            request -> {
              requests.add(request);
              return PermissionDecision.deny("no");
            },
            secrets);
    var call = writeCall("secretXvalue");

    e.authorize(call);

    assertEquals(
        List.of(
            new ApprovalRequest(
                call.call(),
                "{\"path\":\"secretXvalue\"}",
                new ToolPermissionKey("Write", "secretXvalue"),
                ToolCategory.WRITE,
                Optional.of(askSecret),
                "ask rule Write(<redacted:PASSWORD>) matched Write(secretXvalue)")),
        requests);
  }

  @Test
  void approvalArgumentsAreCutAtTheBoundAfterRedaction() {
    var secrets = new SecretRegistry();
    secrets.register("API_KEY", "key-0123456789");
    var requests = new ArrayList<ApprovalRequest>();
    evaluator(
            rules(PermissionMode.DEFAULT, List.of(), List.of(ASK_WRITE), List.of()),
            tools(pathKeyed("Write", ToolCategory.WRITE)),
            request -> {
              requests.add(request);
              return PermissionDecision.deny("no");
            },
            secrets)
        .authorize(writeCall("/tmp/" + "a".repeat(1006) + "key-0123456789"));

    assertEquals(
        "{\"path\":\"/tmp/" + "a".repeat(1006) + "<red\nArguments cut: 16 more chars not shown",
        requests.getFirst().arguments());
  }

  @Test
  void aPermissionKeyNamingAnotherToolDeniesTheCall() {
    var e =
        evaluator(
            rules(
                PermissionMode.DEFAULT, List.of(PermissionRule.any("Read")), List.of(), List.of()),
            tools(binding("Write", ToolCategory.WRITE)));
    var impostor =
        new ToolBinding.CanonicalCall(call("Write").call(), new ToolPermissionKey("Read", "/etc"));
    assertEquals(
        PermissionDecision.deny("permission key of Write names another tool: Read(/etc)"),
        e.authorize(impostor));
  }

  @Test
  void authorizeDecidesOnTheKeyItIsHandedNotOneRecomputedFromTheArguments() {
    var deny = PermissionRule.of("Read", "./secrets/**");
    var e =
        evaluator(
            rules(PermissionMode.DEFAULT, List.of(), List.of(), List.of(deny)),
            tools(pathKeyed("Read", ToolCategory.READ)));
    var handed =
        new ToolBinding.CanonicalCall(
            new ToolCall("c", "Read", Map.of("path", "./docs/a.txt")),
            new ToolPermissionKey("Read", "./secrets/key"));
    assertEquals(
        byRule(
            PermissionEffect.DENY,
            deny,
            "deny rule Read(./secrets/**) matched Read(./secrets/key)"),
        e.authorize(handed));
  }

  // ── rules ────────────────────────────────────────────────────────────────

  @Test
  void anExplicitDenyWinsOverAnAllowForTheSameCall() {
    var deny = PermissionRule.any("Read");
    var e =
        evaluator(
            rules(
                PermissionMode.DEFAULT,
                List.of(PermissionRule.any("Read")),
                List.of(),
                List.of(deny)),
            tools(binding("Read", ToolCategory.READ)));
    assertEquals(
        byRule(PermissionEffect.DENY, deny, "deny rule Read matched Read"),
        e.authorize(call("Read")));
  }

  @Test
  void anExplicitDenyWinsOverAnAskForTheSameCall() {
    var deny = PermissionRule.any("Read");
    var e =
        evaluator(
            rules(
                PermissionMode.DEFAULT,
                List.of(),
                List.of(PermissionRule.any("Read")),
                List.of(deny)),
            tools(binding("Read", ToolCategory.READ)));
    assertEquals(
        byRule(PermissionEffect.DENY, deny, "deny rule Read matched Read"),
        e.authorize(call("Read")));
  }

  @Test
  void bypassPermissionsAllowsEveryCategory() {
    var e =
        evaluator(
            Permission.empty(PermissionMode.BYPASS_PERMISSIONS),
            tools(binding("Execute", ToolCategory.EXECUTION)));
    assertEquals(
        PermissionDecision.allow("BYPASS_PERMISSIONS allows Execute"),
        e.authorize(call("Execute")));
  }

  @Test
  void bypassPermissionsKeepsAnExplicitDeny() {
    var deny = PermissionRule.any("Execute");
    var e =
        evaluator(
            rules(PermissionMode.BYPASS_PERMISSIONS, List.of(), List.of(), List.of(deny)),
            tools(binding("Execute", ToolCategory.EXECUTION)));
    assertEquals(
        byRule(PermissionEffect.DENY, deny, "deny rule Execute matched Execute"),
        e.authorize(call("Execute")));
  }

  @Test
  void anAllowRuleOverridesTheCategoryDefault() {
    var allow = PermissionRule.of("Write", "/workspace/**");
    var e =
        evaluator(
            rules(PermissionMode.DEFAULT, List.of(allow), List.of(), List.of()),
            tools(pathKeyed("Write", ToolCategory.WRITE)));
    assertEquals(
        byRule(
            PermissionEffect.ALLOW,
            allow,
            "allow rule Write(/workspace/**) matched Write(/workspace/a.txt)"),
        e.authorize(writeCall("/workspace/a.txt")));
  }

  @Test
  void aCallTheAllowGlobMissesFallsToTheCategoryDefault() {
    var allow = PermissionRule.of("Write", "/workspace/**");
    var e =
        evaluator(
            rules(PermissionMode.DEFAULT, List.of(allow), List.of(), List.of()),
            tools(pathKeyed("Write", ToolCategory.WRITE)));
    assertEquals(
        PermissionDecision.deny("refused by the test: default-ask on WRITE (Write(/etc/passwd))"),
        e.authorize(writeCall("/etc/passwd")));
  }

  @Test
  void lockedDownAllowRuleOpensASingleTool() {
    var e =
        evaluator(
            Permission.lockedDown(),
            tools(binding("Read", ToolCategory.READ), binding("Execute", ToolCategory.EXECUTION)));
    assertEquals(PermissionEffect.ALLOW, e.authorize(call("Execute")).effect());
    assertEquals(PermissionEffect.DENY, e.authorize(call("Read")).effect());
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
    assertEquals("Allow Write(/tmp/a)?\n\nArguments: {\"path\":\"/tmp/a\"}", question.question());
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
    evaluator(
            Permission.empty(PermissionMode.DEFAULT),
            tools(binding("Fetch", ToolCategory.NETWORK)),
            ToolApprover.askingUser(gateway),
            new SecretRegistry())
        .authorize(call("Fetch"));

    assertEquals("Allow Fetch?\n\nArguments: {}", gateway.asked.getFirst().question());
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
