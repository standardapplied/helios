/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.PathToolSessions.SECRET;
import static com.standardapplied.helios.session.PathToolSessions.assertNoSecretEmitted;
import static com.standardapplied.helios.session.PathToolSessions.call;
import static com.standardapplied.helios.session.PathToolSessions.output;
import static com.standardapplied.helios.session.PathToolSessions.read;
import static com.standardapplied.helios.session.WriteToolSessions.answering;
import static com.standardapplied.helios.session.WriteToolSessions.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.memory.MemoryReadTool;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The memory store is reachable only through the memory tools: in a workspace session, the
 * workspace tools refuse a path in it and their walks skip it, so a {@code MemoryRead} rule
 * protects it. A session without a memory backend refuses it the same way.
 */
final class MemoryStoreIsolationTest {

  private static final Permission DENY_PRIVATE =
      new Permission(
          PermissionMode.DEFAULT,
          List.of(PermissionRule.of("MemoryWrite", "/memories/**")),
          List.of(),
          List.of(PermissionRule.of(MemoryReadTool.NAME, "/memories/private/**")));

  @TempDir Path tmp;

  private PathToolSessions sessions;

  @BeforeEach
  void fixture() throws IOException {
    sessions = new PathToolSessions(tmp);
    sessions.write(".agent/memory/private/a.md", SECRET);
    sessions.write(".agent/memory/public.md", "public note");
    sessions.write(".agent/other.md", "beside the store");
  }

  private WriteToolSessions.Run workspaceRun(ToolCall toolCall) {
    var model = answering(toolCall);
    return run(model, SessionPresets.workspace(model, sessions.root).withPermission(DENY_PRIVATE));
  }

  private static void assertRefusedAsInTheStore(
      WriteToolSessions.Run run, String tool, String path) {
    assertEquals(
        new ToolOutcome.Rejected(
            "Permission", tool + ": path is in the memory store; use MemoryRead: " + path),
        run.onlyResult().outcome());
    assertNoSecretEmitted(run);
  }

  @Test
  void readOfADeniedNoteIsRefused() {
    var run = workspaceRun(read("./.agent/memory/private/a.md"));

    assertRefusedAsInTheStore(run, ReadTool.NAME, "./.agent/memory/private/a.md");
  }

  @Test
  void grepStartedInTheStoreIsRefused() {
    var run = workspaceRun(call(GrepTool.NAME, Map.of("pattern", "TOP", "path", ".agent/memory")));

    assertRefusedAsInTheStore(run, GrepTool.NAME, "./.agent/memory");
  }

  @ParameterizedTest(name = "from {0}")
  @ValueSource(strings = {".", ".agent"})
  void grepNeverReturnsAnEntryInTheStore(String path) {
    var run = workspaceRun(call(GrepTool.NAME, Map.of("pattern", SECRET, "path", path)));

    assertFalse(output(run).contains("memory"), output(run));
  }

  @Test
  void aGrepFromTheParentStillSearchesBesideTheStore() {
    var run = workspaceRun(call(GrepTool.NAME, Map.of("pattern", "beside", "path", ".agent")));

    assertEquals(".agent/other.md:1:beside the store\n", output(run));
  }

  @Test
  void globFromTheParentListsNothingInTheStore() {
    var run = workspaceRun(call(GlobTool.NAME, Map.of("pattern", "**", "path", ".agent")));

    assertEquals(".agent/other.md\n", output(run));
  }

  @Test
  void lsOfTheParentNamesTheStoreButNothingInIt() {
    var run = workspaceRun(call(LsTool.NAME, Map.of("path", ".agent")));

    assertEquals("memory/\nother.md\n", output(run));
  }

  @Test
  void theMemoryToolsStillGoThroughTheirOwnRules() {
    var denied = workspaceRun(call(MemoryReadTool.NAME, Map.of("path", "/memories/private/a.md")));
    var allowed = workspaceRun(call(MemoryReadTool.NAME, Map.of("path", "/memories/public.md")));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "deny rule MemoryRead(/memories/private/**) matched"
                + " MemoryRead(/memories/private/a.md)"),
        denied.onlyResult().outcome());
    assertNoSecretEmitted(denied);
    assertEquals("public note", output(allowed));
  }

  @Test
  void aSessionWithoutAMemoryBackendRefusesTheStoreTheSameWay() {
    var model = answering(read(".agent/memory/private/a.md"));

    var run = run(model, SessionPresets.readOnly(model, sessions.root));

    assertRefusedAsInTheStore(run, ReadTool.NAME, "./.agent/memory/private/a.md");
  }
}
