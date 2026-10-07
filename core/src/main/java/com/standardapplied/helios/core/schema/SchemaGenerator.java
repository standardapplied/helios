/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.schema;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates JSON Schema from Java records, classes, and interfaces. Supports primitive types,
 * strings, lists, enums, nested records/classes, and annotations ({@link Description}, {@link
 * Nullable}).
 *
 * <p>Records use {@code getRecordComponents()} for fast, declaration-order property discovery.
 * Classes and interfaces fall back to public accessor method introspection, recognizing {@code
 * getX()}, {@code isX()} (boolean), and {@code x()} (record-style) accessors.
 */
public final class SchemaGenerator {

  private static final Set<Class<?>> INTEGER_TYPES =
      Set.of(
          int.class,
          Integer.class,
          long.class,
          Long.class,
          short.class,
          Short.class,
          BigInteger.class);

  private static final Set<Class<?>> NUMBER_TYPES =
      Set.of(double.class, Double.class, float.class, Float.class, BigDecimal.class);

  private static final Set<Class<?>> BOOLEAN_TYPES = Set.of(boolean.class, Boolean.class);

  private SchemaGenerator() {}

  /**
   * Generates a JSON Schema from a Java record, class, or interface.
   *
   * @param clazz the class to generate schema from
   * @return the generated JSON Schema
   * @throws IllegalArgumentException if the class is a primitive, array, enum, or leaf type
   */
  public static JsonSchema generate(Class<?> clazz) {
    if (clazz.isRecord()) {
      return generateForRecord(clazz, new HashSet<>());
    }
    if (clazz.isPrimitive()
        || clazz.isArray()
        || clazz.isEnum()
        || clazz == String.class
        || clazz == Object.class
        || INTEGER_TYPES.contains(clazz)
        || NUMBER_TYPES.contains(clazz)
        || BOOLEAN_TYPES.contains(clazz)) {
      throw new IllegalArgumentException(
          "Schema generation requires a record or class, got: " + clazz.getName());
    }
    return generateForBean(clazz, new HashSet<>());
  }

  private static JsonSchema generateForRecord(Class<?> recordClass, Set<Class<?>> visited) {
    if (!visited.add(recordClass)) {
      throw new IllegalArgumentException(
          "Circular record reference detected: "
              + recordClass.getName()
              + ". Records cannot reference themselves directly or transitively."
              + " Consider breaking the cycle with a non-record wrapper type.");
    }
    try {
      var properties = new ObjectProperties();
      for (var component : recordClass.getRecordComponents()) {
        properties.add(
            component.getName(), generateForType(component.getGenericType(), visited), component);
      }
      return properties.toSchema(recordClass);
    } finally {
      visited.remove(recordClass);
    }
  }

  private static JsonSchema generateForBean(Class<?> clazz, Set<Class<?>> visited) {
    if (!visited.add(clazz)) {
      throw new IllegalArgumentException(
          "Circular reference detected: "
              + clazz.getName()
              + ". Types cannot reference themselves directly or transitively.");
    }
    try {
      var properties = new ObjectProperties();
      for (var method : BeanAccessors.discover(clazz)) {
        var name = BeanAccessors.propertyName(method);
        if (!properties.contains(name)) {
          properties.add(name, generateForType(method.getGenericReturnType(), visited), method);
        }
      }
      return properties.toSchema(clazz);
    } finally {
      visited.remove(clazz);
    }
  }

  private static JsonSchema generateForType(Type type, Set<Class<?>> visited) {
    return switch (type) {
      case Class<?> clazz -> generateForClass(clazz, visited);
      case ParameterizedType paramType -> generateForParameterized(paramType, visited);
      case WildcardType w ->
          throw new IllegalArgumentException(
              "Wildcard types (?) are not supported for schema generation."
                  + " Use a concrete type instead, e.g., Map<String, String> instead of Map<String,"
                  + " ?>.");
      default -> throw unsupported(type);
    };
  }

  private static JsonSchema generateForParameterized(
      ParameterizedType paramType, Set<Class<?>> visited) {
    var rawType = (Class<?>) paramType.getRawType();
    var arguments = paramType.getActualTypeArguments();
    if (List.class.isAssignableFrom(rawType)) {
      return JsonSchema.array(generateForType(arguments[0], visited));
    }
    if (Map.class.isAssignableFrom(rawType)) {
      if (arguments[0] != String.class) {
        throw new IllegalArgumentException(
            "Map key type must be String for JSON Schema generation, got: "
                + arguments[0].getTypeName());
      }
      return JsonSchema.map(generateForType(arguments[1], visited));
    }
    throw unsupported(paramType);
  }

  private static IllegalArgumentException unsupported(Type type) {
    return new IllegalArgumentException(
        "Unsupported generic type for schema generation: " + type.getTypeName());
  }

  private static JsonSchema generateForClass(Class<?> clazz, Set<Class<?>> visited) {
    if (clazz == String.class) {
      return JsonSchema.string();
    }

    if (INTEGER_TYPES.contains(clazz)) {
      return JsonSchema.integer();
    }

    if (NUMBER_TYPES.contains(clazz)) {
      return JsonSchema.number();
    }

    if (BOOLEAN_TYPES.contains(clazz)) {
      return JsonSchema.bool();
    }

    if (clazz.isEnum()) {
      var constants = clazz.getEnumConstants();
      var values = new ArrayList<String>(constants.length);
      for (var constant : constants) {
        values.add(((Enum<?>) constant).name());
      }
      return JsonSchema.enumOf(values);
    }

    if (clazz.isRecord()) {
      return generateForRecord(clazz, visited);
    }

    if (clazz.isArray()) {
      return JsonSchema.array(generateForClass(clazz.getComponentType(), visited));
    }

    return generateForBean(clazz, visited);
  }
}
