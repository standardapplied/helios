/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.schema;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Collects the properties of one object schema in declaration order, applying each member's {@link
 * Description} and treating it as required unless it is {@link Nullable}.
 */
final class ObjectProperties {

  private final Map<String, JsonSchema> properties = new LinkedHashMap<>();
  private final List<String> required = new ArrayList<>();

  boolean contains(String name) {
    return properties.containsKey(name);
  }

  void add(String name, JsonSchema schema, AnnotatedElement member) {
    properties.put(name, described(schema, member));
    if (member.getAnnotation(Nullable.class) == null) {
      required.add(name);
    }
  }

  /**
   * The object schema of {@code type}, described by its own {@link Description}.
   *
   * @param freeze how the collected properties are made unmodifiable
   */
  JsonSchema toSchema(AnnotatedElement type, UnaryOperator<Map<String, JsonSchema>> freeze) {
    return described(
        new JsonSchema(
            "object",
            freeze.apply(properties),
            null,
            required.isEmpty() ? null : List.copyOf(required),
            null,
            null,
            null,
            null),
        type);
  }

  private static JsonSchema described(JsonSchema schema, AnnotatedElement element) {
    var description = element.getAnnotation(Description.class);
    return description == null ? schema : schema.withDescription(description.value());
  }
}
