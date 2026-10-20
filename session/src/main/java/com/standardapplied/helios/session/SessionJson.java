/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.provider.JsonBinding;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one Jackson mapper the session module builds, and the structured-output binding the typed
 * {@code runBlocking} parses through. Jackson 3 mappers are immutable and thread-safe.
 */
final class SessionJson {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** Structured output read through Jackson's defaults. */
  static final JsonBinding STRUCTURED =
      new JsonBinding(MAPPER.readerFor(Map.class)::readValue, MAPPER::convertValue);

  private SessionJson() {}
}
