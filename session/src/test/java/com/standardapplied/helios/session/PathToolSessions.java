/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.memory.FileSystemMemoryBackend;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionEffect;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.tools.ToolBinding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Sessions over the path tools ({@code Read}, {@code LS}, {@code Glob}, {@code Grep} and memory) on
 * a strict workspace holding {@code secrets/key} ({@code TOPSECRET}), {@code docs/a.txt} and {@code
 * link -> secrets}, with {@code Read}'s executor wrapped to count its runs.
 */
final class PathToolSessions {

  static final String SECRET = "TOPSECRET";

  final Path root;
  final WorkspaceRoot workspace;
  final AtomicInteger readRuns = new AtomicInteger();

  PathToolSessions(Path dir) throws IOException {
    root = dir.toRealPath();
    write("secrets/key", SECRET);
    write("docs/a.txt", "alpha");
    Files.createSymbolicLink(root.resolve("link"), root.resolve("secrets"));
    workspace = WorkspaceRoot.of(root);
  }

  /** Writes {@code content} at {@code relative}, creating its parents. */
  void write(String relative, String content) throws IOException {
    var file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  /** The workspace path tools, {@code Read} counting its runs. */
  List<ToolBinding> tools(WorkspaceRoot on) {
    return List.of(
        countingRuns(ReadTool.binding(on)),
        LsTool.binding(on),
        GlobTool.binding(on),
        GrepTool.binding(on));
  }

  private ToolBinding countingRuns(ToolBinding read) {
    var tool = read.tool();
    var counted =
        new Tool(
            tool.name(),
            tool.description(),
            tool.parameters(),
            (args, ctx) -> {
              readRuns.incrementAndGet();
              return tool.executor().execute(args, ctx);
            });
    return new ToolBinding(
        counted,
        read.category(),
        read.permissionKeyExtractor(),
        read.visibility(),
        read.pathArgument());
  }

  /** Options of a session holding the path tools and memory under {@code permission}. */
  SessionOptions.Builder options(Permission permission) {
    return withDefaultPolicy().withPermission(permission);
  }

  /** Options of a session holding the path tools and memory with the default permission policy. */
  SessionOptions.Builder withDefaultPolicy() {
    return SessionOptions.newBuilder()
        .withTools(tools(workspace))
        .withMemoryBackend(FileSystemMemoryBackend.of(workspace));
  }

  /**
   * A rule with the list of a policy it goes in.
   *
   * @param effect the list: allow, ask or deny
   * @param rule the rule
   */
  record ListedRule(PermissionEffect effect, PermissionRule rule) {}

  /** A rule over {@code glob}, for the {@code effect} list. */
  static ListedRule rule(PermissionEffect effect, String tool, String glob) {
    return new ListedRule(effect, PermissionRule.of(tool, glob));
  }

  /** A rule over every call of {@code tool}, for the {@code effect} list. */
  static ListedRule rule(PermissionEffect effect, String tool) {
    return new ListedRule(effect, PermissionRule.any(tool));
  }

  /** A policy of {@code mode} holding {@code rules}, each in the list of its effect. */
  static Permission policy(PermissionMode mode, ListedRule... rules) {
    return new Permission(
        mode,
        listOf(rules, PermissionEffect.ALLOW),
        listOf(rules, PermissionEffect.ASK),
        listOf(rules, PermissionEffect.DENY));
  }

  private static List<PermissionRule> listOf(ListedRule[] rules, PermissionEffect effect) {
    return Stream.of(rules)
        .filter(listed -> listed.effect() == effect)
        .map(ListedRule::rule)
        .toList();
  }

  static ToolCall call(String tool, Map<String, Object> args) {
    return new ToolCall("c1", tool, args);
  }

  static ToolCall read(String path) {
    return call(ReadTool.NAME, Map.of("path", path));
  }

  /** The output of the run's only tool result. */
  static String output(WriteToolSessions.Run run) {
    return run.onlyResult().output();
  }

  /** Asserts that no event of the run carries {@link #SECRET}. */
  static void assertNoSecretEmitted(WriteToolSessions.Run run) {
    assertEquals(
        List.of(),
        run.events().events().stream()
            .map(Object::toString)
            .filter(event -> event.contains(SECRET))
            .toList());
  }
}
