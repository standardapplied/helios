/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.trace.Trace;
import com.standardapplied.helios.core.trace.TraceDetail;
import com.standardapplied.helios.core.trace.TraceListener;
import com.standardapplied.helios.session.loop.SessionEnding;
import com.standardapplied.helios.session.loop.SessionState;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records one {@link Trace} per run of a session and hands it to the session's {@link
 * TraceListener}. It reads every event the loop emits, in order, before the session's publisher
 * sees it, and keeps a {@link RunTrace} per run in progress, from its {@link QueryEvent.RunStarted}
 * to {@link #finish}, which the run's thread calls once, after the run's end step and before the
 * run's future completes. A listener's {@code Exception} is logged and changes nothing; its {@link
 * Error} goes to the session's {@link SessionEnding}, so the run fails with it. Thread-safe: one
 * lock guards the runs in progress, never held while the listener runs.
 */
final class SessionTraceRecorder {

  private static final Logger LOGGER = Logger.getLogger(AgentSessionImpl.class.getName());

  private final String sessionId;
  private final String modelId;
  private final String provider;
  private final TraceListener listener;
  private final boolean verbose;
  private final SecretRegistry secrets;
  private final InstantSource clock;
  private final SessionEnding ending;
  private final Map<String, RunTrace> runs = new HashMap<>();

  /**
   * @throws NullPointerException if the options' model reports no id or provider
   */
  SessionTraceRecorder(SessionOptions options, TraceListener listener, SessionEnding ending) {
    this.sessionId = options.sessionId();
    this.modelId = Objects.requireNonNull(options.model().id(), "model id must not be null");
    this.provider =
        Objects.requireNonNull(options.model().provider(), "model provider must not be null");
    this.listener = listener;
    this.verbose = options.traceDetail() == TraceDetail.VERBOSE;
    this.secrets = options.secretRegistry();
    this.clock = options.clock();
    this.ending = ending;
  }

  /** Record {@code event} in the trace of its run; an event of no run in progress is ignored. */
  void accept(QueryEvent event) {
    synchronized (runs) {
      if (event instanceof QueryEvent.RunStarted started) {
        runs.put(started.runId(), new RunTrace(started.timestamp(), verbose, secrets));
        return;
      }
      var run = runs.get(event.runId());
      if (run != null) {
        run.accept(event);
      }
    }
  }

  /**
   * Finish the trace of {@code run} and hand it to the listener; from now on the run's events are
   * ignored.
   *
   * @param run the run's state, whose totals are the trace's
   * @param terminal the run's terminal, or null when it ended without one
   * @param crash the {@link RuntimeException} that left the run without a terminal, or null
   */
  void finish(SessionState run, ResultMessage terminal, RuntimeException crash) {
    try {
      try {
        listener.onTrace(trace(run, terminal, crash));
      } catch (Exception e) {
        LOGGER.log(
            Level.WARNING,
            "the trace of run "
                + run.runId()
                + " of session "
                + sessionId
                + " failed; the run's result is unchanged",
            forLog(e));
      }
    } catch (Error e) {
      ending.fail(e);
    }
  }

  /** {@code failure}'s redacted copy, or its class name when it cannot be read. */
  private Throwable forLog(Exception failure) {
    try {
      return SerializedError.redactedForLog(failure, secrets.redactor());
    } catch (RuntimeException unreadable) {
      return new Throwable(failure.getClass().getName());
    }
  }

  private Trace trace(SessionState run, ResultMessage terminal, RuntimeException crash) {
    RunTrace recorded;
    synchronized (runs) {
      recorded = runs.remove(run.runId());
    }
    var end = clock.instant();
    var failure = TraceFailure.of(ending.first(), crash, terminal, secrets);
    var output = terminal instanceof ResultMessage.Success success ? success.result() : null;
    var usage = run.totals().usage();
    var trace =
        recorded
            .finish(end, new RunTrace.TraceEnding(failure.error(), output))
            .withName("run")
            .withModelId(modelId)
            .withSessionId(sessionId)
            .withAttribute("session.id", sessionId)
            .withAttribute("run.id", run.runId())
            .withAttribute("provider", provider)
            .withUsage(usage)
            .withTotalTokens(usage.totalTokens())
            .withCost(run.totals().cost())
            .withError(failure.error());
    if (terminal != null) {
      trace.withAttribute("terminal", terminal.getClass().getSimpleName());
    }
    if (failure.type() != null) {
      trace.withAttribute("error.type", failure.type());
    }
    return trace.build();
  }

  /**
   * A run's error, null for a {@code Success}, and its {@code error.type}: the first host {@link
   * Error} the session recorded, else the crash, each as its redacted {@code kind: message}, else
   * the terminal's simple name, with the {@code kind: message} of the error it carries.
   */
  private record TraceFailure(String error, String type) {

    static TraceFailure of(
        Optional<Error> host,
        RuntimeException crash,
        ResultMessage terminal,
        SecretRegistry secrets) {
      Throwable thrown = host.isPresent() ? host.get() : crash;
      if (thrown != null) {
        return new TraceFailure(described(thrown, secrets), thrown.getClass().getName());
      }
      var name = terminal.getClass().getSimpleName();
      return switch (terminal) {
        case ResultMessage.Success _ -> new TraceFailure(null, null);
        case ResultMessage.ErrorDuringExecution failed ->
            new TraceFailure(name + ": " + described(failed.error()), name);
        case ResultMessage.ErrorTransientStream failed ->
            new TraceFailure(name + ": " + described(failed.error()), name);
        default -> new TraceFailure(name, name);
      };
    }

    /**
     * {@code thrown}'s redacted {@code kind: message}, or its class name when that cannot be read.
     */
    private static String described(Throwable thrown, SecretRegistry secrets) {
      try {
        return described(SerializedError.of(thrown).redactedBy(secrets.redactor()));
      } catch (RuntimeException unreadable) {
        return thrown.getClass().getName();
      }
    }

    private static String described(SerializedError error) {
      return error.kind() + ": " + error.message();
    }
  }
}
