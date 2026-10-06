/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic.api;

import com.standardapplied.helios.core.schema.StructuredContentParser;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every JSON mapper the Anthropic module uses, each configuration built once; the only place the
 * module builds one. Jackson 3 mappers are immutable and thread-safe.
 */
public final class AnthropicJson {

  /** Reads and writes the wire format, ignoring response fields this client does not model. */
  public static final ObjectMapper LENIENT =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  /** Jackson's defaults, for decoding the metadata this module records on a message. */
  public static final ObjectMapper DEFAULT = JsonMapper.builder().build();

  /** Structured output read through {@link #LENIENT}. */
  public static final StructuredContentParser.JsonAdapter STRUCTURED =
      new StructuredContentParser.JsonAdapter() {
        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> toMap(String json) {
          return LENIENT.readValue(json, Map.class);
        }

        @Override
        public <T> T fromMap(Map<String, Object> map, Class<T> type) {
          return LENIENT.convertValue(map, type);
        }
      };

  private AnthropicJson() {}
}
