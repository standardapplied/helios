/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.host;

import java.util.Map;

/** Handler for a host function callable from sandbox code. */
@FunctionalInterface
public interface HostFunctionHandler {

  /**
   * Handle a host function call.
   *
   * @param params the parameters from the sandbox
   * @return the result to send back to the sandbox
   * @throws Exception if the handler fails
   */
  Object handle(Map<String, Object> params) throws Exception;
}
