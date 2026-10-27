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
 * com.standardapplied.helios.session.memory.MemoryWriteTool} are the model-facing tools. A session
 * with a memory backend ({@code SessionOptions.Builder.withMemoryBackend}) registers both; for
 * read-only memory, deny {@code MemoryWrite} with a permission rule. The workspace preset sets a
 * backend, the read-only preset does not.
 */
package com.standardapplied.helios.session.memory;
