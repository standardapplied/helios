/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.JsonSnapshot;
import com.standardapplied.helios.core.common.Redactor;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.ToolCall;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tool call's arguments as the session's events show them: every registered secret redacted, in
 * keys and in string values at any depth. Other values, the order and any unpaired surrogate are
 * unchanged; where redacting makes two keys equal, the later entry's value wins. A key or string
 * that redaction lengthens past {@link JsonSnapshot}'s limit is cut to it and ends in {@code …}, so
 * a valid call always has a valid event copy.
 */
final class RedactedArguments {

  private static final String CUT = "…";

  private RedactedArguments() {}

  static ToolCall of(ToolCall call, SecretRegistry secrets) {
    return new ToolCall(call.id(), call.name(), of(call.arguments(), secrets));
  }

  static Map<String, Object> of(Map<?, ?> arguments, SecretRegistry secrets) {
    return object(arguments, secrets.redactor());
  }

  private static Map<String, Object> object(Map<?, ?> arguments, Redactor redactor) {
    var redacted = new LinkedHashMap<String, Object>();
    arguments.forEach(
        (key, value) ->
            redacted.put(
                text((String) key, redactor, JsonSnapshot.MAX_KEY_LENGTH), value(value, redactor)));
    return redacted;
  }

  private static Object value(Object value, Redactor redactor) {
    return switch (value) {
      case String text -> text(text, redactor, JsonSnapshot.MAX_STRING_LENGTH);
      case Map<?, ?> object -> object(object, redactor);
      case List<?> array -> array.stream().map(element -> value(element, redactor)).toList();
      case null, default -> value;
    };
  }

  private static String text(String text, Redactor redactor, int limit) {
    var shown = redactedAroundSurrogates(text, redactor);
    return shown.length() <= limit ? shown : Strings.truncated(shown, limit - CUT.length(), CUT);
  }

  /**
   * Redacts the runs between unpaired surrogates, which UTF-8 would turn into {@code ?}. A
   * registered secret is ASCII, so none spans one.
   */
  private static String redactedAroundSurrogates(String text, Redactor redactor) {
    var shown = new StringBuilder(text.length());
    var start = 0;
    for (var i = 0; i < text.length(); i += Character.charCount(text.codePointAt(i))) {
      if (Character.getType(text.codePointAt(i)) == Character.SURROGATE) {
        shown.append(redactor.redact(text.substring(start, i)).text()).append(text.charAt(i));
        start = i + 1;
      }
    }
    return shown.append(redactor.redact(text.substring(start)).text()).toString();
  }
}
