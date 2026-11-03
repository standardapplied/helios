/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OrtException;
import com.standardapplied.helios.core.embedding.EmbeddingConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OnnxEmbeddingProvider} as the embedding SPI sees it, and how it fails to create a model
 * from a cache a finished marker vouches for but whose files are missing or unreadable.
 */
class OnnxEmbeddingProviderTest {

  private static final String NOMIC = OnnxModelId.NOMIC_EMBED_V1_5.id();

  private final OnnxEmbeddingProvider provider = new OnnxEmbeddingProvider();

  @TempDir Path cache;

  @Test
  void isNamedOnnx() {
    assertEquals("onnx", provider.name());
  }

  @Test
  void supportsOnlyCataloguedModels() {
    assertTrue(provider.supports(NOMIC));
    assertFalse(provider.supports("acme/tiny"));
  }

  @Test
  void refusesAnUncataloguedModel() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> provider.create("acme/tiny", EmbeddingConfig.defaults()));

    assertEquals("Unsupported ONNX model: acme/tiny", error.getMessage());
  }

  @Test
  void aCacheWithoutTheModelFileFailsToCreate() throws IOException {
    var modelDir = finishedModelDirectory();

    var error = assertThrows(OnnxEmbeddingException.class, this::create);

    assertEquals(
        "ONNX model file not found: " + modelDir.resolve("model.onnx"), error.getMessage());
  }

  @Test
  void aCacheWithoutTheTokenizerFailsToCreate() throws IOException {
    var modelDir = finishedModelDirectory();
    Files.writeString(modelDir.resolve("model.onnx"), "weights");

    var error = assertThrows(OnnxEmbeddingException.class, this::create);

    assertEquals(
        "Tokenizer file not found: " + modelDir.resolve("tokenizer.json"), error.getMessage());
  }

  @Test
  void aModelFileTheRuntimeCannotLoadFailsToCreateWithItsCause() throws IOException {
    var modelDir = finishedModelDirectory();
    Files.writeString(modelDir.resolve("model.onnx"), "not an onnx model");
    Files.copy(cachedNomicTokenizer(), modelDir.resolve("tokenizer.json"));

    var error = assertThrows(OnnxEmbeddingException.class, this::create);

    assertInstanceOf(OrtException.class, error.getCause());
    assertEquals(
        "Failed to initialize ONNX embedding model: " + error.getCause().getMessage(),
        error.getMessage());
  }

  private void create() {
    provider.create(
        NOMIC, EmbeddingConfig.newBuilder().withWorkingDirectory(cache.toString()).build());
  }

  private Path finishedModelDirectory() throws IOException {
    var modelDir = Files.createDirectories(cache.resolve(NOMIC));
    Files.createFile(modelDir.resolve(".finished"));
    return modelDir;
  }

  private static Path cachedNomicTokenizer() throws IOException {
    var spec = OnnxModelSpec.lookup(NOMIC).orElseThrow();
    try (var downloader = new OnnxModelDownloader(NOMIC, EmbeddingConfig.defaults(), spec)) {
      return downloader.downloadModel().tokenizer();
    }
  }
}
