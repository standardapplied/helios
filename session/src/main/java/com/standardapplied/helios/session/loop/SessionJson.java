/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.standardapplied.helios.core.provider.JsonBinding;
import java.math.BigDecimal;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
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
   * Reads a string of any length, since a stored entry must read back whatever the session wrote:
   * Jackson's default limit refuses an attachment over about 75 MB in base64.
   */
  private static final JsonFactory ANY_STRING_LENGTH =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build())
          .build();

  /**
   * Stored entries read back as the JSON value written, numbers exact: a decimal reads as a {@link
   * Double} only when that is its exact value, otherwise as a {@link BigDecimal}.
   */
  private static final ObjectMapper ENTRY_MAPPER =
      JsonMapper.builder(ANY_STRING_LENGTH)
          .addModule(new SimpleModule().addDeserializer(Number.class, new StoredNumber()))
          .build();

  /** JSON read into maps, and maps converted into types, through Jackson's defaults. */
  public static final JsonBinding BINDING =
      new JsonBinding(MAPPER.readerFor(Map.class)::readValue, MAPPER::convertValue);

  /** Stored entries' JSON read into maps, and maps converted into types, numbers kept exact. */
  public static final JsonBinding ENTRIES =
      new JsonBinding(ENTRY_MAPPER.readerFor(Map.class)::readValue, ENTRY_MAPPER::convertValue);

  private SessionJson() {}

  /**
   * Write {@code value} as the JSON of a stored entry. It is written through Jackson's UTF-8
   * writer, which escapes a lone UTF-16 surrogate, so the JSON survives encoding as UTF-8.
   *
   * @param value the value; maps, lists and records nest
   * @return its JSON
   */
  public static String write(Object value) {
    return new String(ENTRY_MAPPER.writeValueAsBytes(value), UTF_8);
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
