/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.protocol;

import ai.singlr.core.common.Strings;

/**
 * JSON-RPC 2.0 error object.
 *
 * @param code the error code (standard or application-defined)
 * @param message human-readable error description
 * @param data optional additional error data
 */
public record RpcError(int code, String message, Object data) {

  /** Parse error: invalid JSON. */
  public static final int PARSE_ERROR = -32700;

  /** Invalid Request: not a valid JSON-RPC 2.0 request. */
  public static final int INVALID_REQUEST = -32600;

  /** Method not found. */
  public static final int METHOD_NOT_FOUND = -32601;

  /** Invalid params. */
  public static final int INVALID_PARAMS = -32602;

  /** Internal error. */
  public static final int INTERNAL_ERROR = -32603;

  public RpcError {
    if (Strings.isBlank(message)) {
      throw new IllegalArgumentException("Error message must not be null or blank");
    }
  }

  /** Create an error with no additional data. */
  public static RpcError of(int code, String message) {
    return new RpcError(code, message, null);
  }

  /** Create a method-not-found error. */
  public static RpcError methodNotFound(String method) {
    return new RpcError(METHOD_NOT_FOUND, "Method not found: " + method, null);
  }

  /** Create an internal error. */
  public static RpcError internalError(String message) {
    return new RpcError(INTERNAL_ERROR, message, null);
  }
}
