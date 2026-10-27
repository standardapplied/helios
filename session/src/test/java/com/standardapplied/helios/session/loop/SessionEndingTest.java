/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.session.AgentSessionImpl;
import com.standardapplied.helios.session.test.LogCapture;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * The session's ending: its one cancellation path, a recorded {@link Error} cancelling the session,
 * the first recorded {@code Error} settling it with every later one suppressed once, and an {@code
 * Error} after the seal logged and nothing else.
 */
final class SessionEndingTest {

  private final SessionState state =
      new SessionState("sess-ending", new CancellationToken(), Clock.systemUTC());
  private final SessionEnding ending = state.ending();

  @Test
  void aRecordedErrorCancelsTheSessionWithAReasonNamingItsClass() {
    ending.fail(new StackOverflowError("deep"));

    assertEquals(
        Optional.of("host error java.lang.StackOverflowError"), state.cancellation().reason());
  }

  @Test
  void theFirstRecordedErrorSettlesWithEveryLaterOneSuppressedOnce() {
    var first = new AssertionError("first");
    var second = new AssertionError("second");
    var third = new OutOfMemoryError("third");

    ending.fail(first);
    ending.fail(second);
    ending.fail(first);
    ending.fail(third);
    ending.fail(second);

    assertSame(first, ending.seal().orElseThrow());
    assertEquals(List.of(second, third), List.of(first.getSuppressed()));
  }

  @Test
  void anErrorAfterTheSealIsLoggedSevereAndChangesNothing() {
    var first = new AssertionError("first");
    var late = new AssertionError("late");
    ending.fail(first);
    ending.seal();
    List<LogRecord> logged = new CopyOnWriteArrayList<>();

    try (var ignored = LogCapture.of(AgentSessionImpl.class, logged)) {
      ending.fail(late);
    }

    assertEquals(0, first.getSuppressed().length);
    assertEquals(
        List.of(Level.SEVERE, "an Error reached session sess-ending after it ended", late),
        List.of(
            logged.getFirst().getLevel(),
            logged.getFirst().getMessage(),
            logged.getFirst().getThrown()));
  }

  @Test
  void anErrorAfterTheSealOfACleanSessionDoesNotCancelIt() {
    assertEquals(Optional.empty(), ending.seal());

    try (var ignored = LogCapture.of(AgentSessionImpl.class, new CopyOnWriteArrayList<>())) {
      ending.fail(new AssertionError("late"));
    }

    assertFalse(state.cancellation().isCancelled());
  }

  @Test
  void anErrorAJoinedOrAwaitedFutureCarriesIsRecordedAsItself() {
    var joined = new AssertionError("joined");
    var awaited = new AssertionError("awaited");

    assertEquals(
        List.of(true, true),
        List.of(
            ending.failWith(new CompletionException(joined)),
            ending.failWith(new ExecutionException(awaited))));

    assertSame(joined, ending.seal().orElseThrow());
    assertEquals(List.of(awaited), List.of(joined.getSuppressed()));
  }

  @Test
  void anExceptionThatCarriesNoErrorIsNotRecorded() {
    assertEquals(
        List.of(false, false),
        List.of(
            ending.failWith(new CompletionException(new IllegalStateException("plain"))),
            ending.failWith(new IllegalStateException(new AssertionError("not a future's")))));

    assertFalse(state.cancellation().isCancelled());
    assertEquals(Optional.empty(), ending.seal());
  }

  @Test
  void aNullThrowableIsRejected() {
    var ex = assertThrows(NullPointerException.class, () -> ending.failWith(null));

    assertEquals("thrown must not be null", ex.getMessage());
  }

  @Test
  void aNullErrorIsRejected() {
    var ex = assertThrows(NullPointerException.class, () -> ending.fail(null));

    assertEquals("error must not be null", ex.getMessage());
  }

  @Test
  void everyCancelReturnsTheFirstOnesFutureAndKeepsItsReason() {
    var first = ending.cancel("session closed");

    assertSame(first, ending.cancel("session ended"));
    assertEquals(Optional.of("session closed"), state.cancellation().reason());
  }

  @Test
  void anErrorACancellationCallbackThrowsIsRecorded() {
    var sentinel = new AssertionError("callback failure");
    state
        .cancellation()
        .onCancel(
            () -> {
              throw sentinel;
            });

    Await.value("the callbacks to have run", ending.cancel("session closed"));

    assertSame(sentinel, ending.seal().orElseThrow());
  }
}
