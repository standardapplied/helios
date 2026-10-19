/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.InMemoryFileTracker;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.memory.FileSystemMemoryBackend;
import com.standardapplied.helios.session.memory.MemoryReadTool;
import com.standardapplied.helios.session.memory.MemoryWriteTool;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.session.tools.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The policy over canonical path keys: the presets' own rules decide every in-root target however
 * it is spelled, {@link DefaultPermissionEvaluator#authorize} decides on the key it is handed,
 * {@link DefaultPermissionEvaluator#denies} answers for DENY rules alone, and building an evaluator
 * refuses a path rule that no canonical key can match.
 */
final class CanonicalKeyPolicyTest {

  @TempDir Path tmp;

  private ToolRegistry tools;

  @BeforeEach
  void workspace() throws IOException {
    Files.createDirectories(tmp.resolve("src"));
    var workspace = WorkspaceRoot.of(tmp);
    var memory = FileSystemMemoryBackend.of(workspace);
    tools =
        new ToolRegistry(
            List.of(
                ReadTool.binding(workspace, InMemoryFileTracker.create()),
                LsTool.binding(workspace),
                GlobTool.binding(workspace),
                GrepTool.binding(workspace),
                MemoryReadTool.binding(memory),
                MemoryWriteTool.binding(memory)));
  }

  private ToolBinding.CanonicalCall canonical(String tool, Map<String, Object> args) {
    return tools.get(tool).orElseThrow().canonicalize(new ToolCall("c1", tool, args));
  }

  private PermissionDecision decide(Permission permission, String tool, Map<String, Object> args) {
    var canonical = canonical(tool, args);
    return DefaultPermissionEvaluator.newBuilder(permission, tools)
        .build()
        .evaluate(canonical.key(), tools.get(tool).orElseThrow().category());
  }

  private static PermissionRule allowRule(Permission permission, String tool) {
    return permission.allow().stream()
        .filter(rule -> rule.toolName().equals(tool))
        .findFirst()
        .orElseThrow();
  }

  // ── presets ──────────────────────────────────────────────────────────────

  static Stream<Arguments> workspaceCalls() {
    return Stream.of(
        Arguments.of("Read", Map.of("path", "src/Foo.java")),
        Arguments.of("Read", Map.of("path", "./src/Foo.java")),
        Arguments.of("LS", Map.of()),
        Arguments.of("Grep", Map.of("pattern", "x", "path", ".")));
  }

  @ParameterizedTest(name = "defaultInWorkspace: {0} {1}")
  @MethodSource("workspaceCalls")
  void defaultInWorkspaceAllowsEveryInRootTargetByItsOwnRule(
      String tool, Map<String, Object> args) {
    var preset = Permission.defaultInWorkspace();
    assertEquals(
        Optional.of(allowRule(preset, tool)), decide(preset, tool, args).rule(), tool + args);
  }

  @ParameterizedTest(name = "planMode: {0} {1}")
  @MethodSource("workspaceCalls")
  void planModeAllowsEveryInRootTargetByItsOwnRule(String tool, Map<String, Object> args) {
    var preset = Permission.planMode();
    assertEquals(
        Optional.of(allowRule(preset, tool)), decide(preset, tool, args).rule(), tool + args);
  }

  @Test
  void defaultInWorkspaceAllowsAMemoryReadByItsOwnRule() {
    var preset = Permission.defaultInWorkspace();
    assertEquals(
        Optional.of(allowRule(preset, "MemoryRead")),
        decide(preset, "MemoryRead", Map.of("path", "/memories/notes.md")).rule());
  }

  // ── authorize decides on the key it is given ─────────────────────────────

  @Test
  void authorizeDecidesOnTheKeyItIsHandedNotOneRecomputedFromTheArguments() {
    var deny = PermissionRule.withGlob(PermissionEffect.DENY, "Read", "./secrets/**");
    var evaluator =
        DefaultPermissionEvaluator.newBuilder(
                new Permission(PermissionMode.DEFAULT, List.of(), List.of(), List.of(deny)), tools)
            .build();
    var handed =
        new ToolBinding.CanonicalCall(
            new ToolCall("c1", "Read", Map.of("path", "./docs/a.txt")),
            new ToolPermissionKey("Read", "./secrets/key"));

    assertEquals(
        PermissionDecision.byRule(deny, "deny rule Read(./secrets/**) matched Read(./secrets/key)"),
        evaluator.authorize(handed));
  }

  // ── denies ───────────────────────────────────────────────────────────────

  @Test
  void deniesAnswersForDenyRulesAloneWhateverTheMode() {
    var permission =
        new Permission(
            PermissionMode.BYPASS_PERMISSIONS,
            List.of(PermissionRule.withGlob(PermissionEffect.ALLOW, "Grep", "./**")),
            List.of(PermissionRule.withGlob(PermissionEffect.ASK, "Grep", "./docs/**")),
            List.of(PermissionRule.withGlob(PermissionEffect.DENY, "Grep", "./secrets/**")));
    var evaluator = DefaultPermissionEvaluator.newBuilder(permission, tools).build();

    assertTrue(evaluator.denies(new ToolPermissionKey("Grep", "./secrets")));
    assertTrue(evaluator.denies(new ToolPermissionKey("Grep", "./secrets/key")));
    assertFalse(evaluator.denies(new ToolPermissionKey("Grep", "./docs/a.txt")));
    assertFalse(evaluator.denies(new ToolPermissionKey("Read", "./secrets/key")));
  }

  @Test
  void deniesRejectsANullKey() {
    var evaluator =
        DefaultPermissionEvaluator.newBuilder(Permission.defaultInWorkspace(), tools).build();
    assertThrows(NullPointerException.class, () -> evaluator.denies(null));
  }

  // ── rules that can never match ───────────────────────────────────────────

  private DefaultPermissionEvaluator.Builder builderWith(PermissionRule rule) {
    var rules = List.of(rule);
    var none = List.<PermissionRule>of();
    return DefaultPermissionEvaluator.newBuilder(
        new Permission(
            PermissionMode.DEFAULT,
            rule.effect() == PermissionEffect.ALLOW ? rules : none,
            rule.effect() == PermissionEffect.ASK ? rules : none,
            rule.effect() == PermissionEffect.DENY ? rules : none),
        tools);
  }

  static Stream<Arguments> unmatchableRules() {
    return Stream.of(
        Arguments.of(
            PermissionRule.withGlob(PermissionEffect.DENY, "Read", "secrets/**"),
            "rule Read(secrets/**) can never match: Read keys are canonical paths such as"
                + " ./src/Main.java"),
        Arguments.of(
            PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "./a/../b"),
            "rule Read(./a/../b) can never match: Read keys are canonical paths such as"
                + " ./src/Main.java"),
        Arguments.of(
            PermissionRule.withGlob(PermissionEffect.ASK, "LS", "./docs/"),
            "rule LS(./docs/) can never match: LS keys are canonical paths such as"
                + " ./src/Main.java"),
        Arguments.of(
            PermissionRule.withGlob(PermissionEffect.DENY, "MemoryRead", "memories/**"),
            "rule MemoryRead(memories/**) can never match: MemoryRead keys are canonical paths"
                + " such as /memories/notes.md"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unmatchableRules")
  void aPathRuleThatCanNeverMatchFailsTheBuild(PermissionRule rule, String message) {
    var builder = builderWith(rule);
    assertEquals(
        message, assertThrows(IllegalArgumentException.class, builder::build).getMessage());
  }

  static Stream<PermissionRule> matchableRules() {
    return Stream.of(
        PermissionRule.withGlob(PermissionEffect.DENY, "Read", "./secrets/**"),
        PermissionRule.withGlob(PermissionEffect.DENY, "Read", "**/secrets.*"),
        PermissionRule.withGlob(PermissionEffect.ALLOW, "LS", "."),
        PermissionRule.any(PermissionEffect.DENY, "Read"),
        PermissionRule.withGlob(PermissionEffect.DENY, "MemoryRead", "/memories/"),
        PermissionRule.withGlob(PermissionEffect.DENY, "MemoryRead", "**"),
        PermissionRule.withGlob(PermissionEffect.DENY, "Execute", "BASH/rm"),
        PermissionRule.withGlob(PermissionEffect.DENY, "Unregistered", "anything"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matchableRules")
  void aRuleThatCanMatchOrNamesNoPathToolBuilds(PermissionRule rule) {
    var evaluator = builderWith(rule).build();
    assertEquals(rule, evaluator.permission().firstRuleFor(rule.toolName()).orElseThrow());
  }
}
