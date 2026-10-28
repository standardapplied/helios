/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.provider.JsonBinding;
import java.math.BigDecimal;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * The Jackson mappers the session module builds, and what the module reads and writes JSON through:
 * the typed {@code runBlocking}'s structured output and the stored session entries. Jackson 3
 * mappers are immutable and thread-safe.
 */
public final class SessionJson {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /**
   * Stored entries must read back exactly what was written: a decimal reads as a {@link Double}
   * only when that is its exact value, otherwise as a {@link BigDecimal}, and every non-ASCII
   * character is escaped, so a lone UTF-16 surrogate survives the file's UTF-8.
   */
  private static final ObjectMapper ENTRY_MAPPER =
      JsonMapper.builder()
          .addModule(new SimpleModule().addDeserializer(Number.class, new StoredNumber()))
          .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
          .build();

  /** JSON read into maps, and maps converted into types, through Jackson's defaults. */
  public static final JsonBinding BINDING =
      new JsonBinding(MAPPER.readerFor(Map.class)::readValue, MAPPER::convertValue);

  /** Stored entries' JSON read into maps, and maps converted into types, numbers kept exact. */
  public static final JsonBinding ENTRIES =
      new JsonBinding(ENTRY_MAPPER.readerFor(Map.class)::readValue, ENTRY_MAPPER::convertValue);

  private SessionJson() {}

  /**
   * Write {@code value} as the JSON of a stored entry, every non-ASCII character escaped.
   *
   * @param value the value; maps, lists and records nest
   * @return its JSON, all ASCII
   */
  public static String write(Object value) {
    return ENTRY_MAPPER.writeValueAsString(value);
  }

  private static final class StoredNumber extends ValueDeserializer<Number> {

    @Override
    public Number deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
        return parser.getNumberValue();
      }
      // The double first: read after the decimal, Jackson derives it from the decimal, losing -0.0.
      var value = parser.getDoubleValue();
      var exact = parser.getDecimalValue();
      return Double.isFinite(value) && BigDecimal.valueOf(value).compareTo(exact) == 0
          ? value
          : exact;
    }
  }
}
