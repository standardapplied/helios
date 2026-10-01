/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.common.SecretRegistry;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.Model;
import ai.singlr.core.model.Response;
import ai.singlr.core.tool.Tool;
import ai.singlr.session.execution.ExecuteTool;
import ai.singlr.session.execution.LocalProcessExecutionProvider;
import ai.singlr.session.execution.NoopExecutionProvider;
import ai.singlr.session.files.WorkspaceRoot;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.permissions.PermissionMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SessionPresetsTest {

  private static Model stubModel() {
    return new Model() {
      @Override
      public Response<Void> chat(List<Message> messages, List<Tool> tools) {
        return Response.newBuilder().withContent("x").build();
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
  }

  private static SessionOptions.Builder seed() {
    return SessionOptions.newBuilder().withModel(stubModel());
  }

  private static SessionOptions build(SessionPreset preset) {
    return seed().apply(preset).build();
  }

  private static Set<String> toolNames(SessionOptions opts) {
    return opts.tools().bindings().stream()
        .map(b -> b.tool().name())
        .collect(Collectors.toUnmodifiableSet());
  }

  // ── minimal ─────────────────────────────────────────────────────────────

  @Test
  void minimalAppliesNoConfiguration() {
    var opts = build(SessionPresets.minimal());
    assertEquals("stub", opts.model().id());
    assertTrue(opts.tools().bindings().isEmpty(), "no tools by default");
    assertTrue(opts.permission().isEmpty(), "no permission policy by default");
    assertTrue(opts.memoryBackend().isEmpty(), "no memory backend by default");
    assertSame(CostCalculator.ZERO, opts.costCalculator());
  }

  @Test
  void minimalIsIdentityOnTheBuilder() {
    var builder = seed();
    var same = builder.apply(SessionPresets.minimal());
    assertSame(builder, same);
  }

  @Test
  void minimalComposesWithBuilderChaining() {
    var custom = CostCalculator.staticTable(Map.of());
    var opts = seed().apply(SessionPresets.minimal()).withCostCalculator(custom).build();
    assertSame(custom, opts.costCalculator());
  }

  // ── readOnly ────────────────────────────────────────────────────────────

  @Test
  void readOnlyWiresReadInspectionTools(@TempDir Path tmp) {
    var opts = build(SessionPresets.readOnly(tmp));
    assertEquals(Set.of("Read", "Glob", "Grep", "LS"), toolNames(opts));
  }

  @Test
  void readOnlyUsesPlanModePermission(@TempDir Path tmp) {
    var opts = build(SessionPresets.readOnly(tmp));
    var perm = opts.permission().orElseThrow();
    assertEquals(PermissionMode.PLAN, perm.mode());
  }

  @Test
  void readOnlyHasNoMemoryBackend(@TempDir Path tmp) {
    var opts = build(SessionPresets.readOnly(tmp));
    assertTrue(opts.memoryBackend().isEmpty());
  }

  @Test
  void readOnlyPathOverloadDelegatesToWorkspaceOverload(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    var fromPath = build(SessionPresets.readOnly(tmp));
    var fromWorkspace = build(SessionPresets.readOnly(ws));
    assertEquals(toolNames(fromPath), toolNames(fromWorkspace));
    assertEquals(
        fromPath.permission().orElseThrow().mode(),
        fromWorkspace.permission().orElseThrow().mode());
  }

  @Test
  void readOnlyRejectsNullWorkspace() {
    assertEquals(
        "workspaceRoot must not be null",
        assertThrows(NullPointerException.class, () -> SessionPresets.readOnly((Path) null))
            .getMessage());
    assertEquals(
        "workspace must not be null",
        assertThrows(
                NullPointerException.class, () -> SessionPresets.readOnly((WorkspaceRoot) null))
            .getMessage());
  }

  @Test
  void readOnlyComposesWithBuilderChaining(@TempDir Path tmp) {
    var custom = CostCalculator.staticTable(Map.of());
    var opts = seed().apply(SessionPresets.readOnly(tmp)).withCostCalculator(custom).build();
    assertSame(custom, opts.costCalculator());
  }

  // ── workspace ───────────────────────────────────────────────────────────

  @Test
  void workspaceWiresReadToolsPlusMemoryWrite(@TempDir Path tmp) {
    var opts = build(SessionPresets.workspace(tmp));
    assertEquals(Set.of("Read", "Glob", "Grep", "LS", "MemoryWrite"), toolNames(opts));
  }

  @Test
  void workspaceUsesDefaultInWorkspacePermission(@TempDir Path tmp) {
    var opts = build(SessionPresets.workspace(tmp));
    var perm = opts.permission().orElseThrow();
    assertEquals(Permission.defaultInWorkspace().mode(), perm.mode());
  }

  @Test
  void workspaceRegistersMemoryBackend(@TempDir Path tmp) {
    var opts = build(SessionPresets.workspace(tmp));
    assertTrue(opts.memoryBackend().isPresent());
  }

  @Test
  void workspacePathOverloadDelegatesToWorkspaceOverload(@TempDir Path tmp) {
    var ws = WorkspaceRoot.of(tmp);
    var fromPath = build(SessionPresets.workspace(tmp));
    var fromWorkspace = build(SessionPresets.workspace(ws));
    assertEquals(toolNames(fromPath), toolNames(fromWorkspace));
  }

  @Test
  void workspaceRejectsNullWorkspace() {
    assertEquals(
        "workspaceRoot must not be null",
        assertThrows(NullPointerException.class, () -> SessionPresets.workspace((Path) null))
            .getMessage());
    assertEquals(
        "workspace must not be null",
        assertThrows(
                NullPointerException.class, () -> SessionPresets.workspace((WorkspaceRoot) null))
            .getMessage());
  }

  @Test
  void workspaceComposesWithBuilderChaining(@TempDir Path tmp) {
    var custom = CostCalculator.staticTable(Map.of());
    var opts = seed().apply(SessionPresets.workspace(tmp)).withCostCalculator(custom).build();
    assertSame(custom, opts.costCalculator());
  }

  @Test
  void workspaceDefaultsExecutionProviderToNoop(@TempDir Path tmp) {
    var opts = build(SessionPresets.workspace(tmp));
    assertSame(NoopExecutionProvider.INSTANCE, opts.executionProvider());
  }

  // ── openEnded ───────────────────────────────────────────────────────────

  @Test
  void openEndedWiresExecuteToolAndProvider(@TempDir Path tmp) {
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var opts = build(SessionPresets.openEnded(tmp, provider));
    assertTrue(toolNames(opts).contains(ExecuteTool.NAME));
    assertSame(provider, opts.executionProvider());
    assertSame(PermissionMode.DEFAULT, opts.permission().orElseThrow().mode());
  }

  @Test
  void openEndedPathOverloadDelegatesToWorkspaceOverload(@TempDir Path tmp) {
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var ws = WorkspaceRoot.of(tmp);
    var fromPath = build(SessionPresets.openEnded(tmp, provider));
    var fromWorkspace = build(SessionPresets.openEnded(ws, provider));
    assertEquals(toolNames(fromPath), toolNames(fromWorkspace));
  }

  @Test
  void openEndedRejectsNullArguments(@TempDir Path tmp) {
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    assertEquals(
        "workspaceRoot must not be null",
        assertThrows(
                NullPointerException.class, () -> SessionPresets.openEnded((Path) null, provider))
            .getMessage());
    assertEquals(
        "workspace must not be null",
        assertThrows(
                NullPointerException.class,
                () -> SessionPresets.openEnded((WorkspaceRoot) null, provider))
            .getMessage());
    assertEquals(
        "executionProvider must not be null",
        assertThrows(NullPointerException.class, () -> SessionPresets.openEnded(tmp, null))
            .getMessage());
  }

  // ── composition ─────────────────────────────────────────────────────────

  @Test
  void presetsStackAssociativelyWithUserPresets(@TempDir Path tmp) {
    SessionPreset userPreset = b -> b.withSessionId("explicit");
    var opts = seed().apply(SessionPresets.workspace(tmp)).apply(userPreset).build();
    assertEquals("explicit", opts.sessionId());
    assertTrue(toolNames(opts).contains("MemoryWrite"));
  }

  @Test
  void laterPresetOverridesEarlierOnSameField(@TempDir Path tmp) {
    var opts =
        seed()
            .apply(SessionPresets.readOnly(tmp))
            .apply(b -> b.withPermission(Permission.defaultInWorkspace()))
            .build();
    assertEquals(Permission.defaultInWorkspace().mode(), opts.permission().orElseThrow().mode());
  }
}
