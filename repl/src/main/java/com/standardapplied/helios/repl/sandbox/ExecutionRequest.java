/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import ai.singlr.core.common.Strings;
import java.time.Duration;

/**
 * A request to execute code in a sandbox.
 *
 * @param code the source code to execute
 * @param language the programming language (e.g., "java", "python")
 * @param timeout maximum execution time; {@code null} means use sandbox default
 */
public record ExecutionRequest(String code, String language, Duration timeout) {

  public ExecutionRequest {
    if (Strings.isBlank(code)) {
      throw new IllegalArgumentException("Code must not be null or blank");
    }
    if (Strings.isBlank(language)) {
      throw new IllegalArgumentException("Language must not be null or blank");
    }
    if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
      throw new IllegalArgumentException("Timeout must be positive");
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Convenience factory for Java code with no per-request timeout. */
  public static ExecutionRequest java(String code) {
    return new ExecutionRequest(code, "java", null);
  }

  public static class Builder {
    private String code;
    private String language = "java";
    private Duration timeout;

    private Builder() {}

    public Builder withCode(String code) {
      this.code = code;
      return this;
    }

    public Builder withLanguage(String language) {
      this.language = language;
      return this;
    }

    public Builder withTimeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    public ExecutionRequest build() {
      return new ExecutionRequest(code, language, timeout);
    }
  }
}
