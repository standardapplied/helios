/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The native resources one ONNX embedding model holds: its tokenizer, the options its session was
 * created with, and the session. Opening releases whatever it already opened when a later step
 * fails; closing releases all three, logging any that fails to close.
 *
 * @param tokenizer the model's tokenizer
 * @param options the options the session was created with, closed after the session
 * @param session the ONNX Runtime session that runs the model
 */
record NativeModel(
    HuggingFaceTokenizer tokenizer, OrtSession.SessionOptions options, OrtSession session)
    implements AutoCloseable {

  private static final Logger LOGGER = Logger.getLogger(NativeModel.class.getName());

  static NativeModel open(OnnxModelSpec spec, Path tokenizerPath, Path modelPath)
      throws IOException, OrtException {
    LOGGER.info("Loading tokenizer from: %s".formatted(tokenizerPath));
    var tokenizer = spec.tokenizer(tokenizerPath);
    try {
      var options = new OrtSession.SessionOptions();
      try {
        LOGGER.info("Loading ONNX model from: %s".formatted(modelPath));
        var session = OrtEnvironment.getEnvironment().createSession(modelPath.toString(), options);
        return new NativeModel(tokenizer, options, session);
      } catch (OrtException | RuntimeException | Error e) {
        release(e::addSuppressed, options);
        throw e;
      }
    } catch (OrtException | RuntimeException | Error e) {
      release(e::addSuppressed, tokenizer);
      throw e;
    }
  }

  @Override
  public void close() {
    release(
        e -> LOGGER.log(Level.WARNING, "Error closing an ONNX native resource", e),
        tokenizer,
        session,
        options);
  }

  /** Closes each resource in order, handing every failure to close to {@code onFailure}. */
  static void release(Consumer<Throwable> onFailure, AutoCloseable... resources) {
    for (var resource : resources) {
      try {
        resource.close();
      } catch (Exception | Error e) {
        onFailure.accept(e);
      }
    }
  }
}
