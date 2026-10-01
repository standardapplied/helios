/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Helios - ONNX Embedding Provider Module.
 *
 * <p>Implements the EmbeddingProvider SPI for local ONNX Runtime inference. Downloads models from
 * HuggingFace and runs embedding generation locally.
 */
module com.standardapplied.helios.onnx {
  requires com.standardapplied.helios.core;
  requires java.logging;
  requires java.net.http;
  requires tools.jackson.databind;
  requires com.microsoft.onnxruntime;
  requires ai.djl.tokenizers;

  exports com.standardapplied.helios.onnx;

  provides com.standardapplied.helios.core.embedding.EmbeddingProvider with
      com.standardapplied.helios.onnx.OnnxEmbeddingProvider;
}
