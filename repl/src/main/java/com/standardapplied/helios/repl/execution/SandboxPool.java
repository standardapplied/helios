/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.execution;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.repl.ReplConfig;
import com.standardapplied.helios.repl.ReplSession;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The provider's live sandboxes: one {@link ReplSession} per Helios session id, at most {@code
 * maxConcurrentSessions} of them. A session holds one permit from its start until it ends; a start
 * that is refused, or whose startup snippet fails, gives its permit back and closes whatever it
 * spawned. A bound session's permit is given back only by whoever removes it from the pool, so a
 * session ended while it starts is never released twice, and a start that finds the pool closed
 * once it has bound its session discards it.
 */
final class SandboxPool {

  private static final Logger LOGGER = Logger.getLogger(JShellExecutionProvider.class.getName());

  private final ReplConfig replConfig;
  private final String startupSnippet;
  private final int maxConcurrentSessions;
  private final Semaphore sessionPermits;
  private final Map<String, ReplSession> sessions = new ConcurrentHashMap<>();
  private volatile boolean closed;

  /**
   * @param replConfig the configuration each session's sandbox is spawned with
   * @param startupSnippet run once on every new sandbox; {@code null} or blank runs nothing
   * @param maxConcurrentSessions the number of sessions that may be live at once
   */
  SandboxPool(ReplConfig replConfig, String startupSnippet, int maxConcurrentSessions) {
    this.replConfig = replConfig;
    this.startupSnippet = startupSnippet;
    this.maxConcurrentSessions = maxConcurrentSessions;
    this.sessionPermits = new Semaphore(maxConcurrentSessions);
  }

  /**
   * Spawn and register a sandbox for {@code ctx}, run the startup snippet on it, and end the
   * session when {@code ctx}'s cancellation fires — defense in depth for a host that never calls
   * {@code onSessionEnd}.
   */
  SessionStartOutcome start(SessionContext ctx) {
    var sessionId = ctx.sessionId();
    if (sessions.containsKey(sessionId)) {
      return alreadyBound(sessionId);
    }
    if (!sessionPermits.tryAcquire()) {
      return SessionStartOutcome.refuse(
          "JShell session pool saturated (cap=" + maxConcurrentSessions + ")");
    }
    ReplSession session;
    try {
      // ReplSession.create takes a Semaphore for its own concurrency accounting; a fresh
      // single-permit semaphore lets it release on close without touching the pool's permits.
      session = ReplSession.create(replConfig, new Semaphore(1));
    } catch (RuntimeException e) {
      sessionPermits.release();
      return SessionStartOutcome.refuse(
          "failed to spawn JShell sandbox for session " + sessionId + ": " + e.getMessage(), e);
    }
    if (sessions.putIfAbsent(sessionId, session) != null) {
      safeClose(session);
      sessionPermits.release();
      return alreadyBound(sessionId);
    }
    if (closed) {
      discard(sessionId, session);
      return SessionStartOutcome.refuse("provider is closed");
    }
    var startup = runStartupSnippet(sessionId, session);
    if (startup instanceof SessionStartOutcome.Accept) {
      ctx.cancellation().onCancel(() -> end(sessionId));
    }
    return startup;
  }

  /** Close the session's sandbox and give back its permit; an unknown id is ignored. */
  void end(String sessionId) {
    var session = sessions.remove(sessionId);
    if (session != null) {
      safeClose(session);
      sessionPermits.release();
    }
  }

  /** The live session for {@code sessionId}, or {@code null}. */
  ReplSession session(String sessionId) {
    return sessions.get(sessionId);
  }

  int liveCount() {
    return sessions.size();
  }

  int maxConcurrentSessions() {
    return maxConcurrentSessions;
  }

  /** End every live session, and discard any that a start binds afterwards. */
  void close() {
    closed = true;
    sessions.keySet().forEach(this::end);
  }

  /** Close {@code session}, logging rather than throwing a failure to close. */
  static void safeClose(ReplSession session) {
    try {
      session.close();
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "failed to close ReplSession", e);
    }
  }

  private SessionStartOutcome runStartupSnippet(String sessionId, ReplSession session) {
    if (Strings.isBlank(startupSnippet)) {
      return SessionStartOutcome.accept();
    }
    try {
      var result = session.execute(startupSnippet);
      if (result.exitCode() == 0) {
        return SessionStartOutcome.accept();
      }
      var detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
      discard(sessionId, session);
      return SessionStartOutcome.refuse(
          "JShell startup snippet failed for session "
              + sessionId
              + " (exit="
              + result.exitCode()
              + "): "
              + detail);
    } catch (RuntimeException e) {
      discard(sessionId, session);
      return SessionStartOutcome.refuse(
          "JShell startup snippet failed for session " + sessionId + ": " + e.getMessage(), e);
    }
  }

  private void discard(String sessionId, ReplSession session) {
    var stillBound = sessions.remove(sessionId, session);
    safeClose(session);
    if (stillBound) {
      sessionPermits.release();
    }
  }

  private static SessionStartOutcome alreadyBound(String sessionId) {
    return SessionStartOutcome.refuse(
        "session " + sessionId + " already has a JShell sandbox bound");
  }
}
