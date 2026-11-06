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

  /**
   * Returns the unconstrained filter, which admits every run.
   *
   * @return the filter with every field null
   */
  public static TraceFilter none() {
    return NONE;
  }

  /**
   * Returns an empty builder.
   *
   * @return a builder that, left unchanged, builds an unconstrained filter
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Builds a {@link TraceFilter}; every constraint left unset admits all runs. Not thread-safe; use
   * one builder per thread.
   */
  public static class Builder {
    private String name;
    private OffsetDateTime since;
    private OffsetDateTime until;

    private Builder() {}

    /**
     * Restricts the rollup to runs with this agent name.
     *
     * @param name the name to match exactly, or null for any
     * @return this builder
     */
    public Builder withName(String name) {
      this.name = name;
      return this;
    }

    /**
     * Restricts the rollup to runs that started at or after this instant.
     *
     * @param since the inclusive lower bound, or null for none
     * @return this builder
     */
    public Builder withSince(OffsetDateTime since) {
      this.since = since;
      return this;
    }

    /**
     * Restricts the rollup to runs that started before this instant.
     *
     * @param until the exclusive upper bound, or null for none
     * @return this builder
     */
    public Builder withUntil(OffsetDateTime until) {
      this.until = until;
      return this;
    }

    /**
     * Builds the filter. It does not check that {@code since} precedes {@code until}; a window that
     * is empty matches no run.
     *
     * @return the filter
     */
    public TraceFilter build() {
      return new TraceFilter(name, since, until);
    }
  }
}
