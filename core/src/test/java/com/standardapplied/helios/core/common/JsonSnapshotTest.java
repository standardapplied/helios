/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JsonSnapshotTest {

  @Test
  void aSnapshotEqualsItsSourceAndKeepsEveryJsonValueAsTheSameInstance() {
    var values =
        Arrays.<Object>asList(
            null,
            true,
            "text",
            (byte) 1,
            (short) 2,
            3,
            4L,
            new BigInteger("123456789012345678901234567890"),
            1.5f,
            0.1,
            new BigDecimal("0.10000000000000000000000000000000000001"));
    var source = new LinkedHashMap<String, Object>();
    for (var i = 0; i < values.size(); i++) {
      source.put("v" + i, values.get(i));
    }

    var snapshot = JsonSnapshot.ofObject("arguments", source);

    assertEquals(source, snapshot);
    assertEquals(List.copyOf(source.keySet()), List.copyOf(snapshot.keySet()));
    for (var key : source.keySet()) {
      assertSame(source.get(key), snapshot.get(key), key);
    }
  }

  @Test
  void nullsArePresentKeysInObjectsAndElementsInArrays() {
    var nested = new HashMap<String, Object>();
    nested.put("lang", null);
    var source = new LinkedHashMap<String, Object>();
    source.put("unit", null);
    source.put("days", Arrays.asList(1, null));
    source.put("options", nested);

    var snapshot = JsonSnapshot.ofObject("arguments", source);

    assertEquals(source, snapshot);
    assertTrue(snapshot.containsKey("unit"));
    assertTrue(asMap(snapshot.get("options")).containsKey("lang"));
  }

  @Test
  void aSnapshotSharesNoContainerWithItsSource() {
    var inner = new ArrayList<Object>(List.of("a"));
    var object = new HashMap<String, Object>(Map.of("inner", inner));
    var source = new HashMap<String, Object>(Map.of("list", new ArrayList<>(List.of(object))));

    var snapshot = JsonSnapshot.ofObject("arguments", source);
    inner.add("b");
    object.put("added", 1);
    source.put("added", 2);

    assertEquals(Map.of("list", List.of(Map.of("inner", List.of("a")))), snapshot);
    assertNotSame(source.get("list"), snapshot.get("list"));
  }

  @Test
  void everyContainerOfASnapshotIsUnmodifiable() {
    var snapshot =
        JsonSnapshot.ofObject(
            "arguments", Map.of("list", new ArrayList<>(List.of(new HashMap<>(Map.of("k", "v"))))));
    var list = asList(snapshot.get("list"));
    var object = asMap(list.getFirst());

    assertThrows(UnsupportedOperationException.class, () -> snapshot.put("k", "v"));
    assertThrows(UnsupportedOperationException.class, () -> list.add("x"));
    assertThrows(UnsupportedOperationException.class, () -> list.set(0, "x"));
    assertThrows(UnsupportedOperationException.class, () -> object.put("k", "w"));
    assertThrows(UnsupportedOperationException.class, () -> object.entrySet().clear());
  }

  @Test
  void anEmptyObjectIsAccepted() {
    assertEquals(Map.of(), JsonSnapshot.ofObject("arguments", Map.of()));
  }

  @Test
  void aSharedContainerThatIsNotACycleIsCopiedWhereverItAppears() {
    var list = List.of(1);
    var object = Map.of("k", 1);
    var source = Map.of("a", List.of(list, list), "o", List.of(object, object));

    assertEquals(source, JsonSnapshot.ofObject("arguments", source));
  }

  static Stream<Arguments> aValueThatIsNotJsonIsRejectedNamingWhere() {
    var cyclicMap = new HashMap<String, Object>();
    cyclicMap.put("self", cyclicMap);
    var cyclicList = new ArrayList<Object>();
    cyclicList.add(cyclicList);
    var nonStringKey = new HashMap<Object, Object>();
    nonStringKey.put(7, "seven");
    var nullKey = new HashMap<String, Object>();
    nullKey.put(null, "none");
    var afterASibling = new LinkedHashMap<String, Object>();
    afterASibling.put("a", 1);
    afterASibling.put("b", Double.NaN);
    return Stream.of(
        Arguments.of(cyclicMap, "arguments.self contains itself"),
        Arguments.of(Map.of("list", cyclicList), "arguments.list[0] contains itself"),
        Arguments.of(Map.of("n", Double.NaN), "arguments.n is NaN; a JSON number is finite"),
        Arguments.of(afterASibling, "arguments.b is NaN; a JSON number is finite"),
        Arguments.of(
            Map.of("n", Double.POSITIVE_INFINITY),
            "arguments.n is Infinity; a JSON number is finite"),
        Arguments.of(
            Map.of("n", Float.NEGATIVE_INFINITY),
            "arguments.n is -Infinity; a JSON number is finite"),
        Arguments.of(
            Map.of("a", List.of(1, new AtomicLong(1))),
            "arguments.a[1] is a java.util.concurrent.atomic.AtomicLong; " + jsonValues()),
        Arguments.of(
            Map.of("n", new BigIntegerSubclass()),
            "arguments.n is a " + BigIntegerSubclass.class.getName() + "; " + jsonValues()),
        Arguments.of(
            Map.of("n", new BigDecimalSubclass()),
            "arguments.n is a " + BigDecimalSubclass.class.getName() + "; " + jsonValues()),
        Arguments.of(
            Map.of("a", new Object[] {1}), "arguments.a is a [Ljava.lang.Object;; " + jsonValues()),
        Arguments.of(
            Map.of("o", nonStringKey),
            "arguments.o has a key that is a java.lang.Integer; a JSON object's keys are Strings"),
        Arguments.of(nullKey, "arguments has a key that is null; a JSON object's keys are Strings"),
        Arguments.of(
            Map.of("a.b", Map.of("[0]", List.of(1, new Object()))),
            "arguments[\"a.b\"][\"[0]\"][1] is a java.lang.Object; " + jsonValues()),
        Arguments.of(
            Map.of("say \"\\hi\"", new Object()),
            "arguments[\"say \\\"\\\\hi\\\"\"] is a java.lang.Object; " + jsonValues()));
  }

  @ParameterizedTest
  @MethodSource
  void aValueThatIsNotJsonIsRejectedNamingWhere(Map<?, ?> source, String message) {
    assertEquals(message, rejection(source));
  }

  @Test
  void nestingIsAcceptedToTheDepthLimitAndRejectedBeyondIt() {
    assertEquals(
        nested(JsonSnapshot.MAX_DEPTH),
        JsonSnapshot.ofObject("arguments", nested(JsonSnapshot.MAX_DEPTH)));

    var tooDeep = nested(JsonSnapshot.MAX_DEPTH + 1);

    assertEquals(
        "arguments" + ".a".repeat(JsonSnapshot.MAX_DEPTH) + " nests deeper than 64 containers",
        rejection(tooDeep));
  }

  @Test
  void listsCountTowardTheDepthLimit() {
    Object list = List.of();
    for (var level = 2; level < JsonSnapshot.MAX_DEPTH; level++) {
      list = List.of(list);
    }
    var atLimit = Map.of("a", list);
    var tooDeep = Map.of("a", List.of(list));

    assertEquals(atLimit, JsonSnapshot.ofObject("arguments", atLimit));
    assertEquals(
        "arguments.a"
            + "[0]".repeat(JsonSnapshot.MAX_DEPTH - 1)
            + " nests deeper than 64 containers",
        rejection(tooDeep));
  }

  @Test
  void valuesAreAcceptedToTheCountLimitAndRejectedBeyondIt() {
    var atLimit = Map.of("a", Collections.nCopies(JsonSnapshot.MAX_VALUES - 2, 0));
    var overLimit = Map.of("a", Collections.nCopies(JsonSnapshot.MAX_VALUES - 1, 0));

    assertEquals(atLimit, JsonSnapshot.ofObject("arguments", atLimit));
    assertEquals("arguments holds more than 100000 values", rejection(overLimit));
  }

  @Test
  void aListLargerThanTheCountLimitIsRejectedWithoutAllocatingItsSize() {
    var huge = Map.of("a", Collections.nCopies(Integer.MAX_VALUE, 0));

    assertEquals("arguments holds more than 100000 values", rejection(huge));
  }

  @Test
  void stringsAreAcceptedToTheLengthLimitAndRejectedBeyondIt() {
    var atLimit = "x".repeat(JsonSnapshot.MAX_STRING_LENGTH);
    var overLimit = Map.of("s", atLimit + "x");

    assertSame(atLimit, JsonSnapshot.ofObject("arguments", Map.of("s", atLimit)).get("s"));
    assertEquals(
        "arguments.s is a string of 16777217 characters; at most 16777216", rejection(overLimit));
  }

  @Test
  void keysAreAcceptedToTheLengthLimitAndRejectedBeyondIt() {
    var atLimit = "k".repeat(JsonSnapshot.MAX_KEY_LENGTH);
    var overLimit = Map.of("o", Map.of(atLimit + "k", 1));

    assertEquals(Map.of(atLimit, 1), JsonSnapshot.ofObject("arguments", Map.of(atLimit, 1)));
    assertEquals("arguments.o has a key of 50001 characters; at most 50000", rejection(overLimit));
  }

  @Test
  void bigNumbersAreAcceptedToTheDigitLimitOfTheirPlainNotationAndRejectedBeyondIt() {
    var digits = "9".repeat(JsonSnapshot.MAX_NUMBER_DIGITS);
    var accepted = new LinkedHashMap<String, Object>();
    accepted.put("integer", new BigInteger("-" + digits));
    accepted.put("fraction", new BigDecimal("-0." + digits.substring(1)));
    accepted.put("exponent", new BigDecimal("9." + digits.substring(4) + "E+999"));
    accepted.put("large", new BigDecimal("1E+999"));
    accepted.put("small", new BigDecimal("-1E-999"));
    accepted.put("zero", new BigDecimal(BigInteger.ZERO, -999_999_999));
    accepted.put("zeros", new BigDecimal(BigInteger.ZERO, 999));
    accepted.put("max", Double.MAX_VALUE);
    accepted.put("min", -Double.MIN_VALUE);

    assertEquals(accepted, JsonSnapshot.ofObject("arguments", accepted));
    for (var tooLong :
        List.<Number>of(
            new BigInteger("1" + digits),
            new BigInteger("-1" + digits),
            BigInteger.TEN.pow(JsonSnapshot.MAX_NUMBER_DIGITS),
            new BigDecimal(digits + "1E-3"),
            new BigDecimal("0." + digits + "E-5"),
            new BigDecimal(digits + "E+5"),
            new BigDecimal("1E+1000"),
            new BigDecimal("-1E-1000"),
            new BigDecimal(BigInteger.ZERO, 1000),
            new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE),
            new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE))) {
      assertEquals(
          "arguments.n is a number of more than 1000 digits", rejection(Map.of("n", tooLong)));
    }
  }

  @Test
  void aDecimalWithAHugeCoefficientOrExponentIsRejectedWithinASmallHeap(@TempDir Path directory)
      throws Exception {
    var log = directory.resolve("probe.log");
    var builder =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m",
                "-cp",
                String.join(
                    File.pathSeparator,
                    System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path")),
                    System.getProperty("jdk.module.path", "")),
                HugeDecimalProbe.class.getName())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile());
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.environment().remove("JDK_JAVA_OPTIONS");
    builder.environment().remove("_JAVA_OPTIONS");
    var process = builder.start();
    try {
      Await.termination("the huge-decimal probe", process);
      assertEquals(0, process.exitValue(), Files.readString(log));
      assertEquals(
          List.of(
              "arguments.n is a number of more than 1000 digits",
              "arguments.n is a number of more than 1000 digits"),
          Files.readAllLines(log));
    } finally {
      process.destroyForcibly();
      Await.termination("the destroyed huge-decimal probe", process);
    }
  }

  @Test
  void aMissingObjectOrABlankNameIsRejected() {
    assertEquals(
        "arguments must not be null",
        assertThrows(NullPointerException.class, () -> JsonSnapshot.ofObject("arguments", null))
            .getMessage());
    assertThrows(IllegalArgumentException.class, () -> JsonSnapshot.ofObject(" ", Map.of()));
  }

  public static final class HugeDecimalProbe {
    public static void main(String[] args) {
      var coefficient = new BigDecimal(BigInteger.ONE.shiftLeft(128_000_000).negate());
      var exponent = new BigDecimal(BigInteger.ONE, -999_999_999);
      for (var number : List.of(coefficient, exponent)) {
        System.out.println(rejection(Map.of("n", number)));
      }
    }
  }

  private static final class BigIntegerSubclass extends BigInteger {
    BigIntegerSubclass() {
      super("1");
    }
  }

  private static final class BigDecimalSubclass extends BigDecimal {
    BigDecimalSubclass() {
      super("1");
    }
  }

  private static String rejection(Map<?, ?> source) {
    return assertThrows(
            IllegalArgumentException.class, () -> JsonSnapshot.ofObject("arguments", source))
        .getMessage();
  }

  private static Map<String, Object> nested(int depth) {
    Map<String, Object> object = Map.of();
    for (var level = 1; level < depth; level++) {
      object = Map.of("a", object);
    }
    return object;
  }

  @Test
  void writesCompactJsonInTheMapsOrderEscapingWhatRfc8259Requires() {
    var object = new LinkedHashMap<String, Object>();
    object.put("s", "q\" b\\ n\n c\u0001 é\u2028");
    object.put(
        "n", Arrays.asList(null, true, 1, 2L, 1.5, 2.5f, BigInteger.TEN, new BigDecimal("1.0")));
    object.put("o", Map.of("k", List.of()));

    assertEquals(
        "{\"s\":\"q\\\" b\\\\ n\\n c\\u0001 é\u2028\","
            + "\"n\":[null,true,1,2,1.5,2.5,10,1.0],\"o\":{\"k\":[]}}",
        JsonSnapshot.write(object));
  }

  @Test
  void writeRefusesAValueOutsideTheJsonValues() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> JsonSnapshot.write(Map.of("a", List.of(Double.NaN))));
    assertEquals("object holds NaN; " + jsonValues(), ex.getMessage());
  }

  private static String jsonValues() {
    return "a JSON value is null, a Boolean, a String, a Byte, Short, Integer, Long, BigInteger or"
        + " BigDecimal, a finite Float or Double, a List, or a Map with String keys";
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object value) {
    return (List<Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return (Map<String, Object>) value;
  }
}
