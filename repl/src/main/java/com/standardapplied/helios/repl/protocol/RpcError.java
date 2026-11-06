/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.protocol;

import com.standardapplied.helios.core.common.Strings;

/**
 * JSON-RPC 2.0 error object.
 *
 * @param code the error code (standard or application-defined)
 * @param message human-readable error description; must not be {@code null} or blank
 * @param data optional additional error data; {@code null} when there is none
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

  /**
   * Validates the message.
   *
   * @throws IllegalArgumentException if {@code message} is {@code null} or blank
   */
  public RpcError {
    if (Strings.isBlank(message)) {
      throw new IllegalArgumentException("Error message must not be null or blank");
    }
  }

  /**
   * Creates an error with no additional data.
   *
   * @param code the error code, such as one of this record's constants
   * @param message what went wrong; must not be {@code null} or blank
   * @return the error, with {@code data} {@code null}
   * @throws IllegalArgumentException if {@code message} is {@code null} or blank
   */
  public static RpcError of(int code, String message) {
    return new RpcError(code, message, null);
  }

  /**
   * Creates a method-not-found error.
   *
   * @param method the method name the request named; {@code null} is written as {@code "null"}
   * @return an error with code {@link #METHOD_NOT_FOUND} and message {@code Method not found:
   *     <method>}
   */
  public static RpcError methodNotFound(String method) {
    return new RpcError(METHOD_NOT_FOUND, "Method not found: " + method, null);
  }

  /**
   * Creates an internal error.
   *
   * @param message what failed; must not be {@code null} or blank
   * @return an error with code {@link #INTERNAL_ERROR} and no data
   * @throws IllegalArgumentException if {@code message} is {@code null} or blank
   */
  public static RpcError internalError(String message) {
    return new RpcError(INTERNAL_ERROR, message, null);
  }
}
