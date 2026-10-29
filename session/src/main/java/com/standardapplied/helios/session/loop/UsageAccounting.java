/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.CostEstimate;
import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SerializedError;
import java.time.Duration;
import java.time.Instant;

/**
 * Accounts every model attempt and reports it as one {@link QueryEvent.ModelCallEnded}: an
 * accounted call's usage is added to the run's totals and priced through the session's {@link
 * CostCalculator} — the main model's turns at its own rate, and a {@link
 * com.standardapplied.helios.session.ContextCompactor}'s summary call at the compactor's model
 * rate, so a cheap Haiku compactor wired against an Opus main loop is not priced at Opus rates —
 * and its event carries exactly that usage and cost. A retried attempt, or one that ended with a
 * host {@link Error}, is reported with zero usage and is not accounted. A clock that stepped back
 * during an attempt reports it with a zero duration. No call is accounted without its event, and no
 * event adds usage without it.
 */
final class UsageAccounting {

  private final LoopCollaborators collaborators;
  private final EventEmitter emitter;
  private final Model model;
  private final CostCalculator costCalculator;

  UsageAccounting(
      LoopCollaborators collaborators,
      EventEmitter emitter,
      Model model,
      CostCalculator costCalculator) {
    this.collaborators = collaborators;
    this.emitter = emitter;
    this.model = model;
    this.costCalculator = costCalculator;
  }

  /**
   * Account a turn's final attempt at the main model's rate and report it.
   *
   * @param turn the attempt's outcome, whose usage, finish reason and error are reported
   * @param attempt the attempt's 1-based number
   * @param started when the attempt's request was made
   * @param ended when the attempt's stream ended
   */
  void accountTurn(
      SessionState state, TurnOutcome turn, int attempt, Instant started, Instant ended) {
    account(
        state,
        new Call(model.id(), attempt, turn.finishReason(), turn.usage(), turn.streamError()),
        started,
        ended);
  }

  /**
   * Report an attempt that is not accounted, with zero usage: one that failed transiently and is
   * retried, or one that ended with a host {@link Error}.
   */
  void reportUnaccounted(
      SessionState state, int attempt, Throwable error, Instant started, Instant ended) {
    var call = new Call(model.id(), attempt, FinishReason.ERROR, Usage.of(0, 0), error);
    emit(state, call, CostEstimate.zero(), started, ended);
  }

  /**
   * Account a compactor's summary call at {@code modelId}'s rate and report it; a {@link
   * com.standardapplied.helios.session.CompactionResult} that reports tokens names its model.
   */
  void accountCompaction(
      SessionState state, String modelId, Usage usage, Instant started, Instant ended) {
    account(state, new Call(modelId, 1, FinishReason.STOP, usage, null), started, ended);
  }

  /** Add the call's usage, then its cost; a pricing failure leaves the usage added at zero cost. */
  private void account(SessionState state, Call call, Instant started, Instant ended) {
    state.totals().accumulateUsage(call.usage());
    var accounted = CostEstimate.zero();
    try {
      var cost = costCalculator.cost(call.modelId(), call.usage());
      state.totals().accumulateCost(cost);
      accounted = cost;
    } finally {
      emit(state, call, accounted, started, ended);
    }
  }

  private void emit(
      SessionState state, Call call, CostEstimate cost, Instant started, Instant ended) {
    var error = call.error() == null ? null : redacted(call.error());
    emitter.emit(
        state,
        new QueryEvent.ModelCallEnded(
            state.sessionId(),
            state.runId(),
            state.currentTurnIndex(),
            ended,
            call.modelId(),
            call.attempt(),
            call.finishReason(),
            call.usage(),
            cost,
            ended.isBefore(started) ? Duration.ZERO : Duration.between(started, ended),
            error));
  }

  /** {@code error} serialized and redacted, or only its class name when it cannot be read. */
  private SerializedError redacted(Throwable error) {
    try {
      return SerializedError.of(error).redactedBy(collaborators.secrets().redactor());
    } catch (RuntimeException unreadable) {
      return SerializedError.of(error.getClass().getName(), "");
    }
  }

  /** One model attempt: the model id its usage is priced at, and what it reported. */
  private record Call(
      String modelId, int attempt, FinishReason finishReason, Usage usage, Throwable error) {}
}
