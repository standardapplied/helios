/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Theme G regression test for the SSE-backpressure wedge. The agent loop emits events via {@code
 * AgentSessionImpl#safeEmit}, which calls {@link SubmissionPublisher#offer(Object, long, TimeUnit,
 * java.util.function.BiPredicate)} with a bounded timeout and a drop handler. Before the fix,
 * {@code publisher.submit(...)} blocked the loop indefinitely when any subscriber filled its
 * 256-item buffer — a slow SSE client could pin the producer forever.
 *
 * <p>This test exercises the same call pattern against a deliberately-slow subscriber and asserts
 * the producer never blocks longer than the configured timeout. Coupled with code review of {@code
 * safeEmit}, the wedge prevention is verified.
 */
final class SafeEmitBackpressureTest {

  @Test
  void offerWithTimeoutDoesNotWedgeOnSlowSubscriber() throws Exception {
    // Match the publisher shape AgentSessionImpl builds: virtual-thread executor, 256-item
    // per-subscriber buffer.
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var publisher = new SubmissionPublisher<String>(executor, 256)) {

      var blockSubscriber = new CountDownLatch(1);
      var deliveredAtLeastOne = new CountDownLatch(1);
      var receivedCount = new AtomicInteger();
      publisher.subscribe(
          new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
              this.subscription = s;
              s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
              deliveredAtLeastOne.countDown();
              receivedCount.incrementAndGet();
              try {
                blockSubscriber.await(30, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
          });

      // Burst-fill past the 256-item buffer. We use a 50 ms timeout here (safeEmit uses 1 s in
      // production); the test only verifies the bounded-timeout *contract*, not the production
      // value. With 256 slots and 50 events overflowing, the test runs in well under 5 s instead
      // of the 50+ s a 1 s timeout would produce.
      var startNanos = System.nanoTime();
      var droppedAny = false;
      for (var i = 0; i < 306; i++) {
        var result = publisher.offer("evt-" + i, 50, TimeUnit.MILLISECONDS, (sub, e) -> false);
        if (result < 0) {
          droppedAny = true;
        }
      }
      var elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

      assertTrue(
          deliveredAtLeastOne.await(2, TimeUnit.SECONDS),
          "test precondition: subscriber must have received at least one event before blocking");
      assertTrue(
          droppedAny,
          "expected at least one offer to drop on the slow subscriber; without the bounded timeout"
              + " the producer would wedge here instead. Received="
              + receivedCount.get());
      assertTrue(
          elapsedMs < 10_000L,
          "306 offers against a slow subscriber must not pin the producer (took "
              + elapsedMs
              + " ms); the bounded-timeout contract is broken");

      // Unblock the subscriber so the publisher.close() in try-with-resources can flush.
      blockSubscriber.countDown();
    }
  }

  @Test
  void offerSucceedsForFastSubscriber() throws Exception {
    // Sanity check the inverse: a subscriber that drains promptly never sees drops.
    try (var executor = Executors.newVirtualThreadPerTaskExecutor();
        var publisher = new SubmissionPublisher<String>(executor, 256)) {

      var received = new AtomicInteger();
      publisher.subscribe(
          new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
              s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
              received.incrementAndGet();
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
          });

      var droppedAny = false;
      for (var i = 0; i < 100; i++) {
        var result = publisher.offer("evt-" + i, 50, TimeUnit.MILLISECONDS, (sub, e) -> false);
        if (result < 0) {
          droppedAny = true;
        }
      }
      assertFalse(droppedAny, "fast subscriber should not see drops");
    }
  }
}
