/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.common.Ids;
import ai.singlr.core.events.EventSink;
import ai.singlr.core.model.Response.Usage;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mutable builder for constructing traces during agent execution.
 *
 * <p>Create with {@link #start(String)} or {@link #start(String, UUID, List)}. Add spans with
 * {@link #span(String, SpanKind)}, then call {@link #end()} or {@link #fail(String)} to produce an
 * immutable {@link Trace}.
 *
 * <p>The terminal {@link Trace} is returned from {@link #end()} / {@link #fail(String)} so callers
 * can attach it to a {@link ai.singlr.core.events.HeliosEvent.RunCompleted} or {@link
 * ai.singlr.core.events.HeliosEvent.RunFailed} event. Per-span {@link
 * ai.singlr.core.events.HeliosEvent.SpanOpened} / {@link
 * ai.singlr.core.events.HeliosEvent.SpanClosed} events are emitted directly by {@link SpanBuilder}
 * to the supplied {@code eventSinks}.
 *
 * <p>Not thread-safe. Designed for sequential use within an agent loop.
 */
public final class TraceBuilder implements SpanContainer {

  private final UUID id;
  private final String name;
  private final OffsetDateTime startTime;
  private final UUID runId;
  private final List<EventSink> eventSinks;
  private final Map<String, String> attributes = new LinkedHashMap<>();
  private final List<SpanBuilder> openSpans = new ArrayList<>();
  private final List<Span> completedSpans = new ArrayList<>();
  private boolean ended;

  private String inputText;
  private String outputText;
  private String userId;
  private UUID sessionId;
  private String modelId;
  private String promptName;
  private Integer promptVersion;
  private String groupId;
  private List<String> labels = List.of();

  private TraceBuilder(String name, UUID runId, List<EventSink> eventSinks) {
    this.id = Ids.newId();
    this.name = name;
    this.startTime = Ids.now();
    this.runId = runId;
    this.eventSinks = eventSinks == null ? List.of() : List.copyOf(eventSinks);
  }

  /**
   * Starts a new trace with no event sinks. Spans within this trace will not emit any per-span
   * events. Useful for callers that only want the terminal {@link Trace} artifact.
   *
   * @param name the trace name
   * @return a new TraceBuilder
   */
  public static TraceBuilder start(String name) {
    return new TraceBuilder(name, null, List.of());
  }

  /**
   * Starts a new trace whose spans emit {@code SpanOpened} / {@code SpanClosed} events to every
   * supplied {@link EventSink}. The {@code runId} threads through every event so consumers can
   * correlate spans with the agent run that produced them.
   *
   * @param name the trace name
   * @param runId per-run identifier carried on every emitted event; may be {@code null} only when
   *     {@code eventSinks} is empty
   * @param eventSinks sinks notified for every span open/close within this trace
   * @return a new TraceBuilder
   */
  public static TraceBuilder start(String name, UUID runId, List<EventSink> eventSinks) {
    return new TraceBuilder(name, runId, eventSinks);
  }

  /**
   * Creates a new top-level span within this trace.
   *
   * @param name the span name
   * @param kind the span kind
   * @return the SpanBuilder
   * @throws IllegalStateException if this trace has already ended
   */
  @Override
  public SpanBuilder span(String name, SpanKind kind) {
    requireOpen();
    var span = new SpanBuilder(name, kind, this.id, null, eventSinks, runId);
    openSpans.add(span);
    return span;
  }

  /**
   * Adds a key-value attribute to this trace.
   *
   * @param key the attribute key
   * @param value the attribute value
   * @return this builder for chaining
   * @throws IllegalStateException if this trace has already ended
   */
  public TraceBuilder attribute(String key, String value) {
    requireOpen();
    attributes.put(key, value);
    return this;
  }

  public TraceBuilder inputText(String inputText) {
    this.inputText = inputText;
    return this;
  }

  public TraceBuilder outputText(String outputText) {
    this.outputText = outputText;
    return this;
  }

  public TraceBuilder userId(String userId) {
    this.userId = userId;
    return this;
  }

  public TraceBuilder sessionId(UUID sessionId) {
    this.sessionId = sessionId;
    return this;
  }

  public TraceBuilder modelId(String modelId) {
    this.modelId = modelId;
    return this;
  }

  public TraceBuilder promptName(String promptName) {
    this.promptName = promptName;
    return this;
  }

  public TraceBuilder promptVersion(Integer promptVersion) {
    this.promptVersion = promptVersion;
    return this;
  }

  public TraceBuilder groupId(String groupId) {
    this.groupId = groupId;
    return this;
  }

  public TraceBuilder labels(List<String> labels) {
    this.labels = labels != null ? List.copyOf(labels) : List.of();
    return this;
  }

  /**
   * Completes this trace successfully and returns the built {@link Trace}. The caller is
   * responsible for surfacing it via {@link ai.singlr.core.events.HeliosEvent.RunCompleted}.
   *
   * @return the immutable Trace
   * @throws IllegalStateException if this trace has already ended
   * @throws IllegalStateException if any spans are still open
   */
  public Trace end() {
    requireOpen();
    collectCompletedSpans();
    if (!openSpans.isEmpty()) {
      throw new IllegalStateException(
          "Cannot end trace '%s': %d span(s) still open".formatted(name, openSpans.size()));
    }
    return complete(null);
  }

  /**
   * Completes this trace with an error. Auto-fails any open spans and returns the built {@link
   * Trace}. The caller is responsible for surfacing it via {@link
   * ai.singlr.core.events.HeliosEvent.RunFailed}.
   *
   * @param error the error message
   * @return the immutable Trace
   * @throws IllegalStateException if this trace has already ended
   */
  public Trace fail(String error) {
    requireOpen();
    collectCompletedSpans();
    failOpenSpans(error);
    return complete(error);
  }

  private Trace complete(String error) {
    ended = true;
    var endTime = Ids.now();
    var duration = Duration.between(startTime, endTime);
    var usage = rollUpUsage(completedSpans);
    var cost = rollUpCost(completedSpans);
    var totalTokens = usage != null ? usage.totalTokens() : computeTotalTokens();
    return new Trace(
        id,
        name,
        startTime,
        endTime,
        duration,
        error,
        List.copyOf(completedSpans),
        Map.copyOf(attributes),
        inputText,
        outputText,
        userId,
        sessionId,
        modelId,
        promptName,
        promptVersion,
        totalTokens,
        usage,
        cost,
        0,
        0,
        groupId,
        labels);
  }

  private static Usage rollUpUsage(List<Span> spans) {
    Usage total = null;
    for (var span : spans) {
      if (span.usage() != null) {
        total = total == null ? span.usage() : total.plus(span.usage());
      }
      var childTotal = rollUpUsage(span.children());
      if (childTotal != null) {
        total = total == null ? childTotal : total.plus(childTotal);
      }
    }
    return total;
  }

  private static CostEstimate rollUpCost(List<Span> spans) {
    CostEstimate total = null;
    for (var span : spans) {
      if (span.cost() != null) {
        total = total == null ? span.cost() : total.plus(span.cost());
      }
      var childTotal = rollUpCost(span.children());
      if (childTotal != null) {
        total = total == null ? childTotal : total.plus(childTotal);
      }
    }
    return total;
  }

  private int computeTotalTokens() {
    int total = 0;
    for (var span : completedSpans) {
      if (span.kind() == SpanKind.MODEL_CALL) {
        total += parseTokenAttribute(span.attributes().get("inputTokens"));
        total += parseTokenAttribute(span.attributes().get("outputTokens"));
      }
    }
    return total;
  }

  private static int parseTokenAttribute(String value) {
    if (value == null) {
      return 0;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private void collectCompletedSpans() {
    var stillOpen = new ArrayList<SpanBuilder>();
    for (var sb : openSpans) {
      if (!sb.isOpen()) {
        completedSpans.add(sb.result());
      } else {
        stillOpen.add(sb);
      }
    }
    openSpans.clear();
    openSpans.addAll(stillOpen);
  }

  private void failOpenSpans(String traceError) {
    for (var sb : openSpans) {
      if (sb.isOpen()) {
        completedSpans.add(sb.fail("Trace '%s' failed: %s".formatted(name, traceError)));
      }
    }
    openSpans.clear();
  }

  private void requireOpen() {
    if (ended) {
      throw new IllegalStateException("Trace '%s' has already ended".formatted(name));
    }
  }
}
