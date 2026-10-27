/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

/**
 * Where a run's decision to end meets the session's {@code send}: the session takes one lock for
 * both, so a message sent as the run decides to end is either part of the run or starts the next
 * one, and never stays queued for a run that has ended.
 */
@FunctionalInterface
public interface RunBoundary {

  /**
   * Decide that the run ends, unless a message is queued and the run's verdict holds only while
   * none is. Once the run ends, a message still queued for it is discarded, and the next message
   * sent starts the next run.
   *
   * @param naturalStop whether the run's verdict is one a queued message overturns: the {@code
   *     Success} of a turn that stopped with no message queued, or a first turn with no user
   *     message
   * @return {@code true} if the run ends; {@code false} if a message is queued and the run goes on
   */
  boolean end(boolean naturalStop);
}
