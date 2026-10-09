/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Helios - REPL Module.
 *
 * <p>Provides sandboxed JShell execution for the {@code Execute} tool:
 *
 * <ul>
 *   <li>Stateful REPL sessions with isolated JVM subprocess sandboxes
 *   <li>JSON-RPC 2.0 protocol for host-sandbox communication
 *   <li>Custom host functions callable from sandbox code
 *   <li>{@code JShellExecutionProvider}, which serves {@code Runtime.JSHELL}
 * </ul>
 */
module com.standardapplied.helios.repl {
  requires com.standardapplied.helios.core;
  requires com.standardapplied.helios.session;
  requires java.logging;
  requires java.management;
  requires jdk.jshell;
  requires tools.jackson.databind;

  exports com.standardapplied.helios.repl;
  exports com.standardapplied.helios.repl.execution;
  exports com.standardapplied.helios.repl.sandbox;
  exports com.standardapplied.helios.repl.sandbox.policy;
  exports com.standardapplied.helios.repl.host;
  exports com.standardapplied.helios.repl.protocol;
}
