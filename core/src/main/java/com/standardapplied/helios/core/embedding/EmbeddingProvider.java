/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.embedding;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Service Provider Interface (SPI) for embedding providers.
 *
 * <p>Implementations are discovered via JPMS {@link ServiceLoader}. Provider modules declare
 * themselves with {@code provides ai.singlr.core.embedding.EmbeddingProvider with ...} in their
 * {@code module-info.java}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Auto-discover provider and create model
 * EmbeddingModel model = EmbeddingProvider.resolve("nomic-ai/nomic-embed-text-v1.5", config);
 *
 * // Explicit provider lookup
 * EmbeddingProvider onnx = EmbeddingProvider.provider("onnx").orElseThrow();
 * EmbeddingModel model = onnx.create("nomic-ai/nomic-embed-text-v1.5", config);
 *
 * // List all available providers
 * List<EmbeddingProvider> all = EmbeddingProvider.providers();
 * }</pre>
 */
public interface EmbeddingProvider {

  /**
   * The unique name of this provider.
   *
   * @return provider name (e.g., "onnx")
   */
  String name();

  /**
   * Create an embedding model instance with the given model name and configuration.
   *
   * @param modelName the specific model to use (e.g., "nomic-ai/nomic-embed-text-v1.5")
   * @param config embedding configuration (working directory, dimensions, etc.)
   * @return a configured EmbeddingModel instance
   * @throws IllegalArgumentException if the model name is not supported by this provider
   */
  EmbeddingModel create(String modelName, EmbeddingConfig config);

  /**
   * Check if this provider supports the given model name.
   *
   * @param modelName the model identifier to check
   * @return true if this provider can create instances for the given model
   */
  boolean supports(String modelName);

  /**
   * List all available embedding providers discovered via ServiceLoader.
   *
   * @return immutable list of all registered providers
   */
  static List<EmbeddingProvider> providers() {
    return ServiceLoader.load(EmbeddingProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .toList();
  }

  /**
   * Find a provider by name.
   *
   * @param name the provider name (e.g., "onnx")
   * @return the provider, or empty if not found
   */
  static Optional<EmbeddingProvider> provider(String name) {
    return ServiceLoader.load(EmbeddingProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .filter(p -> p.name().equals(name))
        .findFirst();
  }

  /**
   * Auto-discover which provider supports the given model name and create the model.
   *
   * @param modelName the model identifier (e.g., "nomic-ai/nomic-embed-text-v1.5")
   * @param config embedding configuration
   * @return the created embedding model
   * @throws IllegalArgumentException if no provider supports the model name
   */
  static EmbeddingModel resolve(String modelName, EmbeddingConfig config) {
    return ServiceLoader.load(EmbeddingProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .filter(p -> p.supports(modelName))
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("No provider found for model: " + modelName))
        .create(modelName, config);
  }
}
