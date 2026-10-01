/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.session.execution.ExecuteTool;
import ai.singlr.session.execution.ExecutionProvider;
import ai.singlr.session.files.GlobTool;
import ai.singlr.session.files.GrepTool;
import ai.singlr.session.files.InMemoryFileTracker;
import ai.singlr.session.files.LsTool;
import ai.singlr.session.files.ReadTool;
import ai.singlr.session.files.WorkspaceRoot;
import ai.singlr.session.memory.FileSystemMemoryBackend;
import ai.singlr.session.memory.MemoryWriteTool;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Curated {@link SessionPreset} factories for the patterns most agentic SDK users actually want —
 * assembled from real v2 building blocks so callers do not have to learn the tool / permission /
 * memory wiring on day one.
 *
 * <p>Every factory returns a {@link SessionPreset} — a {@code Builder -> Builder} function — so
 * callers apply it to a builder seeded with their model and chain further configuration on top:
 *
 * <pre>{@code
 * var options = SessionOptions.newBuilder()
 *     .withModel(model)
 *     .apply(SessionPresets.workspace(Path.of("/repo")))
 *     .apply(myCustomTracingPreset)
 *     .withCostCalculator(calc)
 *     .build();
 * }</pre>
 *
 * <p>Four curated presets ship today: {@link #minimal()}, {@link #readOnly(Path)} / {@link
 * #readOnly(WorkspaceRoot)}, {@link #workspace(Path)} / {@link #workspace(WorkspaceRoot)}, and
 * {@link #openEnded(Path, ExecutionProvider)} / {@link #openEnded(WorkspaceRoot,
 * ExecutionProvider)} — the last enables code execution via an explicit {@link ExecutionProvider}.
 * The model itself is set through {@link SessionOptions.Builder#withModel}, not a preset arg, so
 * the same preset can drive sessions against different providers.
 */
public final class SessionPresets {

  private SessionPresets() {}

  /**
   * Identity preset — applies no configuration, equivalent to using {@link
   * SessionOptions#newBuilder()} directly. Kept as a preset so consumers have a uniform {@code
   * SessionPresets.*} entrypoint for every shape.
   *
   * @return a no-op preset
   */
  public static SessionPreset minimal() {
    return b -> b;
  }

  /**
   * Read-only inspector: the four filesystem read tools ({@code Read}, {@code Glob}, {@code Grep},
   * {@code LS}) rooted at {@code workspaceRoot}, gated by {@link Permission#planMode()} so the
   * model cannot escalate to writes or execution.
   *
   * @param workspaceRoot the directory the read tools are confined to; non-null, must exist
   * @return a preset that wires read-only tooling
   * @throws NullPointerException if {@code workspaceRoot} is null
   */
  public static SessionPreset readOnly(Path workspaceRoot) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    return readOnly(WorkspaceRoot.of(workspaceRoot));
  }

  /**
   * {@code WorkspaceRoot} overload of {@link #readOnly(Path)} for callers that already hold a
   * constructed workspace.
   *
   * @param workspace the workspace the read tools are confined to; non-null
   * @return a preset that wires read-only tooling
   * @throws NullPointerException if {@code workspace} is null
   */
  public static SessionPreset readOnly(WorkspaceRoot workspace) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    var tools =
        new ToolRegistry(
            List.of(
                ReadTool.binding(workspace, InMemoryFileTracker.create()),
                GlobTool.binding(workspace),
                GrepTool.binding(workspace),
                LsTool.binding(workspace)));
    return b -> b.withTools(tools).withPermission(Permission.planMode());
  }

  /**
   * Full workspace agent: same read tools as {@link #readOnly(WorkspaceRoot)}, plus persistent
   * memory (via {@link FileSystemMemoryBackend} rooted at the workspace), plus the {@link
   * Permission#defaultInWorkspace()} policy that allows reads / memory and asks the user before
   * writes or execution.
   *
   * <p>The {@code MemoryRead} tool is auto-registered by the session whenever a memory backend is
   * present; {@code MemoryWrite} is added to the registry explicitly here so the model can persist
   * notes between turns.
   *
   * @param workspaceRoot the directory the tools are confined to; non-null, must exist
   * @return a preset that wires workspace tooling, memory, and the default permission policy
   * @throws NullPointerException if {@code workspaceRoot} is null
   */
  public static SessionPreset workspace(Path workspaceRoot) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    return workspace(WorkspaceRoot.of(workspaceRoot));
  }

  /**
   * {@code WorkspaceRoot} overload of {@link #workspace(Path)} for callers that already hold a
   * constructed workspace.
   *
   * @param workspace the workspace; non-null
   * @return a preset that wires workspace tooling, memory, and the default permission policy
   * @throws NullPointerException if {@code workspace} is null
   */
  public static SessionPreset workspace(WorkspaceRoot workspace) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    var memoryBackend = FileSystemMemoryBackend.of(workspace);
    var tools =
        new ToolRegistry(
            List.of(
                ReadTool.binding(workspace, InMemoryFileTracker.create()),
                GlobTool.binding(workspace),
                GrepTool.binding(workspace),
                LsTool.binding(workspace),
                MemoryWriteTool.binding(memoryBackend)));
    return b ->
        b.withTools(tools)
            .withPermission(Permission.defaultInWorkspace())
            .withMemoryBackend(memoryBackend);
  }

  /**
   * Open-ended agent: workspace tools (Read / Glob / Grep / LS + MemoryWrite) plus the {@link
   * ExecuteTool} dispatched through the supplied {@link ExecutionProvider}. Permission policy is
   * {@link Permission#defaultInWorkspace()} which already asks before {@code Execute}, so the user
   * confirms each execution by default.
   *
   * <p>Use {@link #openEnded(WorkspaceRoot, ExecutionProvider)} if you already hold a {@code
   * WorkspaceRoot}. The execution provider is required and must not be {@code null} — if you want
   * to opt out of execution, apply {@link #workspace(Path)} instead.
   *
   * @param workspaceRoot the directory the workspace tools are confined to; non-null, must exist
   * @param executionProvider the provider routing {@code Execute} dispatches; non-null
   * @return a preset that wires workspace tooling, the {@code Execute} tool, memory, the default
   *     permission policy, and the supplied execution provider
   * @throws NullPointerException if any argument is null
   */
  public static SessionPreset openEnded(Path workspaceRoot, ExecutionProvider executionProvider) {
    Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
    return openEnded(WorkspaceRoot.of(workspaceRoot), executionProvider);
  }

  /**
   * {@code WorkspaceRoot} overload of {@link #openEnded(Path, ExecutionProvider)} for callers that
   * already hold a constructed workspace.
   *
   * @param workspace the workspace; non-null
   * @param executionProvider the provider routing {@code Execute} dispatches; non-null
   * @return a preset that wires workspace tooling plus the {@code Execute} tool
   * @throws NullPointerException if any argument is null
   */
  public static SessionPreset openEnded(
      WorkspaceRoot workspace, ExecutionProvider executionProvider) {
    Objects.requireNonNull(workspace, "workspace must not be null");
    Objects.requireNonNull(executionProvider, "executionProvider must not be null");
    var memoryBackend = FileSystemMemoryBackend.of(workspace);
    var bindings = new ArrayList<ToolBinding>(6);
    bindings.add(ReadTool.binding(workspace, InMemoryFileTracker.create()));
    bindings.add(GlobTool.binding(workspace));
    bindings.add(GrepTool.binding(workspace));
    bindings.add(LsTool.binding(workspace));
    bindings.add(MemoryWriteTool.binding(memoryBackend));
    bindings.add(ExecuteTool.binding(executionProvider));
    return b ->
        b.withTools(new ToolRegistry(bindings))
            .withPermission(Permission.defaultInWorkspace())
            .withMemoryBackend(memoryBackend)
            .withExecutionProvider(executionProvider);
  }
}
