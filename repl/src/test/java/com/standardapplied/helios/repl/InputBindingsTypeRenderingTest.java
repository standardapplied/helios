/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The Java source {@link InputBindings#renderTypeAsJavaSource} writes for each kind of {@link
 * Type}: every primitive boxed, arrays of each, parameterized types, and the forms JShell cannot
 * name (wildcards, type variables, generic arrays), which become {@code java.lang.Object}.
 */
class InputBindingsTypeRenderingTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "int, java.lang.Integer",
    "long, java.lang.Long",
    "double, java.lang.Double",
    "float, java.lang.Float",
    "boolean, java.lang.Boolean",
    "byte, java.lang.Byte",
    "short, java.lang.Short",
    "char, java.lang.Character",
  })
  void primitivesAreBoxed(String primitive, String boxed) throws Exception {
    var type = primitive(primitive);

    assertEquals(boxed, InputBindings.renderTypeAsJavaSource(type));
    assertEquals(
        boxed + "[][]", InputBindings.renderTypeAsJavaSource(type.arrayType().arrayType()));
  }

  @Test
  void voidIsNotAPrimitiveItCanBox() {
    var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> InputBindings.renderTypeAsJavaSource(void.class));

    assertEquals("Unknown primitive: void", thrown.getMessage());
  }

  @Test
  void typesJShellCannotNameBecomeObject() {
    record Shapes<T>(
        List<?> wildcard,
        List<? extends Number> bounded,
        T variable,
        List<T>[] genericArray,
        Map<String, Function<T, ?>> nested) {}
    var components = Shapes.class.getRecordComponents();

    assertEquals(
        List.of(
            "java.util.List<java.lang.Object>",
            "java.util.List<java.lang.Object>",
            "java.lang.Object",
            "java.lang.Object",
            "java.util.Map<java.lang.String, java.util.function.Function<java.lang.Object,"
                + " java.lang.Object>>"),
        Arrays.stream(components)
            .map(c -> InputBindings.renderTypeAsJavaSource(c.getGenericType()))
            .toList());
  }

  @Test
  void noTypeBecomesObject() {
    assertEquals("java.lang.Object", InputBindings.renderTypeAsJavaSource(null));
  }

  @Test
  void nestedTypeUsesItsCanonicalName() {
    assertEquals("java.util.Map.Entry", InputBindings.renderTypeAsJavaSource(Map.Entry.class));
    assertEquals(
        "java.lang.Thread.State[]", InputBindings.renderTypeAsJavaSource(Thread.State[].class));
  }

  private static Class<?> primitive(String name) {
    return switch (name) {
      case "int" -> int.class;
      case "long" -> long.class;
      case "double" -> double.class;
      case "float" -> float.class;
      case "boolean" -> boolean.class;
      case "byte" -> byte.class;
      case "short" -> short.class;
      default -> char.class;
    };
  }
}
