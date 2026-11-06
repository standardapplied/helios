/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.core.common.Strings;
import java.time.Duration;

/**
 * A request to execute code in a sandbox.
 *
 * @param code the source code to execute; must not be {@code null} or blank
 * @param language the programming language (e.g., "java", "python"); must not be {@code null} or
 *     blank. {@link JvmSandbox} forwards it to its subprocess, which evaluates every request as
 *     JShell Java
 * @param timeout maximum execution time, strictly positive; {@code null} means the sandbox's
 *     default ({@link JvmSandboxConfig#executionTimeout()} for a {@link JvmSandbox})
 */
public record ExecutionRequest(String code, String language, Duration timeout) {

  /**
   * Validates the components.
   *
   * @throws IllegalArgumentException if {@code code} or {@code language} is {@code null} or blank,
   *     or if {@code timeout} is zero or negative
   */
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

  /**
   * Starts a builder with no code, language {@code "java"} and no per-request timeout.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Convenience factory for Java code with no per-request timeout.
   *
   * @param code the Java source to execute; must not be {@code null} or blank
   * @return a request with language {@code "java"} and the sandbox's default timeout
   * @throws IllegalArgumentException if {@code code} is {@code null} or blank
   */
  public static ExecutionRequest java(String code) {
    return new ExecutionRequest(code, "java", null);
  }

  /**
   * Mutable, single-thread builder for {@link ExecutionRequest}; each setter replaces its value.
   */
  public static class Builder {
    private String code;
    private String language = "java";
    private Duration timeout;

    private Builder() {}

    /**
     * Sets the source code to execute; required, unset by default.
     *
     * @param code the source; {@code null} or blank is rejected by {@link #build()}
     * @return this builder
     */
    public Builder withCode(String code) {
      this.code = code;
      return this;
    }

    /**
     * Sets the programming language; {@code "java"} by default.
     *
     * @param language the language name; {@code null} or blank is rejected by {@link #build()}
     * @return this builder
     */
    public Builder withLanguage(String language) {
      this.language = language;
      return this;
    }

    /**
     * Sets the maximum execution time; unset by default, which means the sandbox's default.
     *
     * @param timeout a strictly positive duration, or {@code null} for the sandbox's default; zero
     *     or negative is rejected by {@link #build()}
     * @return this builder
     */
    public Builder withTimeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    /**
     * Builds the request.
     *
     * @return a new request
     * @throws IllegalArgumentException if the code or language is {@code null} or blank, or the
     *     timeout is zero or negative
     */
    public ExecutionRequest build() {
      return new ExecutionRequest(code, language, timeout);
    }
  }
}
