/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Deep, unmodifiable snapshots of a JSON object held as Java values, the shape of a tool call's
 * arguments. Taken once where a value enters, a snapshot shares no container with its source, so no
 * later change to the source, and no caller of the snapshot, can change it.
 *
 * <p>A JSON value is {@code null}, a {@link Boolean}, a {@link String}, a number, a {@link List} of
 * JSON values or a {@link Map} from {@link String} keys to JSON values. A number is a {@link Byte},
 * {@link Short}, {@link Integer}, {@link Long}, {@link BigInteger} or {@link BigDecimal}, or a
 * finite {@link Float} or {@link Double}; it is kept as the same instance, so its precision never
 * changes. A subclass of {@link BigInteger} or {@link BigDecimal} is rejected, since it may be
 * mutable. A {@code null} property is a key present with a {@code null} value; an absent property
 * is an absent key. Containers keep their iteration order.
 *
 * <p>The limits are each at most the default of Jackson's {@code StreamReadConstraints}, the only
 * limits a provider's JSON reader applies: {@value #MAX_DEPTH} nested containers, the object
 * included (Jackson 500); {@value #MAX_VALUES} values, containers included (Jackson unlimited);
 * {@value #MAX_STRING_LENGTH} characters in a string, the size of the largest event a provider
 * stream may carry (Jackson 100,000,000); {@value #MAX_KEY_LENGTH} characters in a key (Jackson
 * 50,000); {@value #MAX_NUMBER_DIGITS} digits in a {@link BigInteger} or {@link BigDecimal} written
 * out in full, as PostgreSQL's {@code jsonb} returns it, so every accepted number reads back from a
 * journal (Jackson 1,000). A finite {@link Double} or {@link Float} written out in full has at most
 * 326 digits, within the limit.
 */
public final class JsonSnapshot {

  /** Containers that may nest, the object itself included. */
  public static final int MAX_DEPTH = 64;

  /** Values a snapshot may hold, containers included. */
  public static final int MAX_VALUES = 100_000;

  /** Characters a string value may hold. */
  public static final int MAX_STRING_LENGTH = 16 * 1024 * 1024;

  /** Characters an object key may hold. */
  public static final int MAX_KEY_LENGTH = 50_000;

  /** Digits a {@link BigInteger} or {@link BigDecimal} may hold, written out in full. */
  public static final int MAX_NUMBER_DIGITS = 1_000;

  private static final Pattern PLAIN_KEY = Pattern.compile("[\\w$-]+");

  private static final BigInteger NUMBER_BOUND = BigInteger.TEN.pow(MAX_NUMBER_DIGITS);

  private static final String NOT_FINITE = "; a JSON number is finite";

  private static final String JSON_VALUES =
      "a JSON value is null, a Boolean, a String, a Byte, Short, Integer, Long, BigInteger or"
          + " BigDecimal, a finite Float or Double, a List, or a Map with String keys";

  private final String name;
  private final Deque<Object> path = new ArrayDeque<>();
  private final Set<Object> open = Collections.newSetFromMap(new IdentityHashMap<>());
  private int values = 1;

  private JsonSnapshot(String name) {
    this.name = name;
  }

  /**
   * A deep, unmodifiable snapshot of the JSON object {@code object}.
   *
   * @param name what {@code object} is, naming it in an error, e.g. {@code "arguments"}
   * @param object the object; non-null
   * @return the snapshot, equal to {@code object}
   * @throws NullPointerException if {@code name} or {@code object} is null
   * @throws IllegalArgumentException if {@code object} holds anything but JSON values, holds
   *     itself, or exceeds a limit; the message names where
   */
  public static SequencedMap<String, Object> ofObject(String name, Map<?, ?> object) {
    var snapshot = new JsonSnapshot(Validate.notBlank("name", name));
    return snapshot.object(Objects.requireNonNull(object, name + " must not be null"), 1);
  }

  /**
   * {@code object} as compact JSON, keys in the map's order, every string escaped by {@link
   * #escape}.
   *
   * @param object the object; non-null, holding only JSON values
   * @return the JSON text
   * @throws NullPointerException if {@code object} is null
   * @throws IllegalArgumentException if {@code object} holds a value that is not a JSON value
   * @throws ClassCastException if {@code object} holds an object with a key that is not a String
   */
  public static String write(Map<String, ?> object) {
    return Writer.json(Objects.requireNonNull(object, "object must not be null"));
  }

  /**
   * {@code text} as the inside of a JSON string, without the quotes. It escapes what RFC 8259
   * requires, {@code "}, {@code \} and every control character, plus the line separators U+2028 and
   * U+2029 and every format character (a bidi override, a zero-width character), so the text shows
   * what it holds: {@code \b}, {@code \f}, {@code \n}, {@code \r} and {@code \t} by name, any other
   * as a lowercase {@code \}{@code uXXXX} for each of its UTF-16 units.
   *
   * @param text the text; non-null
   * @return the escaped text
   * @throws NullPointerException if {@code text} is null
   */
  public static String escape(String text) {
    return text.codePoints().mapToObj(Writer::escape).collect(Collectors.joining());
  }

  private Object value(Object value, int depth) {
    if (++values > MAX_VALUES) {
      throw new IllegalArgumentException(name + " holds more than " + MAX_VALUES + " values");
    }
    return switch (value) {
      case null -> null;
      case String string when string.length() > MAX_STRING_LENGTH ->
          throw rejected(
              "is a string of " + string.length() + " characters; at most " + MAX_STRING_LENGTH);
      case Double number when !Double.isFinite(number) ->
          throw rejected("is " + number + NOT_FINITE);
      case Float number when !Float.isFinite(number) -> throw rejected("is " + number + NOT_FINITE);
      case Boolean _, Byte _, Short _, Integer _, Long _, String _, Double _, Float _ -> value;
      case BigInteger number when number.getClass() == BigInteger.class ->
          bounded(number, number.abs().compareTo(NUMBER_BOUND) < 0);
      case BigDecimal number when number.getClass() == BigDecimal.class ->
          bounded(number, withinDigitLimit(number));
      case Map<?, ?> object -> object(object, depth + 1);
      case List<?> array -> array(array, depth + 1);
      default -> throw rejected("is a " + value.getClass().getName() + "; " + JSON_VALUES);
    };
  }

  private SequencedMap<String, Object> object(Map<?, ?> object, int depth) {
    enter(object, depth);
    var copy = new LinkedHashMap<String, Object>();
    for (var entry : object.entrySet()) {
      var key = key(entry.getKey());
      path.addLast(key);
      copy.put(key, value(entry.getValue(), depth));
      path.removeLast();
    }
    open.remove(object);
    return Collections.unmodifiableSequencedMap(copy);
  }

  private List<Object> array(List<?> array, int depth) {
    enter(array, depth);
    var copy = new ArrayList<>();
    var index = 0;
    for (var element : array) {
      path.addLast(index++);
      copy.add(value(element, depth));
      path.removeLast();
    }
    open.remove(array);
    return Collections.unmodifiableList(copy);
  }

  private String key(Object key) {
    if (!(key instanceof String string)) {
      throw rejected(
          "has a key that is "
              + (key == null ? "null" : "a " + key.getClass().getName())
              + "; a JSON object's keys are Strings");
    }
    if (string.length() > MAX_KEY_LENGTH) {
      throw rejected("has a key of " + string.length() + " characters; at most " + MAX_KEY_LENGTH);
    }
    return string;
  }

  private static boolean withinDigitLimit(BigDecimal number) {
    if (number.unscaledValue().abs().compareTo(NUMBER_BOUND) >= 0) {
      return false;
    }
    long scale = number.scale();
    if (scale > 0) {
      return Math.max(number.precision(), scale + 1) <= MAX_NUMBER_DIGITS;
    }
    return number.signum() == 0 || number.precision() - scale <= MAX_NUMBER_DIGITS;
  }

  private Number bounded(Number number, boolean withinDigitLimit) {
    if (!withinDigitLimit) {
      throw rejected("is a number of more than " + MAX_NUMBER_DIGITS + " digits");
    }
    return number;
  }

  private void enter(Object container, int depth) {
    if (depth > MAX_DEPTH) {
      throw rejected("nests deeper than " + MAX_DEPTH + " containers");
    }
    if (!open.add(container)) {
      throw rejected("contains itself");
    }
  }

  private IllegalArgumentException rejected(String problem) {
    var where = new StringBuilder(name);
    for (var step : path) {
      if (step instanceof String key && !PLAIN_KEY.matcher(key).matches()) {
        where.append("[\"").append(key.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"]");
      } else {
        where.append(step instanceof Integer index ? "[" + index + "]" : "." + step);
      }
    }
    return new IllegalArgumentException(where + " " + problem);
  }

  private static final class Writer {

    private static String json(Object value) {
      return switch (value) {
        case null -> "null";
        case String string -> quote(string);
        case Boolean _, Byte _, Short _, Integer _, Long _, BigInteger _, BigDecimal _ ->
            value.toString();
        case Double number when Double.isFinite(number) -> value.toString();
        case Float number when Float.isFinite(number) -> value.toString();
        case Map<?, ?> object ->
            object.entrySet().stream()
                .map(entry -> quote((String) entry.getKey()) + ":" + json(entry.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
        case List<?> array ->
            array.stream().map(Writer::json).collect(Collectors.joining(",", "[", "]"));
        default ->
            throw new IllegalArgumentException(
                "object holds "
                    + (value instanceof Number ? value : "a " + value.getClass().getName())
                    + "; "
                    + JSON_VALUES);
      };
    }

    private static String escape(int c) {
      return switch (c) {
        case '"' -> "\\\"";
        case '\\' -> "\\\\";
        case '\b' -> "\\b";
        case '\f' -> "\\f";
        case '\n' -> "\\n";
        case '\r' -> "\\r";
        case '\t' -> "\\t";
        default ->
            Character.isISOControl(c)
                    || c == '\u2028'
                    || c == '\u2029'
                    || Character.getType(c) == Character.FORMAT
                ? Character.toString(c)
                    .chars()
                    .mapToObj(unit -> "\\u%04x".formatted(unit))
                    .collect(Collectors.joining())
                : Character.toString(c);
      };
    }

    private static String quote(String string) {
      return '"' + JsonSnapshot.escape(string) + '"';
    }
  }
}
