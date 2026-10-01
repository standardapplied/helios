/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

/**
 * Helios - Google Gemini Provider Module.
 *
 * <p>Implements the ModelProvider SPI for Google's Gemini API using the Interactions API for
 * configurable provider-side continuation and streaming support.
 */
module com.standardapplied.helios.gemini {
  requires com.standardapplied.helios.core;
  requires java.net.http;
  requires tools.jackson.databind;
  requires com.fasterxml.jackson.annotation;

  exports com.standardapplied.helios.gemini;

  opens com.standardapplied.helios.gemini.api to
      tools.jackson.databind;
  opens com.standardapplied.helios.gemini to
      tools.jackson.databind;

  provides com.standardapplied.helios.core.model.ModelProvider with
      com.standardapplied.helios.gemini.GeminiProvider;
}
