/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Helios - Anthropic Claude Provider Module.
 *
 * <p>Implements the ModelProvider SPI for Anthropic's Claude API using the Messages API for
 * multi-turn conversations and SSE streaming support.
 */
module com.standardapplied.helios.anthropic {
  requires com.standardapplied.helios.core;
  requires java.net.http;
  requires tools.jackson.databind;
  requires com.fasterxml.jackson.annotation;

  exports com.standardapplied.helios.anthropic;

  opens com.standardapplied.helios.anthropic.api to
      tools.jackson.databind;

  provides com.standardapplied.helios.core.model.ModelProvider with
      com.standardapplied.helios.anthropic.AnthropicProvider;
}
