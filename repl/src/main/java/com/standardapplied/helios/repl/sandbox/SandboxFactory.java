/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import ai.singlr.repl.host.HostFunctionRegistry;

/**
 * Factory for creating sandbox instances. The registry is provided so the sandbox transport layer
 * can dispatch incoming host function calls.
 */
@FunctionalInterface
public interface SandboxFactory {

  /**
   * Create a new sandbox instance.
   *
   * @param registry the host function registry for this session
   * @return a new sandbox, ready to execute code
   */
  Sandbox create(HostFunctionRegistry registry);
}
