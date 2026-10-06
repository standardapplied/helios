/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.provider;

import com.standardapplied.helios.core.schema.StructuredContentParser;
import java.util.Map;

/**
 * A provider's JSON library as the {@link StructuredContentParser.JsonAdapter} structured output
 * reads through, given as its two operations, so core stays free of the library.
 *
 * @param reader parses a JSON object into a map, e.g. {@code json -> mapper.readValue(json,
 *     Map.class)}
 * @param converter converts a map into a type, e.g. {@code mapper::convertValue}
 */
public record JsonBinding(Reader reader, Converter converter)
    implements StructuredContentParser.JsonAdapter {

  /** Parses a JSON object into a map. */
  @FunctionalInterface
  public interface Reader {

    /**
     * {@code json} as a map.
     *
     * @throws Exception when {@code json} is not a JSON object
     */
    Map<String, Object> read(String json) throws Exception;
  }

  /** Converts a map into a type. */
  @FunctionalInterface
  public interface Converter {

    /** {@code map} as an instance of {@code type}. */
    Object convert(Map<String, Object> map, Class<?> type);
  }

  @Override
  public Map<String, Object> toMap(String json) throws Exception {
    return reader.read(json);
  }

  @Override
  public <T> T fromMap(Map<String, Object> map, Class<T> type) {
    return type.cast(converter.convert(map, type));
  }
}
