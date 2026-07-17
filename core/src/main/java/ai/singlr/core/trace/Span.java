/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import ai.singlr.core.common.CostEstimate;
import ai.singlr.core.common.Ids;
import ai.singlr.core.model.Response.Usage;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable span representing a unit of work within a trace.
 *
 * @param id unique identifier
 * @param name descriptive name for this span
 * @param kind the type of work this span represents
 * @param startTime when the span started
 * @param endTime when the span ended
 * @param duration wall-clock duration
 * @param error error message, or null if the span succeeded
 * @param children child spans nested within this span
 * @param attributes key-value metadata
 * @param usage per-call token usage for model-call spans, or null if not recorded
 * @param cost per-call cost estimate for model-call spans, or null if not recorded
 */
public record Span(
    UUID id,
    String name,
    SpanKind kind,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    Duration duration,
    String error,
    List<Span> children,
    Map<String, String> attributes,
    Usage usage,
    CostEstimate cost) {

  /**
   * Whether the span completed without an error attached.
   *
   * @return {@code true} when no error message was recorded on this span
   */
  public boolean success() {
    return error == null;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(Span span) {
    return new Builder(span);
  }

  /** Builder for Span. Used by persistence module to reconstruct from DB rows. */
  public static class Builder {

    private UUID id;
    private String name;
    private SpanKind kind;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private Duration duration;
    private String error;
    private List<Span> children = new ArrayList<>();
    private Map<String, String> attributes = new LinkedHashMap<>();
    private Usage usage;
    private CostEstimate cost;

    private Builder() {}

    private Builder(Span span) {
      this.id = span.id;
      this.name = span.name;
      this.kind = span.kind;
      this.startTime = span.startTime;
      this.endTime = span.endTime;
      this.duration = span.duration;
      this.error = span.error;
      this.children = new ArrayList<>(span.children);
      this.attributes = new LinkedHashMap<>(span.attributes);
      this.usage = span.usage;
      this.cost = span.cost;
    }

    public Builder withId(UUID id) {
      this.id = id;
      return this;
    }

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withKind(SpanKind kind) {
      this.kind = kind;
      return this;
    }

    public Builder withStartTime(OffsetDateTime startTime) {
      this.startTime = startTime;
      return this;
    }

    public Builder withEndTime(OffsetDateTime endTime) {
      this.endTime = endTime;
      return this;
    }

    public Builder withDuration(Duration duration) {
      this.duration = duration;
      return this;
    }

    public Builder withError(String error) {
      this.error = error;
      return this;
    }

    public Builder withChildren(List<Span> children) {
      this.children = new ArrayList<>(children);
      return this;
    }

    public Builder withChild(Span child) {
      this.children.add(child);
      return this;
    }

    public Builder withAttributes(Map<String, String> attributes) {
      this.attributes = new LinkedHashMap<>(attributes);
      return this;
    }

    public Builder withAttribute(String key, String value) {
      this.attributes.put(key, value);
      return this;
    }

    public Builder withUsage(Usage usage) {
      this.usage = usage;
      return this;
    }

    public Builder withCost(CostEstimate cost) {
      this.cost = cost;
      return this;
    }

    /**
     * Builds the Span. Auto-generates id and startTime if not set. Computes duration from startTime
     * and endTime if duration not explicitly set.
     */
    public Span build() {
      if (id == null) {
        id = Ids.newId();
      }
      if (startTime == null) {
        startTime = Ids.now();
      }
      if (duration == null && endTime != null) {
        duration = Duration.between(startTime, endTime);
      }
      return new Span(
          id,
          name,
          kind,
          startTime,
          endTime,
          duration,
          error,
          List.copyOf(children),
          Map.copyOf(attributes),
          usage,
          cost);
    }
  }
}
