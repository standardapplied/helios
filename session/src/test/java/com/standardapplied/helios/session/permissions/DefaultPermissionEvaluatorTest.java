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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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

  private static DefaultPermissionEvaluator evaluator(Permission permission, ToolRegistry tools) {
    return DefaultPermissionEvaluator.newBuilder(permission, tools).build();
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
  void anEvaluatorIsBuiltOnlyThroughItsBuilder() {
    assertEquals(List.of(), List.of(DefaultPermissionEvaluator.class.getConstructors()));
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
    assertSame(perm, evaluator(perm, ToolRegistry.empty()).permission());
  }

  @Test
  void authorizeRejectsNullCall() {
    var e = evaluator(Permission.defaultInWorkspace(), ToolRegistry.empty());
    assertThrows(NullPointerException.class, () -> e.authorize(null));
  }

  @Test
  void evaluateRejectsNullArgs() {
    var e = evaluator(Permission.defaultInWorkspace(), ToolRegistry.empty());
    assertThrows(NullPointerException.class, () -> e.evaluate(null, ToolCategory.READ));
    assertThrows(NullPointerException.class, () -> e.evaluate(ToolPermissionKey.of("Read"), null));
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
        DefaultPermissionEvaluator.newBuilder(
                rules(PermissionMode.DEFAULT, List.of(), List.of(askSecret), List.of()),
                tools(pathKeyed("Write", ToolCategory.WRITE)))
            .withApprover(
                request -> {
                  requests.add(request);
                  return PermissionDecision.deny("no");
                })
            .withSecretRegistry(secrets)
            .build();
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
    DefaultPermissionEvaluator.newBuilder(
            rules(PermissionMode.DEFAULT, List.of(), List.of(ASK_WRITE), List.of()),
            tools(pathKeyed("Write", ToolCategory.WRITE)))
        .withApprover(
            request -> {
              requests.add(request);
              return PermissionDecision.deny("no");
            })
        .withSecretRegistry(secrets)
        .build()
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
  void anAskRuleWinsOverAnAllowForTheSameCall() {
    var ask = PermissionRule.any("Write");
    var e =
        evaluator(
            rules(
                PermissionMode.DEFAULT,
                List.of(PermissionRule.any("Write")),
                List.of(ask),
                List.of()),
            tools(binding("Write", ToolCategory.WRITE)));
    assertEquals(
        byRule(PermissionEffect.ASK, ask, "ask rule Write matched Write"),
        e.evaluate(ToolPermissionKey.of("Write"), ToolCategory.WRITE));
  }

  private static final PermissionRule DENY_TMP = PermissionRule.of("Write", "/tmp/**");
  private static final PermissionRule ASK_TMP = PermissionRule.of("Write", "/tmp/*");
  private static final PermissionRule ALLOW_TMP_A = PermissionRule.of("Write", "/tmp/?");

  /**
   * Which of a deny, an ask and an allow rule matching one key a policy holds, and the effect that
   * decides.
   */
  record Rules(boolean deny, boolean ask, boolean allow, PermissionEffect expected) {}

  /** Every combination of the three rules, in every mode. */
  static Stream<Arguments> ruleCombinations() {
    var combinations =
        List.of(
            new Rules(true, true, true, PermissionEffect.DENY),
            new Rules(true, true, false, PermissionEffect.DENY),
            new Rules(true, false, true, PermissionEffect.DENY),
            new Rules(true, false, false, PermissionEffect.DENY),
            new Rules(false, true, true, PermissionEffect.ASK),
            new Rules(false, true, false, PermissionEffect.ASK),
            new Rules(false, false, true, PermissionEffect.ALLOW));
    return Stream.of(PermissionMode.values())
        .flatMap(mode -> combinations.stream().map(rules -> Arguments.of(mode, rules)));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("ruleCombinations")
  void theMostRestrictiveMatchingRuleDecidesInEveryMode(PermissionMode mode, Rules held) {
    var e =
        evaluator(
            rules(
                mode,
                held.allow() ? List.of(ALLOW_TMP_A) : List.of(),
                held.ask() ? List.of(ASK_TMP) : List.of(),
                held.deny() ? List.of(DENY_TMP) : List.of()),
            tools(pathKeyed("Write", ToolCategory.WRITE)));
    var rule =
        switch (held.expected()) {
          case DENY -> DENY_TMP;
          case ASK -> ASK_TMP;
          case ALLOW -> ALLOW_TMP_A;
        };

    var decision = e.evaluate(new ToolPermissionKey("Write", "/tmp/a"), ToolCategory.WRITE);

    assertEquals(held.expected(), decision.effect());
    assertEquals(Optional.of(rule), decision.rule());
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "DEFAULT, ASK",
    "PLAN, DENY",
    "ACCEPT_EDITS, ALLOW",
    "LOCKED_DOWN, DENY",
    "BYPASS_PERMISSIONS, ALLOW"
  })
  void withNoMatchingRuleTheModesCategoryDefaultDecides(
      PermissionMode mode, PermissionEffect expected) {
    var e =
        evaluator(
            rules(mode, List.of(ALLOW_TMP_A), List.of(ASK_TMP), List.of(DENY_TMP)),
            tools(pathKeyed("Write", ToolCategory.WRITE)));

    var decision = e.evaluate(new ToolPermissionKey("Write", "/var/a/b"), ToolCategory.WRITE);

    assertEquals(new PermissionDecision(expected, decision.reason(), Optional.empty()), decision);
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
        PermissionDecision.deny("no approver for default-ask on WRITE (Write(/etc/passwd))"),
        e.authorize(writeCall("/etc/passwd")));
  }

  @Test
  void anAskRuleWithNoApproverIsDenied() {
    var e =
        evaluator(
            rules(PermissionMode.DEFAULT, List.of(), List.of(ASK_WRITE), List.of()),
            tools(pathKeyed("Write", ToolCategory.WRITE)));
    assertEquals(
        PermissionDecision.deny("no approver for ask rule Write(/tmp/**) matched Write(/tmp/a)"),
        e.authorize(writeCall("/tmp/a")));
  }

  @Test
  void anAskRuleOverridesAnAllowingCategoryDefault() {
    var ask = PermissionRule.any("Read");
    var e =
        evaluator(
            rules(PermissionMode.DEFAULT, List.of(), List.of(ask), List.of()),
            tools(binding("Read", ToolCategory.READ)));
    assertEquals(
        byRule(PermissionEffect.ASK, ask, "ask rule Read matched Read"),
        e.evaluate(ToolPermissionKey.of("Read"), ToolCategory.READ));
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

  // ── ruleDecision ─────────────────────────────────────────────────────────

  private static final PermissionRule DENY_SECRETS = PermissionRule.of("Grep", "./secrets/**");
  private static final PermissionRule ALLOW_PUBLIC = PermissionRule.of("Grep", "./docs/public/**");
  private static final PermissionRule ASK_DOCS = PermissionRule.of("Grep", "./docs/**");

  private static DefaultPermissionEvaluator grepRules(PermissionMode mode) {
    return evaluator(
        rules(mode, List.of(ALLOW_PUBLIC), List.of(ASK_DOCS), List.of(DENY_SECRETS)),
        tools(binding("Grep", ToolCategory.SEARCH)));
  }

  private static ToolPermissionKey grep(String path) {
    return new ToolPermissionKey("Grep", path);
  }

  private static Optional<PermissionRule> decidingRule(
      DefaultPermissionEvaluator e, ToolPermissionKey key) {
    return e.ruleDecision(key).flatMap(PermissionDecision::rule);
  }

  @Test
  void theDecidingRuleIsTheRuleEvaluateDecidesBy() {
    var e = grepRules(PermissionMode.DEFAULT);

    assertEquals(Optional.of(DENY_SECRETS), decidingRule(e, grep("./secrets/key")));
    assertEquals(Optional.of(ASK_DOCS), decidingRule(e, grep("./docs/public/a.txt")));
    assertEquals(Optional.of(ASK_DOCS), decidingRule(e, grep("./docs/a.txt")));
    assertEquals(Optional.empty(), decidingRule(e, grep("./src/a.txt")));
    assertEquals(Optional.empty(), decidingRule(e, new ToolPermissionKey("Read", "./secrets/key")));
  }

  @Test
  void theRuleDecisionIsTheDecisionEvaluateReturns() {
    var e = grepRules(PermissionMode.DEFAULT);

    for (var path : List.of("./secrets/key", "./docs/public/a.txt", "./docs/a.txt")) {
      assertEquals(
          Optional.of(e.evaluate(grep(path), ToolCategory.SEARCH)), e.ruleDecision(grep(path)));
    }
  }

  @Test
  void underBypassPermissionsDenyAndAskRulesDecide() {
    var e = grepRules(PermissionMode.BYPASS_PERMISSIONS);

    assertEquals(Optional.of(DENY_SECRETS), decidingRule(e, grep("./secrets/key")));
    assertEquals(Optional.of(ASK_DOCS), decidingRule(e, grep("./docs/public/a.txt")));
    assertEquals(Optional.of(ASK_DOCS), decidingRule(e, grep("./docs/a.txt")));
    assertEquals(Optional.empty(), decidingRule(e, grep("./src/a.txt")));
  }

  @Test
  void ruleDecisionRejectsANullKey() {
    var e = evaluator(Permission.defaultInWorkspace(), tools());
    assertThrows(NullPointerException.class, () -> e.ruleDecision(null));
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
    var e = evaluator(Permission.empty(mode), ToolRegistry.empty());
    assertEquals(
        new PermissionDecision(effect, reason, Optional.empty()),
        e.evaluate(ToolPermissionKey.of("T"), category));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"READ", "SEARCH", "WRITE", "EXECUTION", "NETWORK", "CONTROL", "DELEGATION"})
  void lockedDownDeniesEveryCategoryByDefault(ToolCategory category) {
    var e = evaluator(Permission.empty(PermissionMode.LOCKED_DOWN), ToolRegistry.empty());
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
    DefaultPermissionEvaluator.newBuilder(
            Permission.empty(PermissionMode.DEFAULT), tools(binding("Fetch", ToolCategory.NETWORK)))
        .withApprover(ToolApprover.askingUser(gateway))
        .build()
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
