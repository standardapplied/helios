/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.gemini.api;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deserializes the streaming {@code arguments} carrier on {@link ContentItem}, which the Gemini
 * Interactions API ships in two incompatible shapes on the {@code step.delta} surface:
 *
 * <ul>
 *   <li>As a JSON-encoded <em>string fragment</em> on the {@code arguments_delta} delta variant of a
 *       streamed {@code function_call}: {@code "{\"city\":\""}. These are <em>partial</em> and not
 *       individually valid JSON — they are accumulated into a buffer and parsed only once the step
 *       completes, so they must survive deserialization untouched.
 *   <li>As a complete JSON <em>object</em> on a {@code google_search_call} delta: {@code
 *       {"queries":["..."]}}. Grounded turns pack the search-call step into the {@code step.delta}
 *       union, landing the object in this slot.
 * </ul>
 *
 * <p>Unlike {@link ArgumentsDeserializer} (which normalizes to a {@code Map}), this deserializer
 * yields a raw {@code String} so the partial-fragment accumulation path is preserved: a string token
 * is returned verbatim, and an object or array token is re-serialized to its compact JSON string
 * form. {@code null} stays {@code null}. Parsing each partial fragment as a {@code Map} would throw
 * and break streamed tool calls, which is why the two cases cannot share a deserializer.
 */
public final class RawArgumentsDeserializer extends ValueDeserializer<String> {

  // Re-serializer for object/array-shaped arguments. Stateless and thread-safe.
  private static final JsonMapper WRITER = JsonMapper.builder().build();

  @Override
  public String deserialize(JsonParser p, DeserializationContext ctxt) {
    var node = ctxt.readTree(p);
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isString()) {
      return node.asString();
    }
    return WRITER.writeValueAsString(node);
  }
}
