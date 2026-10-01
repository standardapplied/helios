/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.common.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmbeddingProviderTest {

  @Test
  void implementEmbeddingProvider() {
    var provider = new TestEmbeddingProvider();

    assertEquals("test", provider.name());
    assertTrue(provider.supports("test-model/embed-v1"));
    assertFalse(provider.supports("unknown-model"));
  }

  @Test
  void createModel() {
    var provider = new TestEmbeddingProvider();
    var config = EmbeddingConfig.defaults();

    var model = provider.create("test-model/embed-v1", config);

    assertEquals("test-model/embed-v1", model.modelName());
    assertEquals(768, model.embeddingDimension());
  }

  @Test
  void createUnsupportedModelThrows() {
    var provider = new TestEmbeddingProvider();
    var config = EmbeddingConfig.defaults();

    assertThrows(
        IllegalArgumentException.class, () -> provider.create("unsupported-model", config));
  }

  @Test
  void providersReturnsList() {
    var providers = EmbeddingProvider.providers();
    assertNotNull(providers);
  }

  @Test
  void providerNotFoundReturnsEmpty() {
    var result = EmbeddingProvider.provider("nonexistent-provider");
    assertTrue(result.isEmpty());
  }

  @Test
  void resolveWithUnknownModelThrows() {
    var config = EmbeddingConfig.defaults();

    var exception =
        assertThrows(
            IllegalArgumentException.class,
            () -> EmbeddingProvider.resolve("unknown-model", config));
    assertEquals("No provider found for model: unknown-model", exception.getMessage());
  }

  @Test
  void embeddingModelDefaultEmbedQuery() {
    try (var model = new TestEmbeddingModel("test-model/embed-v1")) {
      var result = model.embedQuery("search query");

      assertTrue(result.isSuccess());
      assertEquals(768, ((Result.Success<float[]>) result).value().length);
    }
  }

  @Test
  void embeddingModelDefaultEmbedDocument() {
    try (var model = new TestEmbeddingModel("test-model/embed-v1")) {
      var result = model.embedDocument("document text");

      assertTrue(result.isSuccess());
      assertEquals(768, ((Result.Success<float[]>) result).value().length);
    }
  }

  @Test
  void embeddingConfigDefaults() {
    var config = EmbeddingConfig.defaults();

    assertNotNull(config.workingDirectory());
    assertFalse(config.workingDirectory().isBlank());
  }

  @Test
  void embeddingConfigCustomWorkingDirectory() {
    var config = EmbeddingConfig.newBuilder().withWorkingDirectory("/tmp/models").build();

    assertEquals("/tmp/models", config.workingDirectory());
  }

  @Test
  void embeddingConfigNullWorkingDirectoryThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> EmbeddingConfig.newBuilder().withWorkingDirectory(null).build());
  }

  @Test
  void embeddingConfigBlankWorkingDirectoryThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> EmbeddingConfig.newBuilder().withWorkingDirectory("   ").build());
  }

  static class TestEmbeddingProvider implements EmbeddingProvider {
    private static final List<String> SUPPORTED = List.of("test-model/embed-v1");

    @Override
    public String name() {
      return "test";
    }

    @Override
    public EmbeddingModel create(String modelName, EmbeddingConfig config) {
      if (!supports(modelName)) {
        throw new IllegalArgumentException("Unsupported model: " + modelName);
      }
      return new TestEmbeddingModel(modelName);
    }

    @Override
    public boolean supports(String modelName) {
      return SUPPORTED.contains(modelName);
    }
  }

  static class TestEmbeddingModel implements EmbeddingModel {
    private static final int DIMENSION = 768;
    private final String modelName;

    TestEmbeddingModel(String modelName) {
      this.modelName = modelName;
    }

    @Override
    public Result<float[]> embed(String text) {
      return Result.success(new float[DIMENSION]);
    }

    @Override
    public Result<float[][]> embedBatch(String[] texts) {
      var results = new float[texts.length][DIMENSION];
      return Result.success(results);
    }

    @Override
    public int embeddingDimension() {
      return DIMENSION;
    }

    @Override
    public String modelName() {
      return modelName;
    }

    @Override
    public void close() {}
  }
}
