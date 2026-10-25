/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Filesystem tools the agent uses to inspect a workspace, plus the supporting types that bound
 * those reads.
 *
 * <p>{@link com.standardapplied.helios.session.files.WorkspaceRoot} is the path-jail every file
 * tool resolves against — a two-stage lexical + {@code toRealPath} check refuses paths that escape
 * via {@code ..} or symlink dereference. {@link com.standardapplied.helios.session.files.ReadTool},
 * {@link com.standardapplied.helios.session.files.GlobTool}, {@link
 * com.standardapplied.helios.session.files.GrepTool}, and {@link
 * com.standardapplied.helios.session.files.LsTool} are the four read-side tools the session presets
 * register; each takes a {@link com.standardapplied.helios.session.files.WorkspaceRoot} at
 * construction and surfaces an appropriate {@link
 * com.standardapplied.helios.session.tools.ToolCategory} for the permission system.
 */
package com.standardapplied.helios.session.files;
