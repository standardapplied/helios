/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import java.time.OffsetDateTime;

/**
 * Optional equality and time-window constraints for a trace rollup query. Null fields are
 * unconstrained; {@code since} is inclusive and {@code until} exclusive, both against {@link
 * Trace#startTime()}.
 *
 * @param name restrict to runs with this name
 * @param since restrict to runs starting at or after this instant
 * @param until restrict to runs starting before this instant
 */
public record TraceFilter(String name, OffsetDateTime since, OffsetDateTime until) {

  private static final TraceFilter NONE = new TraceFilter(null, null, null);

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
    private OffsetDateTime since;
    private OffsetDateTime until;

    private Builder() {}

    public Builder withName(String name) {
      this.name = name;
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
      return new TraceFilter(name, since, until);
    }
  }
}
