/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.tool.Tool;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelProviderTest {

  @Test
  void implementModelProvider() {
    var provider = new TestModelProvider();

    assertEquals("test", provider.name());
    assertTrue(provider.supports("test-model-1"));
    assertTrue(provider.supports("test-model-2"));
    assertFalse(provider.supports("unknown-model"));
  }

  @Test
  void createModel() {
    var provider = new TestModelProvider();
    var config = ModelConfig.of("test-key");

    var model = provider.create("test-model-1", config);

    assertEquals("test-model-1", model.id());
    assertEquals("test", model.provider());
  }

  @Test
  void createUnsupportedModelThrows() {
    var provider = new TestModelProvider();
    var config = ModelConfig.of("test-key");

    assertThrows(IllegalArgumentException.class, () -> provider.create("unsupported", config));
  }

  @Test
  void providersReturnsList() {
    var providers = ModelProvider.providers();
    assertNotNull(providers);
  }

  @Test
  void providerNotFoundReturnsEmpty() {
    var result = ModelProvider.provider("nonexistent-provider");
    assertTrue(result.isEmpty());
  }

  @Test
  void resolveWithUnknownModelThrows() {
    var config = ModelConfig.of("test-key");

    var exception =
        assertThrows(
            IllegalArgumentException.class, () -> ModelProvider.resolve("unknown-model", config));
    assertEquals("No provider found for model: unknown-model", exception.getMessage());
  }

  static class TestModelProvider implements ModelProvider {
    private static final List<String> SUPPORTED = List.of("test-model-1", "test-model-2");

    @Override
    public String name() {
      return "test";
    }

    @Override
    public Model create(String modelId, ModelConfig config) {
      if (!supports(modelId)) {
        throw new IllegalArgumentException("Unsupported model: " + modelId);
      }
      return new TestModel(modelId);
    }

    @Override
    public boolean supports(String modelId) {
      return SUPPORTED.contains(modelId);
    }
  }

  static class TestModel implements Model {
    private final String modelId;

    TestModel(String modelId) {
      this.modelId = modelId;
    }

    @Override
    public Response chat(List<Message> messages, List<Tool> tools) {
      return Response.newBuilder()
          .withContent("Test response")
          .withFinishReason(FinishReason.STOP)
          .build();
    }

    @Override
    public String id() {
      return modelId;
    }

    @Override
    public String provider() {
      return "test";
    }
  }
}
