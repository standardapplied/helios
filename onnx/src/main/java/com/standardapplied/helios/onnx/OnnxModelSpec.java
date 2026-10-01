/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.onnx;

import java.util.Map;
import java.util.Optional;

/**
 * Internal metadata for known ONNX models — architecture, dimensions, and prefixes.
 *
 * @param modelType encoder (mean pooling) or decoder (last-token pooling)
 * @param onnxSubfolder subfolder within the HuggingFace repo containing the ONNX file
 * @param onnxFilenamePrefix stem of the desired ONNX variant (e.g. "model" picks "model.onnx" +
 *     "model.onnx_data" and skips "model_fp16.onnx", "model_quantized.onnx", etc.)
 * @param sequenceLength maximum input token count for this model
 * @param embeddingDimension output vector dimensionality for this model
 * @param queryPrefix prefix for query embeddings
 * @param documentPrefix prefix for document embeddings
 */
record OnnxModelSpec(
    ModelType modelType,
    String onnxSubfolder,
    String onnxFilenamePrefix,
    int sequenceLength,
    int embeddingDimension,
    String queryPrefix,
    String documentPrefix) {

  enum ModelType {
    ENCODER,
    DECODER
  }

  private static final Map<String, OnnxModelSpec> KNOWN_MODELS =
      Map.of(
          "nomic-ai/nomic-embed-text-v1.5",
          new OnnxModelSpec(ModelType.ENCODER, "onnx", "model", 8192, 768, "", ""),
          "onnx-community/embeddinggemma-300m-ONNX",
          new OnnxModelSpec(
              ModelType.DECODER,
              "onnx",
              "model",
              2048,
              768,
              "task: search result | query: ",
              "title: none | text: "),
          "onnx-community/harrier-oss-v1-270m-ONNX",
          new OnnxModelSpec(
              ModelType.DECODER,
              "onnx",
              "model",
              32768,
              640,
              "Instruct: Given a web search query, retrieve relevant passages that answer the query"
                  + "\nQuery: ",
              ""),
          "onnx-community/harrier-oss-v1-0.6b-ONNX",
          new OnnxModelSpec(
              ModelType.DECODER,
              "onnx",
              "model",
              32768,
              1024,
              "Instruct: Given a web search query, retrieve relevant passages that answer the query"
                  + "\nQuery: ",
              ""));

  static Optional<OnnxModelSpec> lookup(String modelName) {
    return Optional.ofNullable(KNOWN_MODELS.get(modelName));
  }

  static boolean isKnown(String modelName) {
    return KNOWN_MODELS.containsKey(modelName);
  }
}
