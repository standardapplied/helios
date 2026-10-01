/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InputBindingsTest {

  public record Stats(List<Integer> numbers, String operation) {}

  public record Empty() {}

  public static class Bean {
    public String name;
  }

  // Deliberately package-private. The whole point of the design: bindings DON'T need this
  // to be accessible from JShell. The user's input record class never appears in JShell.
  record PackagePrivateRecord(int x, String y) {}

  // A record with a complex (user-defined) component type. Should fall back to Object binding.
  public record HasNestedRecord(int count, Stats nested) {}

  public record HasPrimitive(int count, boolean active) {}

  @Test
  void boundFieldNamesForRecord() {
    assertEquals(List.of("numbers", "operation"), InputBindings.boundFieldNames(Stats.class));
  }

  @Test
  void boundFieldNamesForNonRecordIsEmpty() {
    assertEquals(List.of(), InputBindings.boundFieldNames(Bean.class));
    assertEquals(List.of(), InputBindings.boundFieldNames(String.class));
    assertEquals(List.of(), InputBindings.boundFieldNames(null));
  }

  @Test
  void boundFieldNamesForEmptyRecordIsEmpty() {
    assertEquals(List.of(), InputBindings.boundFieldNames(Empty.class));
  }

  @Test
  void snippetCallsHostBridgeGetInputNotJackson() {
    var snippet = InputBindings.snippet(Stats.class);
    assertTrue(
        snippet.contains("var __input = ai.singlr.repl.sandbox.HostBridge.getInput();"),
        "snippet must route through HostBridge.getInput, got:\n" + snippet);
    assertFalse(
        snippet.contains("tools.jackson"),
        "snippet must NOT reference Jackson (host-side concern only), got:\n" + snippet);
    assertFalse(
        snippet.contains("Base64"),
        "snippet must NOT base64-encode JSON anymore — host serves the map directly, got:\n"
            + snippet);
    assertFalse(
        snippet.contains(Stats.class.getCanonicalName() + ".class"),
        "snippet must NOT reference the user's record class");
  }

  @Test
  void snippetCastsSimpleFieldsToTheirDeclaredType() {
    var snippet = InputBindings.snippet(Stats.class);
    assertTrue(
        snippet.contains(
            "var numbers = (java.util.List<java.lang.Integer>) __input.get(\"numbers\");"),
        "List<Integer> must render fully qualified for JShell, got:\n" + snippet);
    assertTrue(
        snippet.contains("var operation = (java.lang.String) __input.get(\"operation\");"),
        "String must render as java.lang.String, got:\n" + snippet);
  }

  @Test
  void snippetBoxesPrimitiveTypesInCasts() {
    var snippet = InputBindings.snippet(HasPrimitive.class);
    assertTrue(
        snippet.contains("var count = (java.lang.Integer) __input.get(\"count\");"),
        "primitive int must box to java.lang.Integer in cast");
    assertTrue(
        snippet.contains("var active = (java.lang.Boolean) __input.get(\"active\");"),
        "primitive boolean must box to java.lang.Boolean in cast");
  }

  @Test
  void snippetSkipsCastForComplexTypes() {
    var snippet = InputBindings.snippet(HasNestedRecord.class);
    // count is int → simple → cast
    assertTrue(snippet.contains("var count = (java.lang.Integer) __input.get(\"count\");"));
    // nested is Stats → user-defined → bound as Object (no cast)
    assertTrue(
        snippet.contains("var nested = __input.get(\"nested\");"),
        "complex (user-defined) types must bind as Object — no cast — got:\n" + snippet);
    assertFalse(
        snippet.contains("(" + Stats.class.getCanonicalName() + ")"),
        "must not generate a cast that would require user class accessibility");
  }

  @Test
  void snippetWorksForPackagePrivateRecord() {
    // Key invariant: the user's record class doesn't need to be accessible from JShell.
    // The snippet must compile and not reference PackagePrivateRecord at all.
    var snippet = InputBindings.snippet(PackagePrivateRecord.class);
    assertFalse(
        snippet.contains("PackagePrivateRecord"),
        "snippet must NOT reference the user's input class name — it would be inaccessible");
    assertTrue(snippet.contains("var x = (java.lang.Integer) __input.get(\"x\");"));
    assertTrue(snippet.contains("var y = (java.lang.String) __input.get(\"y\");"));
  }

  @Test
  void snippetIsNullForNonRecord() {
    assertNull(InputBindings.snippet(Bean.class));
    assertNull(InputBindings.snippet(String.class));
    assertNull(InputBindings.snippet(null));
  }

  @Test
  void snippetIsNullForEmptyRecord() {
    assertNull(InputBindings.snippet(Empty.class));
  }

  @Test
  void componentsForRecord() {
    var components = InputBindings.components(Stats.class);
    assertEquals(2, components.length);
    assertEquals("numbers", components[0].getName());
    assertEquals("operation", components[1].getName());
  }

  @Test
  void componentsForNonRecordIsEmpty() {
    assertEquals(0, InputBindings.components(Bean.class).length);
    assertEquals(0, InputBindings.components(null).length);
  }

  @Test
  void isSimpleTypeForJavaPackageClasses() {
    assertTrue(InputBindings.isSimpleType(String.class));
    assertTrue(InputBindings.isSimpleType(Integer.class));
    assertTrue(InputBindings.isSimpleType(Boolean.class));
    assertTrue(InputBindings.isSimpleType(java.math.BigDecimal.class));
    assertTrue(InputBindings.isSimpleType(java.time.Instant.class));
  }

  @Test
  void isSimpleTypeForPrimitives() {
    assertTrue(InputBindings.isSimpleType(int.class));
    assertTrue(InputBindings.isSimpleType(boolean.class));
    assertTrue(InputBindings.isSimpleType(double.class));
  }

  @Test
  void isSimpleTypeForUserClassesIsFalse() {
    assertFalse(InputBindings.isSimpleType(Stats.class));
    assertFalse(InputBindings.isSimpleType(Bean.class));
  }

  @Test
  void isSimpleTypeForListOfSimpleIsTrue() throws Exception {
    var component = HasNestedRecord.class.getRecordComponents()[0]; // count: int
    assertTrue(InputBindings.isSimpleType(component.getGenericType()));
    var statsComponent = Stats.class.getRecordComponents()[0]; // numbers: List<Integer>
    assertTrue(InputBindings.isSimpleType(statsComponent.getGenericType()));
  }

  @Test
  void isSimpleTypeForListOfUserClassIsTrueHybridSpec05() throws Exception {
    // Hybrid binding (Spec 05): List<UserType> is bindable because the raw type (List) is in
    // java.*. The type argument is erased to Object at render time, so the JShell cast works:
    // the model gets .size() / .get() without writing the cast itself.
    record HasListOfRecord(List<Stats> items) {}
    var listOfStats = HasListOfRecord.class.getRecordComponents()[0].getGenericType();
    assertTrue(InputBindings.isSimpleType(listOfStats));
  }

  @Test
  void isSimpleTypeForUserClassAtTopLevelStaysFalse() {
    // Top-level user types still fall back to raw Object — model casts on first use.
    assertFalse(InputBindings.isSimpleType(Stats.class));
  }

  @Test
  void isSimpleTypeForUserContainerStaysFalse() throws Exception {
    // A user-defined parameterized type (raw type not in java.*) is not bindable, even if its
    // type args are simple. The container class is invisible to JShell.
    record MyContainer<T>(T value) {}
    record Wrap(MyContainer<String> wrapped) {}
    var t = Wrap.class.getRecordComponents()[0].getGenericType();
    assertFalse(InputBindings.isSimpleType(t));
  }

  @Test
  void renderTypeAsJavaSourceForCommonShapes() throws Exception {
    assertEquals("java.lang.String", InputBindings.renderTypeAsJavaSource(String.class));
    assertEquals("java.lang.Integer", InputBindings.renderTypeAsJavaSource(int.class));
    assertEquals("java.lang.Boolean", InputBindings.renderTypeAsJavaSource(boolean.class));

    var listOfInt = (ParameterizedType) Stats.class.getRecordComponents()[0].getGenericType();
    assertEquals(
        "java.util.List<java.lang.Integer>", InputBindings.renderTypeAsJavaSource(listOfInt));
  }

  @Test
  void renderTypeAsJavaSourceForArrays() {
    assertEquals("java.lang.Integer[]", InputBindings.renderTypeAsJavaSource(Integer[].class));
    assertEquals(
        "int[]".replace("int", "java.lang.Integer"),
        InputBindings.renderTypeAsJavaSource(int[].class));
  }

  @Test
  void renderTypeAsJavaSourceForNestedGenerics() throws Exception {
    record Wrap(Map<String, List<Integer>> mapping) {}
    var t = Wrap.class.getRecordComponents()[0].getGenericType();
    assertEquals(
        "java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>",
        InputBindings.renderTypeAsJavaSource(t));
  }

  @Test
  void renderTypeAsJavaSourceForSetOfStrings() throws Exception {
    record Wrap(Set<String> tags) {}
    var t = Wrap.class.getRecordComponents()[0].getGenericType();
    assertEquals("java.util.Set<java.lang.String>", InputBindings.renderTypeAsJavaSource(t));
  }

  // ---------------------------------------------------------------------------
  // Spec 05 — hybrid binding: java.* containers over non-java.* element types
  // erase their type arguments to Object so the container stays useful.
  // ---------------------------------------------------------------------------

  @Test
  void renderTypeForListOfUserClassErasesElement() throws Exception {
    record Wrap(List<Stats> items) {}
    var t = Wrap.class.getRecordComponents()[0].getGenericType();
    assertEquals("java.util.List<java.lang.Object>", InputBindings.renderTypeAsJavaSource(t));
  }

  @Test
  void renderTypeForMapWithUserValueErasesValue() throws Exception {
    record Wrap(Map<String, Stats> byName) {}
    var t = Wrap.class.getRecordComponents()[0].getGenericType();
    assertEquals(
        "java.util.Map<java.lang.String, java.lang.Object>",
        InputBindings.renderTypeAsJavaSource(t));
  }

  @Test
  void renderTypeForSetOfUserClassErasesElement() throws Exception {
    record Wrap(Set<Stats> things) {}
    var t = Wrap.class.getRecordComponents()[0].getGenericType();
    assertEquals("java.util.Set<java.lang.Object>", InputBindings.renderTypeAsJavaSource(t));
  }

  @Test
  void renderTypeForNestedContainerOverUserClassErasesDeepArg() throws Exception {
    record Wrap(Map<String, List<Stats>> groups) {}
    var t = Wrap.class.getRecordComponents()[0].getGenericType();
    assertEquals(
        "java.util.Map<java.lang.String, java.util.List<java.lang.Object>>",
        InputBindings.renderTypeAsJavaSource(t));
  }

  @Test
  void renderTypeForArrayOfUserClassErasesComponent() {
    assertEquals("java.lang.Object[]", InputBindings.renderTypeAsJavaSource(Stats[].class));
  }

  @Test
  void renderTypeForTopLevelUserClassIsObject() {
    assertEquals("java.lang.Object", InputBindings.renderTypeAsJavaSource(Stats.class));
  }

  @Test
  void snippetCastsListOfUserClassToListOfObject() throws Exception {
    // Integration check: the SDTM case. A user-typed collection on the input record now binds
    // with a typed cast that the model can call .size() / .get(0) on without a manual cast.
    record SdtmInput(List<Stats> sourceFiles, String runId) {}
    var snippet = InputBindings.snippet(SdtmInput.class);
    assertTrue(
        snippet.contains(
            "var sourceFiles = (java.util.List<java.lang.Object>) __input.get(\"sourceFiles\");"),
        "List<UserType> must bind with a List<Object> cast for the hybrid path. Got:\n" + snippet);
    assertTrue(
        snippet.contains("var runId = (java.lang.String) __input.get(\"runId\");"),
        "simple-typed siblings should still bind with their declared type, got:\n" + snippet);
  }

  @Test
  void snippetCastsMapWithUserValuesToMapOfObject() throws Exception {
    record HasUserMap(Map<String, Stats> byId) {}
    var snippet = InputBindings.snippet(HasUserMap.class);
    assertTrue(
        snippet.contains(
            "var byId = (java.util.Map<java.lang.String, java.lang.Object>)"
                + " __input.get(\"byId\");"),
        "Map<String,UserType> must bind with a Map<String,Object> cast. Got:\n" + snippet);
  }

  @Test
  void snippetTopLevelUserClassStaysObject() throws Exception {
    // Confirm we did NOT change the top-level user-class case — still binds without a cast.
    record HasUserField(Stats nested, int count) {}
    var snippet = InputBindings.snippet(HasUserField.class);
    assertTrue(
        snippet.contains("var nested = __input.get(\"nested\");"),
        "top-level user types still bind as raw Object — no cast. Got:\n" + snippet);
    assertTrue(
        snippet.contains("var count = (java.lang.Integer) __input.get(\"count\");"),
        "simple sibling field unaffected");
  }
}
