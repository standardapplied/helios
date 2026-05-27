/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.onnx;

import ai.singlr.core.embedding.EmbeddingConfig;
import ai.singlr.core.embedding.EmbeddingModel;
import ai.singlr.core.embedding.EmbeddingProvider;
import java.util.logging.Logger;

/**
 * ONNX Runtime embedding provider. Downloads models from HuggingFace and runs inference locally via
 * ONNX Runtime.
 *
 * <p>Discovered via JPMS ServiceLoader. Supports known models registered in {@link OnnxModelSpec}.
 */
public final class OnnxEmbeddingProvider implements EmbeddingProvider {

  private static final Logger LOGGER = Logger.getLogger(OnnxEmbeddingProvider.class.getName());

  /** No-arg constructor for ServiceLoader. */
  public OnnxEmbeddingProvider() {}

  @Override
  public String name() {
    return "onnx";
  }

  @Override
  public EmbeddingModel create(String modelName, EmbeddingConfig config) {
    var spec =
        OnnxModelSpec.lookup(modelName)
            .orElseThrow(
                () -> new IllegalArgumentException("Unsupported ONNX model: " + modelName));

    LOGGER.info("Creating ONNX embedding model: %s".formatted(modelName));
    return OnnxEmbeddingModel.create(modelName, config, spec);
  }

  @Override
  public boolean supports(String modelName) {
    return OnnxModelSpec.isKnown(modelName);
  }
}
