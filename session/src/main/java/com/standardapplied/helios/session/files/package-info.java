/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Filesystem tools the agent uses to inspect a workspace, plus the supporting types that bound and
 * audit those reads.
 *
 * <p>{@link ai.singlr.session.files.WorkspaceRoot} is the path-jail every file tool resolves
 * against — a two-stage lexical + {@code toRealPath} check refuses paths that escape via {@code ..}
 * or symlink dereference. {@link ai.singlr.session.files.ReadTool}, {@link
 * ai.singlr.session.files.GlobTool}, {@link ai.singlr.session.files.GrepTool}, and {@link
 * ai.singlr.session.files.LsTool} are the four read-side tools the session presets register; each
 * takes a {@link ai.singlr.session.files.WorkspaceRoot} at construction and surfaces an appropriate
 * {@link ai.singlr.session.tools.ToolCategory} for the permission system.
 *
 * <p>{@link ai.singlr.session.files.FileFingerprint} + {@link ai.singlr.session.files.FileSnapshot}
 * + {@link ai.singlr.session.files.FileTracker} are the stale-detection primitives the Phase 3 edit
 * tools will compare against to refuse writes against concurrently-modified files; {@link
 * ai.singlr.session.files.InMemoryFileTracker} is the per-session production implementation.
 */
package ai.singlr.session.files;
