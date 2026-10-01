/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Service Provider Interface (SPI) for model providers.
 *
 * <p>Implementations are discovered via JPMS {@link ServiceLoader}. Provider modules declare
 * themselves with {@code provides ai.singlr.core.model.ModelProvider with ...} in their {@code
 * module-info.java}.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * // Auto-discover provider and create model
 * Model model = ModelProvider.resolve("gemini-3-flash", config);
 *
 * // Explicit provider lookup
 * ModelProvider gemini = ModelProvider.provider("gemini").orElseThrow();
 * Model model = gemini.create("gemini-3-flash", config);
 *
 * // List all available providers
 * List<ModelProvider> all = ModelProvider.providers();
 * }</pre>
 */
public interface ModelProvider {

  /**
   * The unique name of this provider.
   *
   * @return provider name (e.g., "gemini", "anthropic", "openai")
   */
  String name();

  /**
   * Create a model instance with the given model ID and configuration.
   *
   * @param modelId the specific model to use (e.g., "gemini-3-flash", "claude-3-opus")
   * @param config provider configuration (API key, timeouts, etc.)
   * @return a configured Model instance
   * @throws IllegalArgumentException if the model ID is not supported by this provider
   */
  Model create(String modelId, ModelConfig config);

  /**
   * Check if this provider supports the given model ID.
   *
   * @param modelId the model identifier to check
   * @return true if this provider can create instances for the given model ID
   */
  boolean supports(String modelId);

  /**
   * List all available model providers discovered via ServiceLoader.
   *
   * @return immutable list of all registered providers
   */
  static List<ModelProvider> providers() {
    return ServiceLoader.load(ModelProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .toList();
  }

  /**
   * Find a provider by name.
   *
   * @param name the provider name (e.g., "gemini", "anthropic")
   * @return the provider, or empty if not found
   */
  static Optional<ModelProvider> provider(String name) {
    return ServiceLoader.load(ModelProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .filter(p -> p.name().equals(name))
        .findFirst();
  }

  /**
   * Auto-discover which provider supports the given model ID and create the model.
   *
   * @param modelId the model identifier (e.g., "gemini-3-flash", "claude-3-opus")
   * @param config provider configuration
   * @return the created model
   * @throws IllegalArgumentException if no provider supports the model ID
   */
  static Model resolve(String modelId, ModelConfig config) {
    return ServiceLoader.load(ModelProvider.class).stream()
        .map(ServiceLoader.Provider::get)
        .filter(p -> p.supports(modelId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("No provider found for model: " + modelId))
        .create(modelId, config);
  }
}
