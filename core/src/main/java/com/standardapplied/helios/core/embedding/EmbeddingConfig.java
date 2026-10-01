/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.core.embedding;

import ai.singlr.core.common.Strings;

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

  /** Sensible defaults for embedding configuration. */
  public static EmbeddingConfig defaults() {
    return new Builder().build();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static class Builder {
    private String workingDirectory = DEFAULT_WORKING_DIR;

    private Builder() {}

    public Builder withWorkingDirectory(String workingDirectory) {
      this.workingDirectory = workingDirectory;
      return this;
    }

    public EmbeddingConfig build() {
      if (Strings.isBlank(workingDirectory)) {
        throw new IllegalArgumentException("Working directory must not be blank");
      }
      return new EmbeddingConfig(workingDirectory);
    }
  }
}
