/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

  private static final Set<String> EXCLUDED_METHODS =
      Set.of(
          "hashCode",
          "toString",
          "getClass",
          "equals",
          "notify",
          "notifyAll",
          "wait",
          "clone",
          "finalize");

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
      var components = recordClass.getRecordComponents();
      var properties = new LinkedHashMap<String, JsonSchema>();
      var required = new ArrayList<String>();

      for (var component : components) {
        var name = component.getName();
        var type = component.getGenericType();
        var schema = generateForType(type, visited);

        var descAnnotation = component.getAnnotation(Description.class);
        if (descAnnotation != null) {
          schema = schema.withDescription(descAnnotation.value());
        }

        properties.put(name, schema);

        if (component.getAnnotation(Nullable.class) == null) {
          required.add(name);
        }
      }

      var typeDescription =
          recordClass.isAnnotationPresent(Description.class)
              ? recordClass.getAnnotation(Description.class).value()
              : null;

      return new JsonSchema(
          "object",
          Map.copyOf(properties),
          null,
          required.isEmpty() ? null : List.copyOf(required),
          null,
          typeDescription,
          null,
          null);
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
      var accessors = discoverAccessors(clazz);
      var properties = new LinkedHashMap<String, JsonSchema>();
      var required = new ArrayList<String>();

      for (var method : accessors) {
        var propertyName = derivePropertyName(method);
        if (properties.containsKey(propertyName)) continue;

        var schema = generateForType(method.getGenericReturnType(), visited);

        var descAnnotation = method.getAnnotation(Description.class);
        if (descAnnotation != null) {
          schema = schema.withDescription(descAnnotation.value());
        }

        properties.put(propertyName, schema);

        if (method.getAnnotation(Nullable.class) == null) {
          required.add(propertyName);
        }
      }

      var typeDescription =
          clazz.isAnnotationPresent(Description.class)
              ? clazz.getAnnotation(Description.class).value()
              : null;

      return new JsonSchema(
          "object",
          Collections.unmodifiableMap(properties),
          null,
          required.isEmpty() ? null : List.copyOf(required),
          null,
          typeDescription,
          null,
          null);
    } finally {
      visited.remove(clazz);
    }
  }

  private static List<Method> discoverAccessors(Class<?> clazz) {
    var accessors = new ArrayList<Method>();
    for (var method : clazz.getMethods()) {
      if (method.getParameterCount() != 0) continue;
      if (method.getReturnType() == void.class) continue;
      if (Modifier.isStatic(method.getModifiers())) continue;
      if (method.isSynthetic()) continue;
      if (EXCLUDED_METHODS.contains(method.getName())) continue;
      accessors.add(method);
    }
    accessors.sort(Comparator.comparing(SchemaGenerator::derivePropertyName));
    return accessors;
  }

  private static String derivePropertyName(Method method) {
    var name = method.getName();

    if (name.startsWith("get") && name.length() > 3) {
      return Character.toLowerCase(name.charAt(3)) + name.substring(4);
    }

    if (name.startsWith("is")
        && name.length() > 2
        && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
      return Character.toLowerCase(name.charAt(2)) + name.substring(3);
    }

    return name;
  }

  private static JsonSchema generateForType(Type type, Set<Class<?>> visited) {
    if (type instanceof Class<?> clazz) {
      return generateForClass(clazz, visited);
    }

    if (type instanceof ParameterizedType paramType) {
      var rawType = (Class<?>) paramType.getRawType();

      if (List.class.isAssignableFrom(rawType)) {
        var itemType = paramType.getActualTypeArguments()[0];
        return JsonSchema.array(generateForType(itemType, visited));
      }

      if (Map.class.isAssignableFrom(rawType)) {
        var keyType = paramType.getActualTypeArguments()[0];
        if (keyType != String.class) {
          throw new IllegalArgumentException(
              "Map key type must be String for JSON Schema generation, got: "
                  + keyType.getTypeName());
        }
        var valueType = paramType.getActualTypeArguments()[1];
        var valueSchema = generateForType(valueType, visited);
        return JsonSchema.map(valueSchema);
      }
    }

    if (type instanceof WildcardType) {
      throw new IllegalArgumentException(
          "Wildcard types (?) are not supported for schema generation."
              + " Use a concrete type instead, e.g., Map<String, String> instead of Map<String, ?>.");
    }

    throw new IllegalArgumentException(
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
