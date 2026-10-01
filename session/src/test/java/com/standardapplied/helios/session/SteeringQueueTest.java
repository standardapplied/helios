/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SteeringQueueTest {

  @Test
  void capacityIsExposed() {
    var q = new SteeringQueue(10);
    assertEquals(10, q.capacity());
  }

  @Test
  void constructorRejectsZeroCapacity() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new SteeringQueue(0));
    assertEquals("capacity must be positive, got 0", ex.getMessage());
  }

  @Test
  void constructorRejectsNegativeCapacity() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new SteeringQueue(-5));
    assertEquals("capacity must be positive, got -5", ex.getMessage());
  }

  @Test
  void freshQueueIsEmpty() {
    var q = new SteeringQueue(4);
    assertEquals(0, q.size());
    assertTrue(q.drain().isEmpty());
  }

  @Test
  void offerAcceptsBelowCapacity() {
    var q = new SteeringQueue(2);
    assertTrue(q.offer(UserMessage.text("a")));
    assertTrue(q.offer(UserMessage.text("b")));
    assertEquals(2, q.size());
  }

  @Test
  void offerReturnsFalseWhenFull() {
    var q = new SteeringQueue(1);
    assertTrue(q.offer(UserMessage.text("a")));
    assertFalse(q.offer(UserMessage.text("b")));
    assertEquals(1, q.size());
  }

  @Test
  void offerRejectsNullMessage() {
    var q = new SteeringQueue(4);
    var ex = assertThrows(NullPointerException.class, () -> q.offer(null));
    assertEquals("message must not be null", ex.getMessage());
  }

  @Test
  void drainReturnsMessagesInFifoOrderAndClearsQueue() {
    var q = new SteeringQueue(4);
    q.offer(UserMessage.text("one"));
    q.offer(UserMessage.text("two"));
    q.offer(UserMessage.text("three"));

    var drained = q.drain();

    assertEquals(List.of("one", "two", "three"), drained.stream().map(UserMessage::text).toList());
    assertEquals(0, q.size());
    assertTrue(q.drain().isEmpty(), "second drain must yield empty");
  }

  @Test
  void drainAndOfferInterleave() {
    var q = new SteeringQueue(2);
    q.offer(UserMessage.text("a"));
    assertEquals(1, q.drain().size());
    assertTrue(q.offer(UserMessage.text("b")));
    assertTrue(q.offer(UserMessage.text("c")));
    assertEquals(2, q.size());
    assertFalse(q.offer(UserMessage.text("d")));
    var second = q.drain();
    assertEquals(List.of("b", "c"), second.stream().map(UserMessage::text).toList());
  }

  @Test
  void concurrentProducersRespectCapacityAndDontLoseMessages() throws Exception {
    int capacity = 1_000;
    int producers = 16;
    int perProducer = 200;
    var q = new SteeringQueue(capacity);
    var start = new CountDownLatch(1);
    var accepted = new AtomicInteger();
    var rejected = new AtomicInteger();

    try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int p = 0; p < producers; p++) {
        final int pid = p;
        exec.submit(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              for (int i = 0; i < perProducer; i++) {
                if (q.offer(UserMessage.text("p" + pid + ":" + i))) {
                  accepted.incrementAndGet();
                } else {
                  rejected.incrementAndGet();
                }
              }
            });
      }
      start.countDown();
      exec.shutdown();
      assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS), "producers must finish");
    }

    int total = producers * perProducer;
    assertEquals(total, accepted.get() + rejected.get(), "every offer is accounted for");
    assertEquals(Math.min(capacity, total), accepted.get(), "accepted = min(capacity, total)");

    var drained = q.drain();
    assertEquals(accepted.get(), drained.size(), "drain returns every accepted message");

    var unique = new HashSet<String>();
    for (var m : drained) {
      assertTrue(unique.add(m.text()), "no duplicates in drained batch: " + m.text());
    }
  }
}
