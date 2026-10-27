/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.Uninterruptible;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.UserMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A {@link RegisteredSession} records the runs its sends start in the order the session started
 * them, and publishes a run's end and the time it became idle together, under the table's lock.
 */
final class RegisteredSessionTest {

  private static final Duration RETENTION = Duration.ofMinutes(15);

  private final Object lock = new Object();
  private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
  private final StubSession stub = new StubSession("s1");
  private final RegisteredSession registered =
      new RegisteredSession(stub, new Principal("acme", "alice"), lock, now::get, () -> {});

  @Test
  void aSendThatStartsALaterRunWaitsForAnEarlierSendToRecordItsRun() {
    var first = stub.result;
    var paused = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    stub.onSend =
        () -> {
          paused.countDown();
          Uninterruptible.await(resume);
        };
    var firstSender = Thread.ofVirtual().start(() -> registered.send(UserMessage.text("one")));
    Await.latch("the first send to start its run", paused);
    stub.onSend = () -> {};
    first.complete(stub.success());
    var second = new CompletableFuture<ResultMessage>();
    stub.result = second;

    var secondSender = Thread.ofVirtual().start(() -> registered.send(UserMessage.text("two")));
    Await.until(
        "the second send to wait for the first",
        () -> secondSender.getState() == Thread.State.BLOCKED);
    resume.countDown();
    Await.termination("the first send", firstSender);
    Await.termination("the second send", secondSender);

    assertSame(second, registered.latest());
    synchronized (lock) {
      assertFalse(registered.isIdle());
    }
  }

  @Test
  void aRunThatCompletedButHasNotRecordedItsEndKeepsTheSessionFromExpiring() {
    registered.send(UserMessage.text("go"));
    now.set(now.get().plus(Duration.ofHours(1)));

    Thread completing;
    synchronized (lock) {
      completing = Thread.ofVirtual().start(stub::terminate);
      Await.until("the run to complete", stub.result::isDone);

      assertFalse(registered.expired(now.get(), RETENTION));
    }
    Await.termination("the run's end to be recorded", completing);
    synchronized (lock) {
      assertFalse(registered.expired(now.get(), RETENTION));
      now.set(now.get().plus(RETENTION));
      assertTrue(registered.expired(now.get(), RETENTION));
    }
  }
}
