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
 * An immutable trace representing a complete agent execution.
 *
 * @param id unique identifier
 * @param name descriptive name for this trace
 * @param startTime when the trace started
 * @param endTime when the trace ended
 * @param duration wall-clock duration
 * @param error error message, or null if the trace succeeded
 * @param spans top-level spans within this trace
 * @param attributes key-value metadata
 * @param inputText the user's input message
 * @param outputText the agent's final response text
 * @param userId who triggered this trace
 * @param sessionId session this trace belongs to
 * @param modelId model identifier (e.g., "gemini-2.0-flash")
 * @param promptName prompt registry name
 * @param promptVersion prompt registry version
 * @param totalTokens aggregated token count from model calls
 * @param usage rolled-up token usage summed across spans that recorded one, or null if none did
 * @param cost rolled-up cost summed across spans that recorded one, or null if none did
 * @param thumbsUpCount denormalized positive feedback count (DB-managed)
 * @param thumbsDownCount denormalized negative feedback count (DB-managed)
 * @param groupId comparison/evaluation batch grouping
 * @param labels freeform classification tags
 */
public record Trace(
    UUID id,
    String name,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    Duration duration,
    String error,
    List<Span> spans,
    Map<String, String> attributes,
    String inputText,
    String outputText,
    String userId,
    UUID sessionId,
    String modelId,
    String promptName,
    Integer promptVersion,
    int totalTokens,
    Usage usage,
    CostEstimate cost,
    int thumbsUpCount,
    int thumbsDownCount,
    String groupId,
    List<String> labels) {

  /** Returns true if this trace completed without error. */
  public boolean success() {
    return error == null;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(Trace trace) {
    return new Builder(trace);
  }

  /** Builder for Trace. Used by persistence module to reconstruct from DB rows. */
  public static class Builder {

    private UUID id;
    private String name;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private Duration duration;
    private String error;
    private List<Span> spans = new ArrayList<>();
    private Map<String, String> attributes = new LinkedHashMap<>();
    private String inputText;
    private String outputText;
    private String userId;
    private UUID sessionId;
    private String modelId;
    private String promptName;
    private Integer promptVersion;
    private int totalTokens;
    private Usage usage;
    private CostEstimate cost;
    private int thumbsUpCount;
    private int thumbsDownCount;
    private String groupId;
    private List<String> labels = new ArrayList<>();

    private Builder() {}

    private Builder(Trace trace) {
      this.id = trace.id;
      this.name = trace.name;
      this.startTime = trace.startTime;
      this.endTime = trace.endTime;
      this.duration = trace.duration;
      this.error = trace.error;
      this.spans = new ArrayList<>(trace.spans);
      this.attributes = new LinkedHashMap<>(trace.attributes);
      this.inputText = trace.inputText;
      this.outputText = trace.outputText;
      this.userId = trace.userId;
      this.sessionId = trace.sessionId;
      this.modelId = trace.modelId;
      this.promptName = trace.promptName;
      this.promptVersion = trace.promptVersion;
      this.totalTokens = trace.totalTokens;
      this.usage = trace.usage;
      this.cost = trace.cost;
      this.thumbsUpCount = trace.thumbsUpCount;
      this.thumbsDownCount = trace.thumbsDownCount;
      this.groupId = trace.groupId;
      this.labels = new ArrayList<>(trace.labels);
    }

    public Builder withId(UUID id) {
      this.id = id;
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

    public Builder withDuration(Duration duration) {
      this.duration = duration;
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

    public Builder withSpan(Span span) {
      this.spans.add(span);
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

    public Builder withInputText(String inputText) {
      this.inputText = inputText;
      return this;
    }

    public Builder withOutputText(String outputText) {
      this.outputText = outputText;
      return this;
    }

    public Builder withUserId(String userId) {
      this.userId = userId;
      return this;
    }

    public Builder withSessionId(UUID sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder withModelId(String modelId) {
      this.modelId = modelId;
      return this;
    }

    public Builder withPromptName(String promptName) {
      this.promptName = promptName;
      return this;
    }

    public Builder withPromptVersion(Integer promptVersion) {
      this.promptVersion = promptVersion;
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

    public Builder withGroupId(String groupId) {
      this.groupId = groupId;
      return this;
    }

    public Builder withLabels(List<String> labels) {
      this.labels = new ArrayList<>(labels);
      return this;
    }

    /**
     * Builds the Trace. Auto-generates id and startTime if not set. Computes duration from
     * startTime and endTime if duration not explicitly set.
     */
    public Trace build() {
      if (id == null) {
        id = Ids.newId();
      }
      if (startTime == null) {
        startTime = Ids.now();
      }
      if (duration == null && endTime != null) {
        duration = Duration.between(startTime, endTime);
      }
      return new Trace(
          id,
          name,
          startTime,
          endTime,
          duration,
          error,
          List.copyOf(spans),
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
          thumbsUpCount,
          thumbsDownCount,
          groupId,
          List.copyOf(labels));
    }
  }
}
