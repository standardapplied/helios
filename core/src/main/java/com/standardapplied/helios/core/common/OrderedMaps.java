/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * Unmodifiable map copies that keep their source's iteration order. {@code Map.copyOf} iterates in
 * an order randomized per JVM; what Helios sends to a model or reports to a user is copied here
 * instead, so it reads the same in every process.
 */
public final class OrderedMaps {

  private OrderedMaps() {}

  /**
   * An unmodifiable copy of {@code map} in its iteration order, rejecting nulls as {@code
   * Map.copyOf} does.
   *
   * @param map the map to copy; non-null, with no null key or value
   * @param <K> the key type
   * @param <V> the value type
   * @return the ordered copy
   * @throws NullPointerException if {@code map}, a key or a value is null
   */
  public static <K, V> SequencedMap<K, V> copyOf(Map<? extends K, ? extends V> map) {
    Objects.requireNonNull(map, "map must not be null");
    var copy = new LinkedHashMap<K, V>();
    map.forEach(
        (key, value) ->
            copy.put(
                Objects.requireNonNull(key, "key must not be null"),
                Objects.requireNonNull(value, () -> "value of key " + key + " must not be null")));
    return Collections.unmodifiableSequencedMap(copy);
  }
}
