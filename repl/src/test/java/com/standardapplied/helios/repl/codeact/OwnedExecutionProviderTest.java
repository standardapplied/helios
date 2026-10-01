/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.runtime.SessionContext;
import ai.singlr.repl.ReplConfig;
import ai.singlr.repl.execution.JShellExecutionProvider;
import ai.singlr.repl.sandbox.JvmSandbox;
import ai.singlr.session.execution.SessionStartOutcome;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link OwnedExecutionProvider}. The decorator's value is the deterministic
 * close on {@code onSessionEnd}; these tests pin that contract down — plus null-arg handling and
 * the capability delegation so a future change can't accidentally regress the forwarding.
 */
final class OwnedExecutionProviderTest {

  private static JShellExecutionProvider noShutdownHookProvider() {
    var replConfig = ReplConfig.newBuilder().withSandboxFactory(JvmSandbox.factory()).build();
    return JShellExecutionProvider.newBuilder()
        .withReplConfig(replConfig)
        .withShutdownHook(false)
        .build();
  }

  @Test
  void constructorRejectsNullDelegate() {
    var ex = assertThrows(NullPointerException.class, () -> new OwnedExecutionProvider(null));
    assertEquals("delegate must not be null", ex.getMessage());
  }

  @Test
  void capabilitiesDelegatesToWrappedProvider() {
    try (var inner = noShutdownHookProvider()) {
      var owned = new OwnedExecutionProvider(inner);
      assertNotNull(owned.capabilities());
      assertEquals(inner.capabilities(), owned.capabilities());
    }
  }

  @Test
  void delegateAccessorExposesInner() {
    try (var inner = noShutdownHookProvider()) {
      var owned = new OwnedExecutionProvider(inner);
      assertEquals(inner, owned.delegate());
    }
  }

  @Test
  void onSessionEndClosesWrappedProvider() {
    var inner = noShutdownHookProvider();
    var owned = new OwnedExecutionProvider(inner);
    var ctx = SessionContext.forTesting("owned-end-closes");
    owned.onSessionEnd(ctx);
    assertTrue(inner.isClosed(), "delegate must be closed via onSessionEnd cascade");
  }

  @Test
  void explicitCloseAlsoClosesWrappedProvider() {
    var inner = noShutdownHookProvider();
    var owned = new OwnedExecutionProvider(inner);
    owned.close();
    assertTrue(inner.isClosed());
  }

  @Test
  void onSessionEndIsSafeWhenSessionNeverStarted() {
    var inner = noShutdownHookProvider();
    var owned = new OwnedExecutionProvider(inner);
    var ctx = SessionContext.forTesting("never-started");
    owned.onSessionEnd(ctx); // delegate onSessionEnd is a no-op for unknown ids
    assertTrue(inner.isClosed());
  }

  @Test
  void onSessionStartDelegatesAndReturnsAccept() {
    try (var inner = noShutdownHookProvider()) {
      var owned = new OwnedExecutionProvider(inner);
      var ctx = SessionContext.forTesting("owned-start");
      var outcome = owned.onSessionStart(ctx);
      assertFalse(outcome instanceof SessionStartOutcome.Refuse);
    }
  }
}
