/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.Redactor;
import com.standardapplied.helios.core.model.ToolCall;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tool call's arguments as the session's events show them: every registered secret redacted, in
 * keys and in string values at any depth. Other values and the order are unchanged; where redacting
 * makes two keys equal, the later entry's value wins.
 */
final class RedactedArguments {

  private RedactedArguments() {}

  static ToolCall of(ToolCall call, Redactor redactor) {
    return new ToolCall(call.id(), call.name(), of(call.arguments(), redactor));
  }

  static Map<String, Object> of(Map<?, ?> arguments, Redactor redactor) {
    var redacted = new LinkedHashMap<String, Object>();
    arguments.forEach(
        (key, value) -> redacted.put(redactor.redact((String) key).text(), value(value, redactor)));
    return redacted;
  }

  private static Object value(Object value, Redactor redactor) {
    return switch (value) {
      case String text -> redactor.redact(text).text();
      case Map<?, ?> object -> of(object, redactor);
      case List<?> array -> array.stream().map(element -> value(element, redactor)).toList();
      case null, default -> value;
    };
  }
}
