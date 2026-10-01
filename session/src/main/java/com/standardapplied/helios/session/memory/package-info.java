/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * The {@code /memories/...} subsystem — Letta-inspired persistent notes the model reads + writes
 * across sessions to carry knowledge forward.
 *
 * <p>{@link com.standardapplied.helios.session.memory.MemoryBackend} is the storage abstraction
 * (list / read / write / delete keyed by a path-like name); {@link
 * com.standardapplied.helios.session.memory.FileSystemMemoryBackend} is the production
 * implementation that maps memory ids onto files under a {@link
 * com.standardapplied.helios.session.files.WorkspaceRoot}'s {@code /memories/} subdirectory. The
 * workspace root provides the same path-jail used by the file tools, so memories cannot escape the
 * configured workspace.
 *
 * <p>{@link com.standardapplied.helios.session.memory.MemoryReadTool} and {@link
 * com.standardapplied.helios.session.memory.MemoryWriteTool} are the model-facing tools. {@code
 * MemoryRead} is auto-registered by {@code AgentSessionImpl} whenever a backend is wired (so the
 * model can always inspect what's there); {@code MemoryWrite} is opt-in — the workspace preset
 * registers it explicitly, the read-only preset does not.
 */
package com.standardapplied.helios.session.memory;
