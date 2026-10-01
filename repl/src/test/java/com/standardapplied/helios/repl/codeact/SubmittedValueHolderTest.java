/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class SubmittedValueHolderTest {

  @Test
  void emptyByDefault() {
    var h = new SubmittedValueHolder();
    assertFalse(h.isSubmitted());
    assertTrue(h.peek().isEmpty());
  }

  @Test
  void submitStoresValueAndFlipsState() {
    var h = new SubmittedValueHolder();
    var value = "result";
    assertTrue(h.submit(value));
    assertTrue(h.isSubmitted());
    assertSame(value, h.peek().orElseThrow());
  }

  @Test
  void secondSubmitReturnsFalseAndKeepsFirstValue() {
    var h = new SubmittedValueHolder();
    h.submit("first");
    assertFalse(h.submit("second"));
    assertSame("first", h.peek().orElseThrow());
  }

  @Test
  void submitRejectsNull() {
    var h = new SubmittedValueHolder();
    var ex = assertThrows(NullPointerException.class, () -> h.submit(null));
    assertEquals("value must not be null", ex.getMessage());
  }

  @Test
  void concurrentSubmitsKeepExactlyOneWinner() throws Exception {
    var h = new SubmittedValueHolder();
    var threads = 16;
    var start = new CountDownLatch(1);
    var done = new CountDownLatch(threads);
    var winners = new AtomicInteger();
    for (var i = 0; i < threads; i++) {
      final var value = "v" + i;
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  start.await();
                  if (h.submit(value)) {
                    winners.incrementAndGet();
                  }
                } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }
    start.countDown();
    done.await();
    assertEquals(1, winners.get(), "exactly one submit must win the race");
    assertTrue(h.isSubmitted());
    assertTrue(((String) h.peek().orElseThrow()).startsWith("v"));
  }
}
