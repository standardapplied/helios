/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

final class NoopExecutionProviderTest {

  private static final SessionContext CTX = SessionContext.forTesting("noop-test");

  @Test
  void singletonIsStable() {
    assertSame(NoopExecutionProvider.INSTANCE, NoopExecutionProvider.INSTANCE);
  }

  @Test
  void capabilitiesAdvertiseEmptyRuntimes() {
    var caps = NoopExecutionProvider.INSTANCE.capabilities();
    assertTrue(caps.supportedRuntimes().isEmpty());
    assertFalse(caps.networkAllowed());
    assertFalse(caps.filesystemWriteAllowed());
  }

  @Test
  void executeReturnsRefusalResult() throws ExecutionException, InterruptedException {
    var request =
        ExecutionRequest.newBuilder().withRuntime(Runtime.PYTHON).withScript("print('hi')").build();
    var future =
        NoopExecutionProvider.INSTANCE
            .execute(CTX, request, new CancellationToken())
            .toCompletableFuture();
    var result = future.get();
    assertEquals(-1, result.exitCode());
    assertEquals("", result.stdout());
    assertTrue(result.stderr().contains("execution is disabled"));
    assertTrue(result.stderr().contains("PYTHON"));
    assertEquals(Duration.ZERO, result.duration());
    assertFalse(result.timedOut());
    assertEquals(0, result.totalRedactions());
  }

  @Test
  void executeRejectsNullSession() {
    var request = ExecutionRequest.newBuilder().withRuntime(Runtime.BASH).withScript("x").build();
    var token = new CancellationToken();
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> NoopExecutionProvider.INSTANCE.execute(null, request, token));
    assertEquals("session must not be null", ex.getMessage());
  }

  @Test
  void executeRejectsNullRequest() {
    var token = new CancellationToken();
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> NoopExecutionProvider.INSTANCE.execute(CTX, null, token));
    assertEquals("request must not be null", ex.getMessage());
  }

  @Test
  void executeRejectsNullCancellation() {
    var request = ExecutionRequest.newBuilder().withRuntime(Runtime.BASH).withScript("x").build();
    var ex =
        assertThrows(
            NullPointerException.class,
            () -> NoopExecutionProvider.INSTANCE.execute(CTX, request, null));
    assertEquals("cancellation must not be null", ex.getMessage());
  }
}
