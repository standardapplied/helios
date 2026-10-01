/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.persistence.mapper;

import ai.singlr.core.common.Strings;
import ai.singlr.core.model.ToolCall;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converts between Java objects and JSONB strings for PostgreSQL.
 *
 * <p>Serialized payloads are capped at {@link #MAX_JSONB_BYTES} (256 KB) to prevent runaway memory
 * writes from a misbehaving model or downstream code path. PostgreSQL JSONB itself has a 1 GB hard
 * limit, but at that size every read/write/index is OOM-prone. The 256 KB cap is well below typical
 * agent context windows (multi-MB) yet large enough for any realistic single memory block, archival
 * entry, or trace attribute. Override per-call via {@link #objectToJsonb(Object, int)} when a
 * higher cap is genuinely needed.
 */
public final class JsonbMapper {

  /** Default cap on serialized JSONB byte length. */
  public static final int MAX_JSONB_BYTES = 256 * 1024;

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private JsonbMapper() {}

  /** Serializes a map to a JSON string suitable for JSONB columns. */
  public static String toJsonb(Map<String, String> map) {
    if (map == null || map.isEmpty()) {
      return "{}";
    }
    return MAPPER.writeValueAsString(map);
  }

  private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

  /** Deserializes a JSONB string to a map. */
  public static Map<String, String> fromJsonb(String json) {
    if (Strings.isBlank(json) || "{}".equals(json)) {
      return Map.of();
    }
    try {
      return MAPPER.readValue(json, MAP_TYPE);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to deserialize attributes from JSON: " + json, e);
    }
  }

  /** Serializes any object to a JSON string suitable for JSONB columns, capped at the default. */
  public static String objectToJsonb(Object obj) {
    return objectToJsonb(obj, MAX_JSONB_BYTES);
  }

  /**
   * Serializes any object to a JSON string suitable for JSONB columns, capped at {@code maxBytes}.
   *
   * @param obj the object to serialize; {@code null} returns {@code null}
   * @param maxBytes maximum permitted serialized length in UTF-8 bytes
   * @throws IllegalArgumentException when the serialized form exceeds the cap; the model or caller
   *     can recover by trimming the payload
   */
  public static String objectToJsonb(Object obj, int maxBytes) {
    if (obj == null) {
      return null;
    }
    if (maxBytes <= 0) {
      throw new IllegalArgumentException("maxBytes must be positive");
    }
    var json = MAPPER.writeValueAsString(obj);
    var byteLen = json.getBytes(StandardCharsets.UTF_8).length;
    if (byteLen > maxBytes) {
      throw new IllegalArgumentException(
          "JSONB payload exceeds "
              + maxBytes
              + " bytes (got "
              + byteLen
              + "); trim the value or call objectToJsonb(obj, higher) explicitly");
    }
    return json;
  }

  private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE =
      new TypeReference<>() {};

  /** Deserializes a JSONB string to a {@code Map<String, Object>}. */
  public static Map<String, Object> fromJsonbObject(String json) {
    if (Strings.isBlank(json) || "{}".equals(json)) {
      return Map.of();
    }
    try {
      return MAPPER.readValue(json, OBJECT_MAP_TYPE);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to deserialize object from JSON: " + json, e);
    }
  }

  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

  /** Serializes a list of strings to a JSON array string suitable for JSONB columns. */
  public static String listToJsonb(List<String> list) {
    if (list == null || list.isEmpty()) {
      return "[]";
    }
    return MAPPER.writeValueAsString(list);
  }

  /** Deserializes a JSONB string to a list of strings. */
  public static List<String> listFromJsonb(String json) {
    if (Strings.isBlank(json) || "[]".equals(json)) {
      return List.of();
    }
    try {
      return MAPPER.readValue(json, STRING_LIST_TYPE);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to deserialize labels from JSON: " + json, e);
    }
  }

  private static final TypeReference<List<ToolCall>> TOOL_CALLS_TYPE = new TypeReference<>() {};

  /** Deserializes a JSONB string to a list of tool calls. */
  public static List<ToolCall> toolCallsFromJsonb(String json) {
    if (Strings.isBlank(json) || "[]".equals(json)) {
      return List.of();
    }
    try {
      return MAPPER.readValue(json, TOOL_CALLS_TYPE);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to deserialize tool calls from JSON: " + json, e);
    }
  }
}
