/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.singlr.core.common.Result;
import ai.singlr.core.common.Strings;
import ai.singlr.core.embedding.EmbeddingConfig;
import ai.singlr.core.embedding.EmbeddingModel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ONNX Runtime-based embedding model. Downloads models from HuggingFace and runs inference locally.
 * Supports encoder models (mean pooling) and decoder models (last-token pooling).
 */
public final class OnnxEmbeddingModel implements EmbeddingModel {

  private static final Logger LOGGER = Logger.getLogger(OnnxEmbeddingModel.class.getName());

  private final String modelName;
  private final OnnxModelSpec spec;
  private final HuggingFaceTokenizer tokenizer;
  private final OrtEnvironment ortEnvironment;
  private final OrtSession ortSession;

  private OnnxEmbeddingModel(
      String modelName,
      OnnxModelSpec spec,
      HuggingFaceTokenizer tokenizer,
      OrtEnvironment ortEnvironment,
      OrtSession ortSession) {
    this.modelName = modelName;
    this.spec = spec;
    this.tokenizer = tokenizer;
    this.ortEnvironment = ortEnvironment;
    this.ortSession = ortSession;
  }

  static OnnxEmbeddingModel create(String modelName, EmbeddingConfig config, OnnxModelSpec spec) {
    try {
      LOGGER.info("Initializing ONNX embedding model: %s".formatted(modelName));

      Path modelPath;
      Path tokenizerPath;
      try (var downloader = new OnnxModelDownloader(modelName, config, spec)) {
        downloader.downloadModel();
        modelPath = downloader.modelPath();
        tokenizerPath = downloader.tokenizerPath();
      }

      if (!modelPath.toFile().exists()) {
        throw new OnnxEmbeddingException("ONNX model file not found: %s".formatted(modelPath));
      }
      if (!tokenizerPath.toFile().exists()) {
        throw new OnnxEmbeddingException("Tokenizer file not found: %s".formatted(tokenizerPath));
      }

      LOGGER.info("Loading tokenizer from: %s".formatted(tokenizerPath));
      var tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);

      LOGGER.info("Loading ONNX model from: %s".formatted(modelPath));
      var ortEnvironment = OrtEnvironment.getEnvironment();
      var ortSession =
          ortEnvironment.createSession(modelPath.toString(), new OrtSession.SessionOptions());

      LOGGER.info(
          "ONNX model loaded successfully. Embedding dimension: %d"
              .formatted(spec.embeddingDimension()));

      return new OnnxEmbeddingModel(modelName, spec, tokenizer, ortEnvironment, ortSession);
    } catch (OnnxEmbeddingException e) {
      throw e;
    } catch (IOException | OrtException e) {
      LOGGER.log(Level.SEVERE, "Failed to initialize ONNX embedding model", e);
      throw new OnnxEmbeddingException(
          "Failed to initialize ONNX embedding model: %s".formatted(e.getMessage()), e);
    }
  }

  @Override
  public Result<float[]> embed(String text) {
    return embedDocument(text);
  }

  @Override
  public Result<float[]> embedQuery(String query) {
    return embedQuery(query, null);
  }

  @Override
  public Result<float[]> embedQuery(String query, String customQueryPrefix) {
    if (Strings.isBlank(query)) {
      return Result.failure("Query must not be null or empty");
    }
    var prefix = customQueryPrefix != null ? customQueryPrefix : spec.queryPrefix();
    return embedInternal(prefix + query);
  }

  @Override
  public Result<float[]> embedDocument(String document) {
    return embedDocument(document, null);
  }

  @Override
  public Result<float[]> embedDocument(String document, String customDocumentPrefix) {
    if (Strings.isBlank(document)) {
      return Result.failure("Document must not be null or empty");
    }
    var prefix = customDocumentPrefix != null ? customDocumentPrefix : spec.documentPrefix();
    return embedInternal(prefix + document);
  }

  @Override
  public Result<float[][]> embedBatch(String[] texts) {
    if (texts == null || texts.length == 0) {
      return Result.failure("Texts array must not be null or empty");
    }

    var embeddings = new float[texts.length][];
    for (var i = 0; i < texts.length; i++) {
      if (Strings.isBlank(texts[i])) {
        return Result.failure("Text at index %d is null or empty".formatted(i));
      }
      var result = embed(texts[i]);
      if (result instanceof Result.Failure<float[]> failure) {
        return Result.failure(failure.error(), failure.cause());
      }
      embeddings[i] = ((Result.Success<float[]>) result).value();
    }
    return Result.success(embeddings);
  }

  @Override
  public int embeddingDimension() {
    return spec.embeddingDimension();
  }

  @Override
  public String modelName() {
    return modelName;
  }

  @Override
  public void close() {
    if (tokenizer != null) {
      try {
        tokenizer.close();
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Error closing HuggingFace tokenizer", e);
      }
    }
    if (ortSession != null) {
      try {
        ortSession.close();
      } catch (OrtException e) {
        LOGGER.log(Level.WARNING, "Error closing ONNX session", e);
      }
    }
  }

  private Result<float[]> embedInternal(String text) {
    try {
      var encoding = tokenizer.encode(text);
      var tokens = encoding.getIds();
      var attentionMaskArr = encoding.getAttentionMask();
      var tokenTypeArr = encoding.getTypeIds();

      if (tokens.length > spec.sequenceLength()) {
        LOGGER.warning(
            "Text exceeds max sequence length (%d), truncating to %d tokens"
                .formatted(tokens.length, spec.sequenceLength()));
        tokens = truncate(tokens, spec.sequenceLength());
        attentionMaskArr = truncate(attentionMaskArr, spec.sequenceLength());
        tokenTypeArr = truncate(tokenTypeArr, spec.sequenceLength());
      }

      var inputIds = new long[1][tokens.length];
      var attentionMask = new long[1][tokens.length];
      var tokenTypeIds = new long[1][tokens.length];

      for (var i = 0; i < tokens.length; i++) {
        inputIds[0][i] = tokens[i];
        attentionMask[0][i] = attentionMaskArr[i];
        tokenTypeIds[0][i] = tokenTypeArr[i];
      }

      var inputs = new HashMap<String, OnnxTensor>();
      try {
        // Build each tensor in turn. If any creation throws, the previously-built tensors are
        // already in the map and the outer finally will close them — without this scope the
        // cleanup block wraps only the run() call, leaking native tensor memory on every
        // mid-construction failure.
        inputs.put("input_ids", OnnxTensor.createTensor(ortEnvironment, inputIds));
        inputs.put("attention_mask", OnnxTensor.createTensor(ortEnvironment, attentionMask));

        if (spec.modelType() == OnnxModelSpec.ModelType.ENCODER) {
          inputs.put("token_type_ids", OnnxTensor.createTensor(ortEnvironment, tokenTypeIds));
        }

        try (var result = ortSession.run(inputs)) {
          float[] embedding;

          if (spec.modelType() == OnnxModelSpec.ModelType.DECODER && result.size() > 1) {
            var sentenceEmbedding = (float[][]) result.get(1).getValue();
            embedding = sentenceEmbedding[0].clone();
          } else if (result.get(0).getValue() instanceof float[][] pooled) {
            embedding = pooled[0].clone();
          } else {
            var outputTensor = (float[][][]) result.get(0).getValue();
            if (spec.modelType() == OnnxModelSpec.ModelType.DECODER) {
              embedding = lastTokenPooling(outputTensor[0], attentionMask[0]);
            } else {
              embedding = meanPooling(outputTensor[0], attentionMask[0]);
            }
          }

          normalize(embedding);
          return Result.success(embedding);
        }
      } finally {
        for (var tensor : inputs.values()) {
          tensor.close();
        }
      }
    } catch (OrtException e) {
      LOGGER.log(Level.SEVERE, "ONNX Runtime error during embedding generation", e);
      return Result.failure("Failed to generate embedding: %s".formatted(e.getMessage()), e);
    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Failed to generate embedding", e);
      return Result.failure("Failed to generate embedding: %s".formatted(e.getMessage()), e);
    }
  }

  private long[] truncate(long[] arr, int maxLength) {
    var truncated = new long[maxLength];
    System.arraycopy(arr, 0, truncated, 0, maxLength);
    return truncated;
  }

  private float[] lastTokenPooling(float[][] tokenEmbeddings, long[] attentionMask) {
    var lastTokenIndex = 0;
    for (var i = 0; i < attentionMask.length; i++) {
      if (attentionMask[i] == 1L) {
        lastTokenIndex = i;
      }
    }
    return tokenEmbeddings[lastTokenIndex].clone();
  }

  private float[] meanPooling(float[][] tokenEmbeddings, long[] attentionMask) {
    var seqLength = tokenEmbeddings.length;
    var hiddenSize = tokenEmbeddings[0].length;

    var pooled = new float[hiddenSize];
    var maskSum = new float[hiddenSize];

    for (var i = 0; i < seqLength; i++) {
      if (attentionMask[i] == 1L) {
        for (var j = 0; j < hiddenSize; j++) {
          pooled[j] += tokenEmbeddings[i][j];
          maskSum[j] += 1.0f;
        }
      }
    }

    for (var j = 0; j < hiddenSize; j++) {
      if (maskSum[j] > 0) {
        pooled[j] /= maskSum[j];
      }
    }

    return pooled;
  }

  private void normalize(float[] embedding) {
    var norm = 0.0f;
    for (var value : embedding) {
      norm += value * value;
    }
    norm = (float) Math.sqrt(norm);

    if (norm > 0) {
      for (var i = 0; i < embedding.length; i++) {
        embedding[i] /= norm;
      }
    }
  }
}
