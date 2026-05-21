/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Singular Agentic Framework - REPL Module.
 *
 * <p>Provides sandboxed code execution for Recursive Language Model (RLM) patterns:
 *
 * <ul>
 *   <li>Stateful REPL sessions with isolated JVM subprocess sandboxes
 *   <li>JSON-RPC 2.0 protocol for host-sandbox communication
 *   <li>Host functions callable from sandbox code (predict, submit, user-defined)
 *   <li>CodeExecutionTool factory for agent integration
 * </ul>
 */
module ai.singlr.repl {
  requires ai.singlr.core;
  requires ai.singlr.session;
  requires java.logging;
  requires java.management;
  requires jdk.jshell;
  requires tools.jackson.databind;

  exports ai.singlr.repl;
  exports ai.singlr.repl.codeact;
  exports ai.singlr.repl.execution;
  exports ai.singlr.repl.sandbox;
  exports ai.singlr.repl.sandbox.policy;
  exports ai.singlr.repl.host;
  exports ai.singlr.repl.protocol;
}
