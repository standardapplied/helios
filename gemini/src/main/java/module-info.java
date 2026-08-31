/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Singular Agentic Framework - Google Gemini Provider Module.
 *
 * <p>Implements the ModelProvider SPI for Google's Gemini API using the Interactions API for
 * configurable provider-side continuation and streaming support.
 */
module ai.singlr.gemini {
  requires ai.singlr.core;
  requires java.net.http;
  requires tools.jackson.databind;
  requires com.fasterxml.jackson.annotation;

  exports ai.singlr.gemini;

  opens ai.singlr.gemini.api to
      tools.jackson.databind;
  opens ai.singlr.gemini to
      tools.jackson.databind;

  provides ai.singlr.core.model.ModelProvider with
      ai.singlr.gemini.GeminiProvider;
}
