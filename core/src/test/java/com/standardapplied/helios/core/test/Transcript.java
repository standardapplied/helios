/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.ProviderException;
import com.standardapplied.helios.core.model.StreamEvent;
import com.standardapplied.helios.core.model.TransientStreamException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Canonical text of what a model streamed or returned, for a golden file to pin. Records render
 * every component by name, maps in key order, and exceptions as their type, message and cause
 * chain, so two runs that behave the same render the same text whatever the hash order of a map.
 */
public final class Transcript {

  private final UnaryOperator<String> mapStrings;

  /** A transcript that renders every string as it is. */
  public Transcript() {
    this(UnaryOperator.identity());
  }

  /**
   * A transcript that renders a string held in a map through {@code mapStrings}: the canonical form
   * of a value whose text varies without its meaning varying, such as a JSON document built from a
   * hash map.
   */
  public Transcript(UnaryOperator<String> mapStrings) {
    this.mapStrings = mapStrings;
  }

  /** Drains and closes {@code events}, one rendered event per line. */
  public String events(CloseableIterator<StreamEvent> events) {
    var text = new StringBuilder();
    try (events) {
      while (events.hasNext()) {
        text.append(render(events.next())).append('\n');
      }
    }
    return text.toString();
  }

  /** The rendered value {@code call} returns, or the rendered exception it throws. */
  public String outcome(Supplier<?> call) {
    try {
      return render(call.get()) + '\n';
    } catch (RuntimeException e) {
      return "threw " + render(e) + '\n';
    }
  }

  /** The canonical text of {@code value}. */
  public String render(Object value) {
    return switch (value) {
      case null -> "null";
      case Map<?, ?> map -> renderMap(map);
      case Collection<?> items ->
          items.stream().map(this::render).collect(Collectors.joining(", ", "[", "]"));
      case Record record -> renderRecord(record);
      case Throwable thrown -> renderThrowable(thrown);
      default -> String.valueOf(value);
    };
  }

  private String renderMap(Map<?, ?> map) {
    var sorted = new TreeMap<String, String>();
    map.forEach(
        (key, entry) ->
            sorted.put(
                String.valueOf(key),
                entry instanceof String text ? mapStrings.apply(text) : render(entry)));
    return sorted.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining(", ", "{", "}"));
  }

  private String renderRecord(Record record) {
    var components = new StringBuilder();
    for (var component : record.getClass().getRecordComponents()) {
      if (!components.isEmpty()) {
        components.append(", ");
      }
      components.append(component.getName()).append('=').append(render(read(component, record)));
    }
    return record.getClass().getSimpleName() + "[" + components + "]";
  }

  private static Object read(RecordComponent component, Record record) {
    try {
      return component.getAccessor().invoke(record);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new IllegalStateException("cannot read " + component, e);
    }
  }

  private String renderThrowable(Throwable thrown) {
    var text = thrown.getClass().getSimpleName() + "(" + thrown.getMessage() + ")";
    if (thrown instanceof TransientStreamException transientFailure) {
      text += " provider=" + transientFailure.providerName();
    }
    if (thrown instanceof ProviderException providerFailure) {
      text += " status=" + providerFailure.statusCode();
    }
    return thrown.getCause() == null ? text : text + " caused by " + render(thrown.getCause());
  }
}
