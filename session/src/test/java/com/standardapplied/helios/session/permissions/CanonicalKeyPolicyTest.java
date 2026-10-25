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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The policy over canonical path keys: each preset decides every tool as before, and building an
 * evaluator accepts every rule a canonical key can match or that names no path tool.
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

  private PermissionDecision evaluate(
      Permission permission, ToolPermissionKey key, ToolCategory category) {
    return DefaultPermissionEvaluator.newBuilder(permission, tools).build().evaluate(key, category);
  }

  // ── presets ──────────────────────────────────────────────────────────────

  @Test
  void defaultInWorkspaceAllowsAMemoryWriteByItsOwnRule() {
    var preset = Permission.defaultInWorkspace();
    var key = canonical("MemoryWrite", Map.of("path", "/memories/notes.md")).key();
    assertEquals(
        Optional.of(preset.allow().getFirst()), evaluate(preset, key, ToolCategory.WRITE).rule());
  }

  @ParameterizedTest(name = "{0}({2}): planMode {3}, defaultInWorkspace {4}")
  @CsvSource({
    "Read, READ, ./src/Foo.java, ALLOW, ALLOW",
    "LS, READ, ., ALLOW, ALLOW",
    "Glob, SEARCH, ., ALLOW, ALLOW",
    "Grep, SEARCH, ., ALLOW, ALLOW",
    "MemoryRead, READ, /memories/notes.md, ALLOW, ALLOW",
    "MemoryWrite, WRITE, /memories/notes.md, DENY, ALLOW",
    "Execute, EXECUTION, BASH/ls, DENY, ASK",
    "Fetch, NETWORK, '', ASK, ASK",
    "AskUserQuestion, CONTROL, '', ALLOW, ALLOW"
  })
  void eachPresetDecidesEveryToolAsBefore(
      String tool,
      ToolCategory category,
      String canonicalArgs,
      PermissionEffect planMode,
      PermissionEffect defaultInWorkspace) {
    var key = new ToolPermissionKey(tool, canonicalArgs);
    assertEquals(planMode, evaluate(Permission.planMode(), key, category).effect());
    assertEquals(
        defaultInWorkspace, evaluate(Permission.defaultInWorkspace(), key, category).effect());
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
