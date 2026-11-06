/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.embedding;

import com.standardapplied.helios.core.common.Strings;

/**
 * Provider-agnostic configuration for embedding models. Contains only user-level operational
 * concerns — model-specific properties (dimension, sequence length, prefixes) are handled
 * internally by providers.
 *
 * @param workingDirectory local directory for model cache
 */
public record EmbeddingConfig(String workingDirectory) {

  private static final String DEFAULT_WORKING_DIR =
      System.getProperty("user.home") + "/.helios/models/";

  /**
   * Returns the default configuration, which caches models under {@code .helios/models/} in the
   * {@code user.home} directory.
   *
   * @return a configuration with every setting at its default
   */
  public static EmbeddingConfig defaults() {
    return new Builder().build();
  }

  /**
   * Returns a builder starting from the settings {@link #defaults()} uses.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builds an {@link EmbeddingConfig}; not thread-safe, so confine one builder to a thread. */
  public static class Builder {
    private String workingDirectory = DEFAULT_WORKING_DIR;

    private Builder() {}

    /**
     * Sets the local directory a provider downloads and caches model files in. Defaults to {@code
     * .helios/models/} in the {@code user.home} directory. Validated by {@link #build()}.
     *
     * @param workingDirectory the cache directory path; must not be {@code null} or blank
     * @return this builder
     */
    public Builder withWorkingDirectory(String workingDirectory) {
      this.workingDirectory = workingDirectory;
      return this;
    }

    /**
     * Builds the configuration.
     *
     * @return a new configuration holding this builder's settings
     * @throws IllegalArgumentException if the working directory is {@code null} or blank
     */
    public EmbeddingConfig build() {
      if (Strings.isBlank(workingDirectory)) {
        throw new IllegalArgumentException("Working directory must not be blank");
      }
      return new EmbeddingConfig(workingDirectory);
    }
  }
}
