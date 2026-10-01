/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.gemini.api;

import ai.singlr.core.common.Strings;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deserializes the function-call {@code arguments} field, which the Gemini Interactions API ships
 * in two shapes depending on the surface:
 *
 * <ul>
 *   <li>As a JSON object (the documented shape): {@code {"location":"SF"}} → deserialized directly
 *       into a {@code Map<String,Object>}.
 *   <li>As a JSON-encoded string (observed on streaming {@code interaction.step_*} events): {@code
 *       "{\"location\":\"SF\"}"} → re-parsed as JSON, also yielding a {@code Map<String,Object>}.
 * </ul>
 *
 * <p>Both shapes normalize to the same internal {@code Map} representation so downstream code (e.g.
 * {@code ContentItem.arguments()} consumers) never sees the wire-format quirk. Empty or blank
 * strings degrade to an empty map.
 */
public final class ArgumentsDeserializer extends ValueDeserializer<Map<String, Object>> {

  // Re-parser for string-shaped arguments. Stateless and thread-safe.
  private static final JsonMapper STRING_PARSER = JsonMapper.builder().build();

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, Object> deserialize(JsonParser p, DeserializationContext ctxt) {
    var node = ctxt.readTree(p);
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isObject()) {
      return STRING_PARSER.convertValue(node, Map.class);
    }
    if (node.isString()) {
      var raw = node.asString();
      if (Strings.isBlank(raw)) {
        return Map.of();
      }
      return STRING_PARSER.readValue(raw, Map.class);
    }
    return null;
  }
}
