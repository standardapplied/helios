/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable journal entry for a single tool invocation within an {@link AgentRun}.
 *
 * <p>One row per logical call: written as {@link ToolCallStatus#STARTED} immediately before the
 * tool's {@link ai.singlr.core.fault.FaultTolerance} envelope is entered, and updated to a terminal
 * status after the envelope returns — regardless of how many in-process retries happened inside.
 *
 * @param runId run this call belongs to
 * @param iteration zero-based iteration index at the time the call was journaled
 * @param toolCallId model-supplied call identifier (matches {@code ToolCall.id()})
 * @param toolName the tool's name
 * @param args arguments passed to the tool, nullable for tools without parameters
 * @param status current status; transitions {@code STARTED -> SUCCEEDED|FAILED}
 * @param output truncated output text from a successful {@link ai.singlr.core.tool.ToolResult},
 *     nullable
 * @param error error message when {@link ToolCallStatus#FAILED}, otherwise {@code null}
 * @param startedAt UTC time {@code STARTED} was journaled
 * @param endedAt UTC time the terminal status was journaled, or {@code null} while in-flight
 */
public record ToolCallRecord(
    UUID runId,
    int iteration,
    String toolCallId,
    String toolName,
    Map<String, Object> args,
    ToolCallStatus status,
    String output,
    String error,
    OffsetDateTime startedAt,
    OffsetDateTime endedAt) {

  public ToolCallRecord {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(toolCallId, "toolCallId");
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(startedAt, "startedAt");
    if (iteration < 0) {
      throw new IllegalArgumentException("iteration must be >= 0");
    }
    // Defensive copy that preserves insertion order and allows null values. Models routinely emit
    // null arg values (e.g. {"timeout": null}); Map.copyOf rejects nulls, so we use an unmodifiable
    // wrapper around a LinkedHashMap instead.
    args = args == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(args));
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(ToolCallRecord record) {
    return new Builder(record);
  }

  public static class Builder {
    private UUID runId;
    private int iteration;
    private String toolCallId;
    private String toolName;
    private Map<String, Object> args;
    private ToolCallStatus status = ToolCallStatus.STARTED;
    private String output;
    private String error;
    private OffsetDateTime startedAt;
    private OffsetDateTime endedAt;

    private Builder() {}

    private Builder(ToolCallRecord record) {
      this.runId = record.runId;
      this.iteration = record.iteration;
      this.toolCallId = record.toolCallId;
      this.toolName = record.toolName;
      this.args = record.args;
      this.status = record.status;
      this.output = record.output;
      this.error = record.error;
      this.startedAt = record.startedAt;
      this.endedAt = record.endedAt;
    }

    public Builder withRunId(UUID runId) {
      this.runId = runId;
      return this;
    }

    public Builder withIteration(int iteration) {
      this.iteration = iteration;
      return this;
    }

    public Builder withToolCallId(String toolCallId) {
      this.toolCallId = toolCallId;
      return this;
    }

    public Builder withToolName(String toolName) {
      this.toolName = toolName;
      return this;
    }

    public Builder withArgs(Map<String, Object> args) {
      this.args = args;
      return this;
    }

    public Builder withStatus(ToolCallStatus status) {
      this.status = status;
      return this;
    }

    public Builder withOutput(String output) {
      this.output = output;
      return this;
    }

    public Builder withError(String error) {
      this.error = error;
      return this;
    }

    public Builder withStartedAt(OffsetDateTime startedAt) {
      this.startedAt = startedAt;
      return this;
    }

    public Builder withEndedAt(OffsetDateTime endedAt) {
      this.endedAt = endedAt;
      return this;
    }

    public ToolCallRecord build() {
      return new ToolCallRecord(
          runId, iteration, toolCallId, toolName, args, status, output, error, startedAt, endedAt);
    }
  }
}
