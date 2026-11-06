/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.protocol;

import com.standardapplied.helios.core.common.Strings;

/**
 * JSON-RPC 2.0 message types. All messages carry a {@code jsonrpc} field with the value {@code
 * "2.0"}.
 */
public sealed interface RpcMessage {

  /** The JSON-RPC version constant. */
  String VERSION = "2.0";

  /**
   * A method call from one side to the other, expecting a response.
   *
   * @param id the request identifier
   * @param method the method name to invoke
   * @param params the method parameters (may be null)
   */
  record Request(String id, String method, Object params) implements RpcMessage {
    /**
     * Validates the id and method.
     *
     * @throws IllegalArgumentException if {@code id} or {@code method} is {@code null} or blank
     */
    public Request {
      if (Strings.isBlank(id)) {
        throw new IllegalArgumentException("Request id must not be null or blank");
      }
      if (Strings.isBlank(method)) {
        throw new IllegalArgumentException("Request method must not be null or blank");
      }
    }
  }

  /**
   * A successful response to a request.
   *
   * @param id the request identifier this responds to
   * @param result the result value (may be null)
   */
  record Response(String id, Object result) implements RpcMessage {
    /**
     * Validates the id.
     *
     * @throws IllegalArgumentException if {@code id} is {@code null} or blank
     */
    public Response {
      if (Strings.isBlank(id)) {
        throw new IllegalArgumentException("Response id must not be null or blank");
      }
    }
  }

  /**
   * An error response to a request.
   *
   * @param id the request identifier this responds to (may be null if parse error)
   * @param error the error details
   */
  record ErrorResponse(String id, RpcError error) implements RpcMessage {
    /**
     * Validates the error.
     *
     * @throws IllegalArgumentException if {@code error} is {@code null}
     */
    public ErrorResponse {
      if (error == null) {
        throw new IllegalArgumentException("Error must not be null");
      }
    }
  }

  /**
   * A one-way notification (no response expected).
   *
   * @param method the notification method
   * @param params the notification parameters (may be null)
   */
  record Notification(String method, Object params) implements RpcMessage {
    /**
     * Validates the method.
     *
     * @throws IllegalArgumentException if {@code method} is {@code null} or blank
     */
    public Notification {
      if (Strings.isBlank(method)) {
        throw new IllegalArgumentException("Notification method must not be null or blank");
      }
    }
  }
}
