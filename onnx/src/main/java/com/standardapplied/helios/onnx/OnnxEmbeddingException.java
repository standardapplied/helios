/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.onnx;

/** Unchecked exception for ONNX embedding initialization failures. */
public class OnnxEmbeddingException extends RuntimeException {

  public OnnxEmbeddingException(String message) {
    super(message);
  }

  public OnnxEmbeddingException(String message, Throwable cause) {
    super(message, cause);
  }
}
