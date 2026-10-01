/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Helios - Session Module.
 *
 * <p>The open-ended, streamable, steerable agentic SDK. Provides the foundation for long-running
 * tool-using agents with first-class hooks, file-editing safety, swappable execution providers,
 * filesystem-backed memory, and tamper-evident audit.
 *
 * <p>Spec: {@code docs/specs/agentic-coding-sdk-java-v2.md}. This is the v2 SDK; v1's {@code
 * core.agent.Agent} surface has been removed.
 *
 * <p>The public surface in {@code com.standardapplied.helios.session} carries the value types
 * ({@code UserMessage}, {@code StopReason}, {@code SerializedError}), the concurrency primitives,
 * the sealed event/result hierarchies, the session API, hooks, file tools, execution providers,
 * memory, audit, and the preset surface. Subsystem-specific packages (e.g. {@code
 * com.standardapplied.helios.session.loop}) are exported as their first types land.
 */
module com.standardapplied.helios.session {
  requires com.standardapplied.helios.core;
  requires java.logging;
  requires tools.jackson.databind;

  exports com.standardapplied.helios.session;
  exports com.standardapplied.helios.session.ask;
  exports com.standardapplied.helios.session.execution;
  exports com.standardapplied.helios.session.files;
  exports com.standardapplied.helios.session.hooks;
  exports com.standardapplied.helios.session.loop;
  exports com.standardapplied.helios.session.memory;
  exports com.standardapplied.helios.session.permissions;
  exports com.standardapplied.helios.session.tools;
}
