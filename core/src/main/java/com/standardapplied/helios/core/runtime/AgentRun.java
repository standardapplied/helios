/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable record of a single run invocation. One row per logical run; updated in place at iteration
 * boundaries and again on terminal status. Combined with {@link ToolCallRecord} entries written by
 * the {@link ToolCallJournal}, this is everything a resume call needs to pick a crashed run back
 * up.
 *
 * @param runId stable identifier for the run; supplied by the caller at start
 * @param sessionId session this run belongs to
 * @param agentId logical agent name
 * @param userId user the run belongs to, nullable for anonymous runs
 * @param status current lifecycle status
 * @param iteration zero-based iteration counter at the most recent checkpoint
 * @param startedAt UTC time the run was first checkpointed
 * @param lastCheckpointAt UTC time of the most recent checkpoint
 * @param endedAt UTC time the run reached a terminal status, or {@code null} if still
 *     RUNNING/SUSPENDED
 * @param error terminal error message when {@link AgentRunStatus#FAILED}, otherwise {@code null}
 */
public record AgentRun(
    UUID runId,
    UUID sessionId,
    String agentId,
    String userId,
    AgentRunStatus status,
    int iteration,
    OffsetDateTime startedAt,
    OffsetDateTime lastCheckpointAt,
    OffsetDateTime endedAt,
    String error) {

  public AgentRun {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(startedAt, "startedAt");
    Objects.requireNonNull(lastCheckpointAt, "lastCheckpointAt");
    if (iteration < 0) {
      throw new IllegalArgumentException("iteration must be >= 0");
    }
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(AgentRun run) {
    return new Builder(run);
  }

  public static class Builder {
    private UUID runId;
    private UUID sessionId;
    private String agentId;
    private String userId;
    private AgentRunStatus status = AgentRunStatus.RUNNING;
    private int iteration = 0;
    private OffsetDateTime startedAt;
    private OffsetDateTime lastCheckpointAt;
    private OffsetDateTime endedAt;
    private String error;

    private Builder() {}

    private Builder(AgentRun run) {
      this.runId = run.runId;
      this.sessionId = run.sessionId;
      this.agentId = run.agentId;
      this.userId = run.userId;
      this.status = run.status;
      this.iteration = run.iteration;
      this.startedAt = run.startedAt;
      this.lastCheckpointAt = run.lastCheckpointAt;
      this.endedAt = run.endedAt;
      this.error = run.error;
    }

    public Builder withRunId(UUID runId) {
      this.runId = runId;
      return this;
    }

    public Builder withSessionId(UUID sessionId) {
      this.sessionId = sessionId;
      return this;
    }

    public Builder withAgentId(String agentId) {
      this.agentId = agentId;
      return this;
    }

    public Builder withUserId(String userId) {
      this.userId = userId;
      return this;
    }

    public Builder withStatus(AgentRunStatus status) {
      this.status = status;
      return this;
    }

    public Builder withIteration(int iteration) {
      this.iteration = iteration;
      return this;
    }

    public Builder withStartedAt(OffsetDateTime startedAt) {
      this.startedAt = startedAt;
      return this;
    }

    public Builder withLastCheckpointAt(OffsetDateTime lastCheckpointAt) {
      this.lastCheckpointAt = lastCheckpointAt;
      return this;
    }

    public Builder withEndedAt(OffsetDateTime endedAt) {
      this.endedAt = endedAt;
      return this;
    }

    public Builder withError(String error) {
      this.error = error;
      return this;
    }

    public AgentRun build() {
      return new AgentRun(
          runId,
          sessionId,
          agentId,
          userId,
          status,
          iteration,
          startedAt,
          lastCheckpointAt,
          endedAt,
          error);
    }
  }
}
