/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.Response.Usage;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One stored run of a session, as {@link PgSpanExporter} wrote it from the run's {@code
 * invoke_agent} span and {@link PgTraceStore} reads it back.
 *
 * @param id the run id, the span's {@code helios.run.id}
 * @param otelTraceId the OpenTelemetry trace id, 32 lower-case hex digits
 * @param otelSpanId the run span's OpenTelemetry span id, 16 lower-case hex digits
 * @param name the agent's name, the span's {@code gen_ai.agent.name}
 * @param startTime when the run started
 * @param endTime when the run ended
 * @param duration from {@code startTime} to {@code endTime}
 * @param error the span's status description when the run failed, or null if it succeeded
 * @param spans the run's model, tool and hook spans, in start order
 * @param attributes every other attribute of the run's span, as strings
 * @param inputText the run's first user message, stored only under {@code TraceDetail.VERBOSE}
 * @param outputText a successful run's result, stored only under {@code TraceDetail.VERBOSE}
 * @param sessionId the session the run belongs to
 * @param modelId the session's model id
 * @param totalTokens the run's total token count
 * @param usage the run's token usage, or null if none was recorded
 * @param cost the run's cost, or null if none was recorded
 * @param thumbsUpCount the run's annotations with a positive rating, counted when read
 * @param thumbsDownCount the run's annotations with a negative rating, counted when read
 */
public record Trace(
    String id,
    String otelTraceId,
    String otelSpanId,
    String name,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    Duration duration,
    String error,
    List<Span> spans,
    Map<String, String> attributes,
    String inputText,
    String outputText,
    String sessionId,
    String modelId,
    int totalTokens,
    Usage usage,
    CostEstimate cost,
    int thumbsUpCount,
    int thumbsDownCount) {

  /** Returns true if this run completed without error. */
  public boolean success() {
    return error == null;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(Trace trace) {
    return new Builder(trace);
  }

  /** Builder for Trace, used to reconstruct one from its row. */
  public static class Builder {

    private String id;
    private String otelTraceId;
    private String otelSpanId;
    private String name;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private String error;
    private List<Span> spans = new ArrayList<>();
    private Map<String, String> attributes = new LinkedHashMap<>();
    private String inputText;
    private String outputText;
    private String sessionId;
    private String modelId;
    private int totalTokens;
    private Usage usage;
    private CostEstimate cost;
    private int thumbsUpCount;
    private int thumbsDownCount;

    private Builder() {}

    private Builder(Trace trace) {
      this.id = trace.id;
      this.otelTraceId = trace.otelTraceId;
      this.otelSpanId = trace.otelSpanId;
      this.name = trace.name;
      this.startTime = trace.startTime;
      this.endTime = trace.endTime;
      this.error = trace.error;
      this.spans = new ArrayList<>(trace.spans);
      this.attributes = new LinkedHashMap<>(trace.attributes);
      this.inputText = trace.inputText;
      this.outputText = trace.outputText;
      this.sessionId = trace.sessionId;
      this.modelId = trace.modelId;
      this.totalTokens = trace.totalTokens;
      this.usage = trace.usage;
      this.cost = trace.cost;
      this.thumbsUpCount = trace.thumbsUpCount;
      this.thumbsDownCount = trace.thumbsDownCount;
    }

    public Builder withId(String id) {
      this.id = id;
      return this;
    }

    public Builder withOtelTraceId(String otelTraceId) {
      this.otelTraceId = otelTraceId;
      return this;
    }

    public Builder withOtelSpanId(String otelSpanId) {
      this.otelSpanId = otelSpanId;
      return this;
    }

    public Builder withName(String name) {
      this.name = name;
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

    public Builder withError(String error) {
      this.error = error;
      return this;
    }

    public Builder withSpans(List<Span> spans) {
      this.spans = new ArrayList<>(spans);
      return this;
    }

    public Builder withAttributes(Map<String, String> attributes) {
      this.attributes = new LinkedHashMap<>(attributes);
      return this;
    }

    public Builder withInputText(String inputText) {
      this.inputText = inputText;
      return this;
    }

    public Builder withOutputText(String outputText) {
      this.outputText = outputText;
      return this;
    }

    public Builder withSessionId(String sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder withModelId(String modelId) {
      this.modelId = modelId;
      return this;
    }

    public Builder withTotalTokens(int totalTokens) {
      this.totalTokens = totalTokens;
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

    public Builder withThumbsUpCount(int thumbsUpCount) {
      this.thumbsUpCount = thumbsUpCount;
      return this;
    }

    public Builder withThumbsDownCount(int thumbsDownCount) {
      this.thumbsDownCount = thumbsDownCount;
      return this;
    }

    /**
     * Builds the Trace, its duration from {@code startTime} to {@code endTime} when both are set.
     */
    public Trace build() {
      var duration =
          startTime != null && endTime != null ? Duration.between(startTime, endTime) : null;
      return new Trace(
          id,
          otelTraceId,
          otelSpanId,
          name,
          startTime,
          endTime,
          duration,
          error,
          List.copyOf(spans),
          Map.copyOf(attributes),
          inputText,
          outputText,
          sessionId,
          modelId,
          totalTokens,
          usage,
          cost,
          thumbsUpCount,
          thumbsDownCount);
    }
  }
}
