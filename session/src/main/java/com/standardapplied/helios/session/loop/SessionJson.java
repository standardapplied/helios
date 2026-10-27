/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.provider.JsonBinding;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one Jackson mapper the session module builds, and what the module reads and writes JSON
 * through: the typed {@code runBlocking}'s structured output and the stored session entries.
 * Jackson 3 mappers are immutable and thread-safe.
 */
public final class SessionJson {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  /** JSON read into maps, and maps converted into types, through Jackson's defaults. */
  public static final JsonBinding BINDING =
      new JsonBinding(MAPPER.readerFor(Map.class)::readValue, MAPPER::convertValue);

  private SessionJson() {}

  /**
   * Write {@code value} as JSON through Jackson's defaults.
   *
   * @param value the value; maps, lists and records nest
   * @return its JSON
   */
  public static String write(Object value) {
    return MAPPER.writeValueAsString(value);
  }
}
