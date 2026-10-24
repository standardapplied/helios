/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class CancellationTokenTest {

  @Test
  void freshTokenIsNotCancelled() {
    var t = new CancellationToken();
    assertFalse(t.isCancelled());
    assertEquals(Optional.empty(), t.reason());
  }

  @Test
  void cancelTransitionsToCancelledAndRecordsReason() {
    var t = new CancellationToken();
    assertTrue(t.cancel("user-stop"));
    assertTrue(t.isCancelled());
    assertEquals(Optional.of("user-stop"), t.reason());
  }

  @Test
  void secondCancelReturnsFalseAndPreservesFirstReason() {
    var t = new CancellationToken();
    assertTrue(t.cancel("first"));
    assertFalse(t.cancel("second"));
    assertEquals(Optional.of("first"), t.reason());
  }

  @Test
  void cancelNullReasonThrowsNullPointerException() {
    var t = new CancellationToken();
    var ex = assertThrows(NullPointerException.class, () -> t.cancel(null));
    assertEquals("reason must not be null", ex.getMessage());
    assertFalse(t.isCancelled(), "failed cancel must not flip state");
  }

  @Test
  void cancelBlankReasonThrowsIllegalArgumentException() {
    var t = new CancellationToken();
    var ex = assertThrows(IllegalArgumentException.class, () -> t.cancel("   "));
    assertEquals("reason must not be blank", ex.getMessage());
    assertFalse(t.isCancelled(), "failed cancel must not flip state");
  }

  @Test
  void cancelEmptyReasonThrowsIllegalArgumentException() {
    var t = new CancellationToken();
    assertThrows(IllegalArgumentException.class, () -> t.cancel(""));
  }

  @Test
  void throwIfCancelledIsSilentBeforeCancel() {
    new CancellationToken().throwIfCancelled();
  }

  @Test
  void throwIfCancelledRaisesCancellationExceptionWithReasonAfterCancel() {
    var t = new CancellationToken();
    t.cancel("user-stop");
    var ex = assertThrows(CancellationException.class, t::throwIfCancelled);
    assertEquals("user-stop", ex.getMessage());
  }

  @Test
  void concurrentCancelsExactlyOneWins() throws Exception {
    var t = new CancellationToken();
    var threadCount = 32;
    var ready = new CountDownLatch(threadCount);
    var start = new CountDownLatch(1);
    var winners = new AtomicInteger(0);
    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      var cancellers = new ArrayList<Future<Void>>();
      for (int i = 0; i < threadCount; i++) {
        final int id = i;
        cancellers.add(
            exec.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  if (t.cancel("thread-" + id)) {
                    winners.incrementAndGet();
                  }
                  return null;
                }));
      }
      Await.latch("every thread to arrive at the start barrier", ready);
      start.countDown();
      cancellers.forEach(canceller -> Await.value("a concurrent cancel", canceller));
    }
    assertEquals(1, winners.get(), "exactly one cancel must report winning the race");
    assertTrue(t.isCancelled());
    assertTrue(t.reason().orElseThrow().startsWith("thread-"));
  }

  // ── onCancel ─────────────────────────────────────────────────────────────

  @Test
  void onCancelFiresWhenTokenCancels() {
    var t = new CancellationToken();
    var fired = new AtomicInteger();
    t.onCancel(fired::incrementAndGet);
    assertEquals(0, fired.get(), "callback must not fire on registration when not cancelled");
    t.cancel("now");
    assertEquals(1, fired.get(), "callback must fire exactly once on cancel");
  }

  @Test
  void onCancelFiresImmediatelyIfAlreadyCancelled() {
    var t = new CancellationToken();
    t.cancel("already");
    var fired = new AtomicInteger();
    t.onCancel(fired::incrementAndGet);
    assertEquals(
        1, fired.get(), "callback must fire synchronously when token is already cancelled");
  }

  @Test
  void onCancelFiresEveryRegisteredCallbackOnCancel() {
    var t = new CancellationToken();
    var a = new AtomicInteger();
    var b = new AtomicInteger();
    t.onCancel(a::incrementAndGet);
    t.onCancel(b::incrementAndGet);
    t.cancel("flush");
    assertEquals(1, a.get());
    assertEquals(1, b.get());
  }

  @Test
  void onCancelDoesNotFireForSubsequentCancelCalls() {
    var t = new CancellationToken();
    var fired = new AtomicInteger();
    t.onCancel(fired::incrementAndGet);
    t.cancel("first");
    t.cancel("second");
    assertEquals(1, fired.get(), "callback must fire exactly once across multiple cancel attempts");
  }

  @Test
  void onCancelThrowingCallbackDoesNotPreventOthers() {
    var t = new CancellationToken();
    var good = new AtomicInteger();
    t.onCancel(
        () -> {
          throw new RuntimeException("boom");
        });
    t.onCancel(good::incrementAndGet);
    t.cancel("flush");
    assertEquals(1, good.get(), "subsequent callback must run even when an earlier one throws");
  }

  @Test
  void onCancelRejectsNullCallback() {
    var t = new CancellationToken();
    assertThrows(NullPointerException.class, () -> t.onCancel(null));
  }

  // ── Registration deregistration ──────────────────────────────────────────

  @Test
  void registrationRemovePreventsCallbackFromFiring() {
    var t = new CancellationToken();
    var fired = new AtomicInteger();
    var registration = t.onCancel(fired::incrementAndGet);
    registration.remove();
    t.cancel("flush");
    assertEquals(0, fired.get(), "removed callback must not fire on cancel");
  }

  @Test
  void registrationRemoveIsIdempotent() {
    var t = new CancellationToken();
    var registration = t.onCancel(() -> {});
    registration.remove();
    registration.remove(); // second remove must not throw
    t.cancel("flush");
  }

  @Test
  void registrationRemoveAfterFireIsSilentNoop() {
    var t = new CancellationToken();
    var registration = t.onCancel(() -> {});
    t.cancel("now");
    registration.remove(); // post-fire remove must not throw
  }

  @Test
  void alreadyCancelledTokenReturnsNoopRegistration() {
    var t = new CancellationToken();
    t.cancel("already");
    var fired = new AtomicInteger();
    var registration = t.onCancel(fired::incrementAndGet);
    assertEquals(1, fired.get(), "immediate-fire branch must run the callback");
    // The returned NOOP registration's remove is a safe no-op
    registration.remove();
  }

  @Test
  void manyRegisterRemovePairsDoNotAccumulateCallbacks() {
    // The leak the Phase 5/6 review identified: every per-call onCancel(...) used to leave a
    // dead callback behind in the long-lived session token. With Registration.remove() each
    // caller can detach, so a thousand executes don't accumulate a thousand inert callbacks.
    var t = new CancellationToken();
    for (var i = 0; i < 1000; i++) {
      var registration = t.onCancel(() -> {});
      registration.remove();
    }
    var fired = new AtomicInteger();
    t.onCancel(fired::incrementAndGet);
    t.cancel("flush");
    assertEquals(1, fired.get(), "only the lone retained callback must fire");
  }

  @Test
  void noopRegistrationRemoveDoesNothing() {
    // The NOOP constant is reused for two paths (already-cancelled and lost-the-race). Calling
    // remove() must be safe regardless of how the caller obtained it.
    CancellationToken.Registration.NOOP.remove();
    CancellationToken.Registration.NOOP.remove();
  }

  @Test
  void activeCallbackCountTracksRegisterAndRemove() {
    // Theme G regression test: SessionQuestionGateway used to drop the Registration returned by
    // onCancel(), accumulating one callback per AskUserQuestion call over the session's lifetime.
    // The fix captures the Registration and calls remove() in finally. This test exercises the
    // capture-and-remove pattern N times directly against the token and asserts the count returns
    // to zero each cycle — proving the leak-prevention contract holds.
    var t = new CancellationToken();
    assertEquals(0, t.activeCallbackCountForTests(), "fresh token has no callbacks");

    for (var i = 0; i < 100; i++) {
      var reg = t.onCancel(() -> {});
      assertEquals(1, t.activeCallbackCountForTests(), "callback present mid-cycle");
      reg.remove();
      assertEquals(
          0,
          t.activeCallbackCountForTests(),
          "callback removed at end of cycle; if missing, registrations accumulate and the"
              + " long-lived per-session token leaks closures across every per-call ask / tool"
              + " dispatch");
    }
  }

  @Test
  void activeCallbackCountIsZeroAfterFire() {
    // After cancel() fires, the callbacks list is cleared. The count accessor reflects that, so
    // a leak test against the token can run pre-cancel without false positives.
    var t = new CancellationToken();
    t.onCancel(() -> {});
    t.onCancel(() -> {});
    assertEquals(2, t.activeCallbackCountForTests());
    t.cancel("done");
    assertEquals(0, t.activeCallbackCountForTests(), "list cleared after fire");
  }

  // ── Linearization contract ───────────────────────────────────────────────

  @Test
  void cancelRunsALaterCallbackWhileAnEarlierOneBlocks() throws Exception {
    var t = new CancellationToken();
    var release = new CountDownLatch(1);
    var later = new CountDownLatch(1);
    t.onCancel(() -> Await.latch("the test to release the blocking callback", release));
    t.onCancel(later::countDown);

    var canceller = Thread.ofVirtual().start(() -> t.cancel("host"));
    Await.latch("the later callback to run while the earlier one blocks", later);
    assertTrue(canceller.isAlive(), "cancel returns only once every callback has run");

    release.countDown();
    Await.termination("the cancelling thread", canceller);
  }

  @Test
  void registerAndRemoveWhileCancelIsMidFireObeyContract() throws Exception {
    // Callback A blocks the cancel outside the lock. While it blocks the token is already
    // cancelled, so a new registration must fire immediately on the registering thread, and
    // removing an already-claimed registration must be a no-op that still lets it fire.
    var t = new CancellationToken();
    var midFire = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var claimedFired = new AtomicInteger();
    var removedBeforeCancelFired = new AtomicInteger();
    var lateFired = new AtomicInteger();
    t.onCancel(
        () -> {
          midFire.countDown();
          Await.latch("the test to release callback A", release);
        });
    var claimed = t.onCancel(claimedFired::incrementAndGet);
    t.onCancel(removedBeforeCancelFired::incrementAndGet).remove();

    var canceller = Thread.ofVirtual().start(() -> t.cancel("mid-fire"));
    Await.latch("the cancelling thread to reach callback A", midFire);

    var late = t.onCancel(lateFired::incrementAndGet);
    assertSame(CancellationToken.Registration.NOOP, late);
    assertEquals(1, lateFired.get(), "registration after cancellation fires on the caller");
    claimed.remove();
    assertEquals(0, t.activeCallbackCountForTests(), "claimed registrations already drained");

    release.countDown();
    Await.termination("the cancelling thread", canceller);
    assertEquals(1, claimedFired.get(), "remove after claim is a no-op; callback still fires once");
    assertEquals(0, removedBeforeCancelFired.get(), "remove before cancel never fires");
    assertEquals(1, lateFired.get());
  }

  @Test
  void reentrantRegisterRemoveAndCancelFromCallbackDoNotDeadlock() throws Exception {
    var t = new CancellationToken();
    var nested = new AtomicInteger();
    var secondCancel = new AtomicReference<Boolean>();
    var self = new AtomicReference<CancellationToken.Registration>();
    self.set(
        t.onCancel(
            () -> {
              self.get().remove();
              t.onCancel(nested::incrementAndGet);
              secondCancel.set(t.cancel("reentrant"));
            }));
    var canceller = Thread.ofVirtual().start(() -> t.cancel("outer"));
    Await.termination("the cancel whose callback re-enters the token", canceller);
    assertEquals(1, nested.get(), "nested registration fires immediately");
    assertEquals(Boolean.FALSE, secondCancel.get(), "reentrant cancel loses to the outer one");
    assertEquals(Optional.of("outer"), t.reason());
  }

  @Test
  void errorFromCallbackRunsRemainingCallbacksThenEscapes() {
    var t = new CancellationToken();
    var first = new AssertionError("first");
    var second = new AssertionError("second");
    var cleanup = new AtomicInteger();
    t.onCancel(
        () -> {
          throw first;
        });
    t.onCancel(
        () -> {
          throw new IllegalStateException("ordinary");
        });
    t.onCancel(
        () -> {
          throw second;
        });
    t.onCancel(cleanup::incrementAndGet);
    var thrown = assertThrows(AssertionError.class, () -> t.cancel("host-failure"));
    var suppressed = List.of(thrown.getSuppressed());
    assertEquals(Set.of(first, second), Set.of(thrown, suppressed.getFirst()));
    assertEquals(1, suppressed.size());
    assertEquals(1, cleanup.get(), "cleanup after a throwing callback must still run");
    assertTrue(t.isCancelled(), "state transition precedes callback dispatch");
    assertEquals(0, t.activeCallbackCountForTests());
  }

  @Test
  void repeatedErrorInstanceDoesNotSkipRemainingCallbacks() {
    var t = new CancellationToken();
    var first = new AssertionError("shared");
    var second = new AssertionError("distinct");
    var failures = new AtomicInteger();
    var cleanup = new AtomicInteger();
    for (var error : List.of(first, first, second, first)) {
      t.onCancel(
          () -> {
            failures.incrementAndGet();
            throw error;
          });
    }
    t.onCancel(cleanup::incrementAndGet);

    var thrown = assertThrows(AssertionError.class, () -> t.cancel("host-failure"));

    var suppressed = List.of(thrown.getSuppressed());
    assertEquals(1, suppressed.size());
    assertEquals(Set.of(first, second), Set.of(thrown, suppressed.getFirst()));
    assertEquals(4, failures.get(), "every throwing callback must run exactly once");
    assertEquals(1, cleanup.get(), "repeated Error instances must not skip cleanup");
    assertEquals(Optional.of("host-failure"), t.reason());
    assertEquals(0, t.activeCallbackCountForTests());
    assertFalse(t.cancel("retry"), "the failed dispatch must not undo cancellation");
    assertEquals(4, failures.get());
    assertEquals(1, cleanup.get());
  }

  @Test
  void cancelSuppressesAnErrorThrownTwiceOnceAndNeverInItself() {
    var t = new CancellationToken();
    var first = new AssertionError("first");
    var repeated = new AssertionError("repeated");
    var firstThread = new CompletableFuture<Thread>();
    t.onCancel(
        () -> {
          firstThread.complete(Thread.currentThread());
          throw first;
        });
    for (var i = 0; i < 2; i++) {
      t.onCancel(
          () -> {
            Await.termination("the first error's thread", Await.value("it", firstThread));
            throw repeated;
          });
    }

    var thrown = assertThrows(AssertionError.class, () -> t.cancel("host-failure"));

    assertSame(first, thrown);
    assertEquals(List.of(repeated), List.of(thrown.getSuppressed()));
  }

  @Test
  void cancelThrowsAnErrorEveryCallbackThrewWithNothingSuppressed() {
    var t = new CancellationToken();
    var shared = new AssertionError("shared");
    for (var i = 0; i < 2; i++) {
      t.onCancel(
          () -> {
            throw shared;
          });
    }

    var thrown = assertThrows(AssertionError.class, () -> t.cancel("host-failure"));

    assertSame(shared, thrown);
    assertEquals(0, thrown.getSuppressed().length);
  }

  @Test
  void errorFromImmediateFireEscapesToRegisteringThread() {
    var t = new CancellationToken();
    t.cancel("already");
    assertThrows(
        AssertionError.class,
        () ->
            t.onCancel(
                () -> {
                  throw new AssertionError("immediate");
                }));
  }

  @Test
  void racingRegisterCancelAndRemoveNeverMissOrDuplicate() throws Exception {
    // Three threads per round leave a common barrier together: one registers a callback it keeps,
    // one removes a callback registered before the round, one cancels. The kept callback must fire
    // exactly once whichever side of the cancel it lands on; the removed one at most once.
    var rounds = 2_000;
    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var round = 0; round < rounds; round++) {
        var t = new CancellationToken();
        var kept = new AtomicInteger();
        var removed = new AtomicInteger();
        var toRemove = t.onCancel(removed::incrementAndGet);
        var barrier = new CyclicBarrier(3);
        var register =
            exec.submit(
                () -> {
                  barrier.await();
                  t.onCancel(kept::incrementAndGet);
                  return null;
                });
        var remove =
            exec.submit(
                () -> {
                  barrier.await();
                  toRemove.remove();
                  return null;
                });
        var cancel =
            exec.submit(
                () -> {
                  barrier.await();
                  t.cancel("race");
                  return null;
                });
        Await.value("the racing registration", register);
        Await.value("the racing removal", remove);
        Await.value("the racing cancel", cancel);
        assertEquals(1, kept.get(), "round " + round + ": kept callback fired " + kept.get());
        assertTrue(removed.get() <= 1, "round " + round + ": removed callback fired twice");
        assertEquals(0, t.activeCallbackCountForTests(), "round " + round + " retained callbacks");
      }
    }
  }

  // ── cancelAsync ───────────────────────────────────────────────────────────

  @Test
  void cancelAsyncCancelsTheTokenBeforeItReturnsWhileACallbackStillRuns() {
    var token = new CancellationToken();
    var release = new CountDownLatch(1);
    token.onCancel(() -> Await.latch("the callback released", release));

    var fired = token.cancelAsync("closed", error -> {});

    assertEquals(Optional.of("closed"), token.reason());
    assertFalse(fired.isDone(), "the callback is still running");
    release.countDown();
    Await.value("the callbacks to have run", fired);
  }

  @Test
  void cancelAsyncWithNoCallbackReturnsACompletedFuture() {
    var token = new CancellationToken();

    var fired = token.cancelAsync("closed", error -> {});

    assertTrue(fired.isDone(), "nothing to run");
    assertEquals(Optional.of("closed"), token.reason());
  }

  @Test
  void cancelAsyncRunsEachCallbackOnAVirtualThreadOfItsOwn() {
    var token = new CancellationToken();
    var first = new CompletableFuture<Thread>();
    var second = new CompletableFuture<Thread>();
    token.onCancel(() -> first.complete(Thread.currentThread()));
    token.onCancel(() -> second.complete(Thread.currentThread()));

    Await.value("the callbacks to have run", token.cancelAsync("closed", error -> {}));

    var threads = List.of(first.join(), second.join());
    assertTrue(threads.stream().allMatch(Thread::isVirtual), "callbacks run on virtual threads");
    assertFalse(threads.get(0) == threads.get(1), "each callback has a thread of its own");
    assertFalse(threads.contains(Thread.currentThread()), "not on the caller's thread");
    assertEquals("helios-cancellation-callback", threads.get(0).getName());
  }

  @Test
  void cancelAsyncRunsALaterCallbackWhileAnEarlierOneBlocks() {
    var token = new CancellationToken();
    var release = new CountDownLatch(1);
    var later = new CountDownLatch(1);
    token.onCancel(() -> Await.latch("the earlier callback released", release));
    token.onCancel(later::countDown);

    var fired = token.cancelAsync("closed", error -> {});

    try {
      Await.latch("the later callback to run", later);
      assertFalse(fired.isDone(), "the earlier callback still blocks");
    } finally {
      release.countDown();
    }
    Await.value("the callbacks to have run", fired);
  }

  @Test
  void cancelAsyncCompletesOnlyOnceALaterCallbackThatBlocksReturns() {
    var token = new CancellationToken();
    var earlier = new CompletableFuture<Thread>();
    var release = new CountDownLatch(1);
    token.onCancel(() -> earlier.complete(Thread.currentThread()));
    token.onCancel(() -> Await.latch("the later callback released", release));

    var fired = token.cancelAsync("closed", error -> {});

    try {
      Await.termination("the earlier callback's thread to end", Await.value("it ran", earlier));
      assertFalse(fired.isDone(), "the later callback still blocks");
    } finally {
      release.countDown();
    }
    Await.value("the callbacks to have run", fired);
  }

  @Test
  void cancelAsyncHandsEachCallbackErrorToOnErrorAndCompletesNormallyOnceEveryCallbackRan() {
    var token = new CancellationToken();
    var first = new AssertionError("first");
    var second = new AssertionError("second");
    var laterCallbacks = new AtomicInteger();
    var errors = new CopyOnWriteArrayList<Error>();
    token.onCancel(
        () -> {
          throw first;
        });
    token.onCancel(
        () -> {
          throw second;
        });
    token.onCancel(laterCallbacks::incrementAndGet);

    Await.value("the callbacks to have run", token.cancelAsync("closed", errors::add));

    assertEquals(Set.of(first, second), Set.copyOf(errors));
    assertEquals(2, errors.size());
    assertEquals(0, first.getSuppressed().length);
    assertEquals(0, second.getSuppressed().length);
    assertEquals(1, laterCallbacks.get());
  }

  @Test
  void cancelAsyncHandsOnErrorAnErrorBeforeALaterCallbackReturns() {
    var token = new CancellationToken();
    var sentinel = new AssertionError("sentinel");
    var release = new CountDownLatch(1);
    var observed = new CompletableFuture<Error>();
    token.onCancel(
        () -> {
          throw sentinel;
        });
    token.onCancel(() -> Await.latch("the callback released", release));

    var fired = token.cancelAsync("closed", observed::complete);

    try {
      assertSame(sentinel, Await.value("the error handed on", observed));
    } finally {
      release.countDown();
      Await.value("the callbacks to have run", fired);
    }
  }

  @Test
  void cancelAsyncRejectsANullOnErrorBeforeCancelling() {
    var token = new CancellationToken();

    assertThrows(NullPointerException.class, () -> token.cancelAsync("closed", null));

    assertFalse(token.isCancelled());
  }

  @Test
  void cancelAsyncOnACancelledTokenKeepsTheFirstReasonAndRunsNothing() {
    var token = new CancellationToken();
    token.cancel("first");
    var fired = new AtomicInteger();
    token.onCancel(fired::incrementAndGet);

    var done = token.cancelAsync("second", error -> {});

    assertTrue(done.isDone(), "nothing left to run");
    assertEquals(Optional.of("first"), token.reason());
    assertEquals(1, fired.get());
  }

  @Test
  void cancelAsyncRejectsABlankReason() {
    var token = new CancellationToken();

    var ex =
        assertThrows(IllegalArgumentException.class, () -> token.cancelAsync(" ", error -> {}));

    assertEquals("reason must not be blank", ex.getMessage());
    assertFalse(token.isCancelled());
  }
}
