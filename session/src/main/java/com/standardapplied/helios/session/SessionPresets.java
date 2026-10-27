/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.LsTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.memory.FileSystemMemoryBackend;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.tools.ToolBinding;
import java.nio.file.Path;
import java.util.List;

/**
 * Curated {@link SessionOptions.Builder} factories for the session shapes most callers want, each
 * returning a builder that already holds the model so callers chain further configuration before
 * {@code build()}:
 *
 * <pre>{@code
 * var options = SessionPresets.workspace(model, Path.of("/repo"))
 *     .withCostCalculator(calc)
 *     .build();
 * }</pre>
 *
 * <p>The session registers {@code MemoryRead} and {@code MemoryWrite} itself whenever a memory
 * backend is set, and {@code Execute} whenever an execution provider is: an agent that runs code is
 * {@code workspace(model, root).withExecutionProvider(provider)}. Further tools append with {@link
 * SessionOptions.Builder#withTool}; a root that must be trusted or curated is wired by hand on that
 * root.
 */
public final class SessionPresets {

  private SessionPresets() {}

  /**
   * The model and every default.
   *
   * @param model the model the session drives; non-null
   * @return a builder holding the model
   * @throws NullPointerException if {@code model} is null
   */
  public static SessionOptions.Builder minimal(Model model) {
    return SessionOptions.newBuilder().withModel(model);
  }

  /**
   * Read-only inspector: {@code Read}, {@code Glob}, {@code Grep} and {@code LS} rooted at {@code
   * root}, gated by {@link Permission#planMode()}.
   *
   * @param model the model the session drives; non-null
   * @param root the directory the read tools are confined to; non-null, must exist
   * @return a builder holding the model, the read tools and the plan-mode policy
   * @throws NullPointerException if any argument is null
   */
  public static SessionOptions.Builder readOnly(Model model, Path root) {
    return minimal(model)
        .withTools(tools(WorkspaceRoot.of(root)))
        .withPermission(Permission.planMode());
  }

  /**
   * Workspace agent: the read tools of {@link #readOnly}, a {@link FileSystemMemoryBackend} at
   * {@code root}, and {@link Permission#defaultInWorkspace()}, which allows reads and memory and
   * asks before writes or execution.
   *
   * @param model the model the session drives; non-null
   * @param root the directory the tools and memory are confined to; non-null, must exist
   * @return a builder holding the model, the read tools, the memory backend and the policy
   * @throws NullPointerException if any argument is null
   */
  public static SessionOptions.Builder workspace(Model model, Path root) {
    var workspace = WorkspaceRoot.of(root);
    return minimal(model)
        .withTools(tools(workspace))
        .withPermission(Permission.defaultInWorkspace())
        .withMemoryBackend(FileSystemMemoryBackend.of(workspace));
  }

  private static List<ToolBinding> tools(WorkspaceRoot workspace) {
    return List.of(
        ReadTool.binding(workspace),
        GlobTool.binding(workspace),
        GrepTool.binding(workspace),
        LsTool.binding(workspace));
  }
}
