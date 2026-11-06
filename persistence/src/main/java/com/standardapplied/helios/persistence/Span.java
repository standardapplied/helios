/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response.Usage;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One stored step of a run, a model attempt, tool call or hook decision, as {@link PgSpanExporter}
 * wrote it from the step's span and {@link PgTraceStore} reads it back.
 *
 * @param id the OpenTelemetry span id, 16 lower-case hex digits
 * @param name the span's name, such as {@code chat gemini-3.8-flash}
 * @param kind the span's {@code gen_ai.operation.name} ({@code chat}, {@code execute_tool}), or
 *     {@code hook} for a hook decision
 * @param startTime when the step started
 * @param endTime when the step ended
 * @param duration from {@code startTime} to {@code endTime}, or null when either is missing
 * @param error the span's status description when the step failed, or null if it succeeded
 * @param attributes every other attribute of the span, as strings
 * @param usage the model attempt's token usage, or null if none was recorded
 * @param cost the model attempt's cost, or null if none was recorded
 */
public record Span(
    String id,
    String name,
    String kind,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    Duration duration,
    String error,
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

  /**
   * Returns an empty builder.
   *
   * @return a builder with every field unset and no attributes
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Builds a {@link Span}, used to reconstruct one from its row. It validates nothing: every field
   * left unset is null. Not thread-safe; use one builder per thread.
   */
  public static class Builder {

    private String id;
    private String name;
    private String kind;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private String error;
    private Map<String, String> attributes = new LinkedHashMap<>();
    private Usage usage;
    private CostEstimate cost;

    private Builder() {}

    /**
     * Sets the OpenTelemetry span id, 16 lower-case hex digits.
     *
     * @param id the span id
     * @return this builder
     */
    public Builder withId(String id) {
      this.id = id;
      return this;
    }

    /**
     * Sets the span's name, such as {@code chat gemini-3.8-flash}.
     *
     * @param name the span name
     * @return this builder
     */
    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the step's kind: the span's {@code gen_ai.operation.name}, or {@code hook} for a hook
     * decision.
     *
     * @param kind the kind
     * @return this builder
     */
    public Builder withKind(String kind) {
      this.kind = kind;
      return this;
    }

    /**
     * Sets when the step started; with the end time it gives the duration.
     *
     * @param startTime the start time, or null if unknown
     * @return this builder
     */
    public Builder withStartTime(OffsetDateTime startTime) {
      this.startTime = startTime;
      return this;
    }

    /**
     * Sets when the step ended; with the start time it gives the duration.
     *
     * @param endTime the end time, or null if unknown
     * @return this builder
     */
    public Builder withEndTime(OffsetDateTime endTime) {
      this.endTime = endTime;
      return this;
    }

    /**
     * Sets the span's status description; null marks a successful step.
     *
     * @param error the failure description, or null for success
     * @return this builder
     */
    public Builder withError(String error) {
      this.error = error;
      return this;
    }

    /**
     * Sets the span's other attributes, replacing any set before. The map is copied.
     *
     * @param attributes the attributes; non-null, with no null key or value
     * @return this builder
     */
    public Builder withAttributes(Map<String, String> attributes) {
      this.attributes = new LinkedHashMap<>(attributes);
      return this;
    }

    /**
     * Sets the model attempt's token usage by class.
     *
     * @param usage the usage, or null if none was recorded
     * @return this builder
     */
    public Builder withUsage(Usage usage) {
      this.usage = usage;
      return this;
    }

    /**
     * Sets the model attempt's cost.
     *
     * @param cost the cost, or null if none was recorded
     * @return this builder
     */
    public Builder withCost(CostEstimate cost) {
      this.cost = cost;
      return this;
    }

    /**
     * Builds the Span, its duration from {@code startTime} to {@code endTime} when both are set.
     *
     * @return the span, holding an unmodifiable copy of the attributes
     * @throws NullPointerException if an attribute key or value is null
     */
    public Span build() {
      var duration =
          startTime != null && endTime != null ? Duration.between(startTime, endTime) : null;
      return new Span(
          id, name, kind, startTime, endTime, duration, error, Map.copyOf(attributes), usage, cost);
    }
  }
}
