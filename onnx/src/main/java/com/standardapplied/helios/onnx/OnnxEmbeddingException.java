/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.onnx;

/**
 * Unchecked exception for ONNX embedding initialization failures: thrown when an embedding model
 * cannot be created because its model or tokenizer file is missing after the download, or the
 * download or ONNX Runtime failed.
 */
public class OnnxEmbeddingException extends RuntimeException {

  /**
   * Creates the exception for a failure with no underlying exception, such as a missing file.
   *
   * @param message what failed, naming the file or model concerned
   */
  public OnnxEmbeddingException(String message) {
    super(message);
  }

  /**
   * Creates the exception for a failure caused by another exception, such as an I/O or ONNX Runtime
   * error.
   *
   * @param message what failed
   * @param cause the exception that caused the failure; may be {@code null}
   */
  public OnnxEmbeddingException(String message, Throwable cause) {
    super(message, cause);
  }
}
