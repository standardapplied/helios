/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.events;

import java.util.Map;
import java.util.Optional;

/** Writes one single-line JSON object field by field, in call order, through {@link JsonText}. */
final class JsonObjectWriter {

  private final StringBuilder sb = new StringBuilder(256).append('{');

  JsonObjectWriter string(String key, String value) {
    return raw(key, JsonText.quote(value));
  }

  JsonObjectWriter number(String key, long value) {
    return raw(key, String.valueOf(value));
  }

  JsonObjectWriter number(String key, double value) {
    return raw(key, JsonText.number(value));
  }

  JsonObjectWriter bool(String key, boolean value) {
    return raw(key, String.valueOf(value));
  }

  JsonObjectWriter optionalString(String key, Optional<String> value) {
    return raw(key, value.map(JsonText::quote).orElse("null"));
  }

  JsonObjectWriter map(String key, Map<String, ?> map) {
    return raw(key, JsonText.object(map));
  }

  JsonObjectWriter doubleArray(String key, double[] values) {
    return raw(key, JsonText.array(values));
  }

  /** Writes a value that is already JSON text, such as {@code null} or a nested object. */
  JsonObjectWriter raw(String key, String json) {
    sb.append('"').append(key).append("\":").append(json).append(',');
    return this;
  }

  /** The object written so far, closed. */
  @Override
  public String toString() {
    var last = sb.length() - 1;
    return (sb.charAt(last) == ',' ? sb.substring(0, last) : sb.toString()) + '}';
  }
}
