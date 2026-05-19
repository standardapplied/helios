/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.fault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  @Test
  void successfulOperationNoRetry() throws Exception {
    var policy = RetryPolicy.newBuilder().withMaxAttempts(3).build();
    var attempts = new AtomicInteger(0);

    var result =
        policy.execute(
            () -> {
              attempts.incrementAndGet();
              return "success";
            });

    assertEquals("success", result);
    assertEquals(1, attempts.get());
  }

  @Test
  void retryUntilSuccess() throws Exception {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var attempts = new AtomicInteger(0);

    var result =
        policy.execute(
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("Fail");
              }
              return "success";
            });

    assertEquals("success", result);
    assertEquals(3, attempts.get());
  }

  @Test
  void exhaustRetries() {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(3)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var attempts = new AtomicInteger(0);

    var exception =
        assertThrows(
            RetryExhaustedException.class,
            () ->
                policy.execute(
                    () -> {
                      attempts.incrementAndGet();
                      throw new RuntimeException("Always fails");
                    }));

    assertEquals(3, attempts.get());
    assertEquals(3, exception.attempts());
    assertInstanceOf(RuntimeException.class, exception.getCause());
  }

  @Test
  void retryOnlySpecificExceptions() {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(IOException.class)
            .build();
    var attempts = new AtomicInteger(0);

    var exception =
        assertThrows(
            RetryExhaustedException.class,
            () ->
                policy.execute(
                    () -> {
                      attempts.incrementAndGet();
                      throw new IllegalArgumentException("Not retryable");
                    }));

    assertEquals(1, attempts.get());
    assertInstanceOf(IllegalArgumentException.class, exception.getCause());
  }

  @Test
  void retryOnSpecificExceptionMatches() throws Exception {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(IOException.class)
            .build();
    var attempts = new AtomicInteger(0);

    var result =
        policy.execute(
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw new IOException("Retryable");
              }
              return "success";
            });

    assertEquals("success", result);
    assertEquals(3, attempts.get());
  }

  @Test
  void retryOnMultipleExceptionTypes() throws Exception {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(IOException.class, IllegalStateException.class)
            .build();
    var attempts = new AtomicInteger(0);

    var result =
        policy.execute(
            () -> {
              var count = attempts.incrementAndGet();
              if (count == 1) {
                throw new IOException("IO error");
              }
              if (count == 2) {
                throw new IllegalStateException("State error");
              }
              return "success";
            });

    assertEquals("success", result);
    assertEquals(3, attempts.get());
  }

  @Test
  void customRetryPredicate() throws Exception {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(t -> t.getMessage().contains("retry"))
            .build();
    var attempts = new AtomicInteger(0);

    var result =
        policy.execute(
            () -> {
              if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("Please retry");
              }
              return "success";
            });

    assertEquals("success", result);
    assertEquals(3, attempts.get());
  }

  @Test
  void customRetryPredicateStopsOnNonMatch() {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(t -> t.getMessage().contains("retry"))
            .build();
    var attempts = new AtomicInteger(0);

    assertThrows(
        RetryExhaustedException.class,
        () ->
            policy.execute(
                () -> {
                  attempts.incrementAndGet();
                  throw new RuntimeException("No match here");
                }));

    assertEquals(1, attempts.get());
  }

  @Test
  void executeRunnableSuccess() throws Exception {
    var policy = RetryPolicy.newBuilder().withMaxAttempts(3).build();
    var executed = new AtomicInteger(0);

    policy.execute(executed::incrementAndGet);

    assertEquals(1, executed.get());
  }

  @Test
  void executeRunnableWithRetry() throws Exception {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var attempts = new AtomicInteger(0);

    policy.execute(
        () -> {
          if (attempts.incrementAndGet() < 3) {
            throw new RuntimeException("Fail");
          }
        });

    assertEquals(3, attempts.get());
  }

  @Test
  void interruptedDuringRetry() {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofSeconds(10)))
            .build();
    var attempts = new AtomicInteger(0);

    Thread.currentThread().interrupt();

    assertThrows(
        InterruptedException.class,
        () ->
            policy.execute(
                () -> {
                  attempts.incrementAndGet();
                  throw new RuntimeException("Fail");
                }));

    assertTrue(Thread.interrupted());
  }

  @Test
  void defaultValues() {
    var policy = RetryPolicy.newBuilder().build();

    assertEquals(3, policy.maxAttempts());
    assertInstanceOf(Backoff.Exponential.class, policy.backoff());
    assertEquals(0.1, policy.jitter());
  }

  @Test
  void operationThrowsInterruptedException() {
    var policy = RetryPolicy.newBuilder().withMaxAttempts(3).build();

    assertThrows(
        InterruptedException.class,
        () ->
            policy.execute(
                () -> {
                  throw new InterruptedException("interrupted");
                }));

    assertTrue(Thread.interrupted());
  }

  @Test
  void zeroMaxAttemptsRejectedAtBuild() {
    assertThrows(
        IllegalStateException.class, () -> RetryPolicy.newBuilder().withMaxAttempts(0).build());
  }

  @Test
  void negativeMaxAttemptsRejectedAtBuild() {
    assertThrows(
        IllegalStateException.class, () -> RetryPolicy.newBuilder().withMaxAttempts(-1).build());
  }

  @Test
  void recordAccessors() {
    var backoff = Backoff.fixed(Duration.ofSeconds(1));
    var policy =
        RetryPolicy.newBuilder().withMaxAttempts(5).withBackoff(backoff).withJitter(0.2).build();

    assertEquals(5, policy.maxAttempts());
    assertEquals(backoff, policy.backoff());
    assertEquals(0.2, policy.jitter());
  }

  @Test
  void errorEscapesRetryPolicy() {
    // OutOfMemoryError / StackOverflowError / LinkageError must escape — RetryPolicy.execute used
    // to
    // catch Throwable and wrap Errors in RetryExhaustedException, violating the framework's
    // documented "Error subtypes escape so OOM/StackOverflow kill the host thread cleanly"
    // invariant (see CLAUDE.md "Loop crash semantics").
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .build();
    var attempts = new AtomicInteger(0);

    assertThrows(
        StackOverflowError.class,
        () ->
            policy.execute(
                () -> {
                  attempts.incrementAndGet();
                  throw new StackOverflowError("simulated");
                }));

    assertEquals(1, attempts.get(), "Error must escape on first attempt, not retry");
  }

  @Test
  void predicateExceptionPreservesOriginalCause() {
    var policy =
        RetryPolicy.newBuilder()
            .withMaxAttempts(5)
            .withBackoff(Backoff.fixed(Duration.ofMillis(1)))
            .withRetryOn(
                t -> {
                  throw new RuntimeException("predicate exploded");
                })
            .build();
    var attempts = new AtomicInteger(0);

    var exception =
        assertThrows(
            RetryExhaustedException.class,
            () ->
                policy.execute(
                    () -> {
                      attempts.incrementAndGet();
                      throw new IOException("original error");
                    }));

    assertEquals(1, attempts.get());
    assertInstanceOf(IOException.class, exception.getCause());
    assertEquals(1, exception.getCause().getSuppressed().length);
    assertEquals("predicate exploded", exception.getCause().getSuppressed()[0].getMessage());
  }
}
