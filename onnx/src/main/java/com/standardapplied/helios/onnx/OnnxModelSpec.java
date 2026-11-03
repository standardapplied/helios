/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Internal metadata for known ONNX models — revision, architecture, dimensions, and prefixes.
 *
 * @param revision the full commit SHA of the model's Hugging Face repository every file is
 *     downloaded from, so every user of a Helios release gets the same files
 * @param modelType encoder (mean pooling) or decoder (last-token pooling)
 * @param onnxSubfolder subfolder within the HuggingFace repo containing the ONNX file
 * @param onnxFilenamePrefix stem of the desired ONNX variant (e.g. "model" picks "model.onnx" +
 *     "model.onnx_data" and skips "model_fp16.onnx", "model_quantized.onnx", etc.)
 * @param sequenceLength maximum input token count for this model; the tokenizer truncates longer
 *     input to this many tokens, keeping the model's special tokens
 * @param embeddingDimension output vector dimensionality for this model
 * @param queryPrefix prefix for query embeddings
 * @param documentPrefix prefix for document embeddings
 */
record OnnxModelSpec(
    String revision,
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

  private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");

  private static final Map<String, OnnxModelSpec> KNOWN_MODELS =
      Map.of(
          "nomic-ai/nomic-embed-text-v1.5",
          new OnnxModelSpec(
              "e9b6763023c676ca8431644204f50c2b100d9aab",
              ModelType.ENCODER,
              "onnx",
              "model",
              8192,
              768,
              "",
              ""),
          "onnx-community/embeddinggemma-300m-ONNX",
          new OnnxModelSpec(
              "5090578d9565bb06545b4552f76e6bc2c93e4a66",
              ModelType.DECODER,
              "onnx",
              "model",
              2048,
              768,
              "task: search result | query: ",
              "title: none | text: "),
          "onnx-community/harrier-oss-v1-270m-ONNX",
          new OnnxModelSpec(
              "d59c919d0159aea2c19ed7d04288fcdd048d0f9c",
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
              "e4daffa011e666dcd2ff2a3c6c05084090ac314d",
              ModelType.DECODER,
              "onnx",
              "model",
              32768,
              1024,
              "Instruct: Given a web search query, retrieve relevant passages that answer the query"
                  + "\nQuery: ",
              ""));

  OnnxModelSpec {
    if (revision == null || !COMMIT_SHA.matcher(revision).matches()) {
      throw new IllegalArgumentException(
          "revision must be a 40-character lowercase hex commit SHA, got: " + revision);
    }
  }

  static Optional<OnnxModelSpec> lookup(String modelName) {
    return Optional.ofNullable(KNOWN_MODELS.get(modelName));
  }

  static boolean isKnown(String modelName) {
    return KNOWN_MODELS.containsKey(modelName);
  }

  HuggingFaceTokenizer tokenizer(Path tokenizerPath) throws IOException {
    var limit = String.valueOf(sequenceLength);
    return HuggingFaceTokenizer.newInstance(
        tokenizerPath, Map.of("maxLength", limit, "modelMaxLength", limit));
  }
}
