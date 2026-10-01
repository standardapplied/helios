/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

import java.time.OffsetDateTime;

/**
 * Optional equality and time-window constraints for a trace rollup query. Null fields are
 * unconstrained; {@code since} is inclusive and {@code until} exclusive, both against {@link
 * Trace#startTime()}.
 *
 * @param name restrict to traces with this name
 * @param groupId restrict to traces in this eval group
 * @param promptName restrict to traces stamped with this prompt name
 * @param promptVersion restrict to traces stamped with this prompt version
 * @param since restrict to traces starting at or after this instant
 * @param until restrict to traces starting before this instant
 */
public record TraceFilter(
    String name,
    String groupId,
    String promptName,
    Integer promptVersion,
    OffsetDateTime since,
    OffsetDateTime until) {

  private static final TraceFilter NONE = new TraceFilter(null, null, null, null, null, null);

  /** Returns the unconstrained filter. */
  public static TraceFilter none() {
    return NONE;
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** Builder for TraceFilter. */
  public static class Builder {
    private String name;
    private String groupId;
    private String promptName;
    private Integer promptVersion;
    private OffsetDateTime since;
    private OffsetDateTime until;

    private Builder() {}

    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    public Builder withGroupId(String groupId) {
      this.groupId = groupId;
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

    public Builder withSince(OffsetDateTime since) {
      this.since = since;
      return this;
    }

    public Builder withUntil(OffsetDateTime until) {
      this.until = until;
      return this;
    }

    public TraceFilter build() {
      return new TraceFilter(name, groupId, promptName, promptVersion, since, until);
    }
  }
}
