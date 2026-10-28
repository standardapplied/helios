/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic.api;

import com.standardapplied.helios.core.provider.JsonBinding;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
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

  /**
   * Writes a value's canonical JSON, properties and map entries sorted by name, so equal values
   * write equal bytes whatever order their maps were built in.
   */
  public static final ObjectMapper CANONICAL =
      JsonMapper.builder()
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  /** Structured output read through {@link #LENIENT}. */
  public static final JsonBinding STRUCTURED =
      new JsonBinding(LENIENT.readerFor(Map.class)::readValue, LENIENT::convertValue);

  private AnthropicJson() {}
}
