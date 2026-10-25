/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import com.standardapplied.helios.session.tools.ToolCategory;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The policy over canonical path keys: the presets allow every in-root read however it is spelled,
 * and building an evaluator accepts every rule a canonical key can match or that names no path
 * tool.
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
  void defaultInWorkspaceAllowsEveryInRootTarget(String tool, Map<String, Object> args) {
    assertEquals(
        PermissionEffect.ALLOW,
        decide(Permission.defaultInWorkspace(), tool, args).effect(),
        tool + args);
  }

  @ParameterizedTest(name = "planMode: {0} {1}")
  @MethodSource("workspaceCalls")
  void planModeAllowsEveryInRootTarget(String tool, Map<String, Object> args) {
    assertEquals(
        PermissionEffect.ALLOW, decide(Permission.planMode(), tool, args).effect(), tool + args);
  }

  @Test
  void defaultInWorkspaceAllowsAMemoryWriteByItsOwnRule() {
    var preset = Permission.defaultInWorkspace();
    assertEquals(
        Optional.of(preset.allow().getFirst()),
        decide(preset, "MemoryWrite", Map.of("path", "/memories/notes.md")).rule());
  }

  private static final Map<String, Map<String, Object>> PRESET_CALLS =
      Map.of(
          "Read", Map.of("path", "src/Foo.java"),
          "LS", Map.of(),
          "Glob", Map.of("pattern", "**"),
          "Grep", Map.of("pattern", "x", "path", "."),
          "MemoryRead", Map.of("path", "/memories/notes.md"),
          "MemoryWrite", Map.of("path", "/memories/notes.md"));

  private static final Map<String, ToolPermissionKey> UNREGISTERED_KEYS =
      Map.of(
          "Execute", new ToolPermissionKey("Execute", "BASH/ls"),
          "Fetch", ToolPermissionKey.of("Fetch"),
          "AskUserQuestion", ToolPermissionKey.of("AskUserQuestion"));

  private PermissionEffect presetEffect(Permission preset, String tool, ToolCategory category) {
    var evaluator = DefaultPermissionEvaluator.newBuilder(preset, tools).build();
    var key =
        PRESET_CALLS.containsKey(tool)
            ? canonical(tool, PRESET_CALLS.get(tool)).key()
            : UNREGISTERED_KEYS.get(tool);
    return evaluator.evaluate(key, category).effect();
  }

  @ParameterizedTest(name = "{0}: planMode {2}, defaultInWorkspace {3}")
  @CsvSource({
    "Read, READ, ALLOW, ALLOW",
    "LS, READ, ALLOW, ALLOW",
    "Glob, SEARCH, ALLOW, ALLOW",
    "Grep, SEARCH, ALLOW, ALLOW",
    "MemoryRead, READ, ALLOW, ALLOW",
    "MemoryWrite, WRITE, DENY, ALLOW",
    "Execute, EXECUTION, DENY, ASK",
    "Fetch, NETWORK, ASK, ASK",
    "AskUserQuestion, CONTROL, ALLOW, ALLOW"
  })
  void eachPresetDecidesEveryToolAsBefore(
      String tool,
      ToolCategory category,
      PermissionEffect planMode,
      PermissionEffect defaultInWorkspace) {
    assertEquals(planMode, presetEffect(Permission.planMode(), tool, category));
    assertEquals(defaultInWorkspace, presetEffect(Permission.defaultInWorkspace(), tool, category));
  }

  // ── rules that can match ─────────────────────────────────────────────────

  private DefaultPermissionEvaluator.Builder builderWith(PermissionRule rule) {
    return DefaultPermissionEvaluator.newBuilder(
        new Permission(PermissionMode.DEFAULT, List.of(), List.of(), List.of(rule)), tools);
  }

  static Stream<PermissionRule> matchableRules() {
    return Stream.of(
        PermissionRule.of("Read", "./secrets/**"),
        PermissionRule.of("Read", "**/secrets.*"),
        PermissionRule.of("LS", "."),
        PermissionRule.any("Read"),
        PermissionRule.of("MemoryRead", "/memories/"),
        PermissionRule.of("MemoryRead", "**"),
        PermissionRule.of("Execute", "BASH/**rm **"),
        PermissionRule.of("Unregistered", "anything"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matchableRules")
  void aRuleThatCanMatchOrNamesNoPathToolBuilds(PermissionRule rule) {
    var evaluator = builderWith(rule).build();
    assertEquals(List.of(rule), evaluator.permission().deny());
  }
}
