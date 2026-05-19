/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SecretRegistryTest {

  @Test
  void registersAndExposesSize() {
    var registry = new SecretRegistry();
    assertEquals(0, registry.size());
    registry.register("GH_TOKEN", "ghp_abc12345");
    assertEquals(1, registry.size());
    assertEquals(Set.of("GH_TOKEN"), registry.names());
  }

  @Test
  void overwriteSameNameKeepsSizeOne() {
    var registry = new SecretRegistry();
    registry.register("GH_TOKEN", "ghp_abc12345");
    registry.register("GH_TOKEN", "ghp_other67890");
    assertEquals(1, registry.size());
    assertTrue(registry.leaks("ghp_other67890"));
    assertFalse(registry.leaks("ghp_abc12345"));
  }

  @Test
  void blankNameRefused() {
    var registry = new SecretRegistry();
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> registry.register("  ", "validvalue123"));
    assertTrue(ex.getMessage().contains("name"));
  }

  @Test
  void nullNameRefused() {
    var registry = new SecretRegistry();
    assertThrows(IllegalArgumentException.class, () -> registry.register(null, "validvalue123"));
  }

  @Test
  void nullValueRefused() {
    var registry = new SecretRegistry();
    var ex = assertThrows(IllegalArgumentException.class, () -> registry.register("X", null));
    assertTrue(ex.getMessage().contains("must not be null"));
  }

  @Test
  void shortValueRefused() {
    var registry = new SecretRegistry();
    var ex = assertThrows(IllegalArgumentException.class, () -> registry.register("X", "short"));
    assertTrue(ex.getMessage().contains("at least"));
  }

  @Test
  void valueAtMinLengthAccepted() {
    var registry = new SecretRegistry();
    registry.register("X", "12345678");
    assertEquals(1, registry.size());
  }

  @Test
  void nonAsciiValueRefused() {
    var registry = new SecretRegistry();
    var ex =
        assertThrows(IllegalArgumentException.class, () -> registry.register("X", "tokenfooé"));
    assertTrue(ex.getMessage().contains("ASCII"));
    assertTrue(ex.getMessage().contains("index"));
  }

  @Test
  void registerRefusesJsonSpecialQuote() {
    var registry = new SecretRegistry();
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> registry.register("X", "tok\"en-12345678"));
    assertTrue(ex.getMessage().contains("JSON-special"), ex.getMessage());
  }

  @Test
  void registerRefusesJsonSpecialBackslash() {
    var registry = new SecretRegistry();
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> registry.register("X", "tok\\en-12345678"));
    assertTrue(ex.getMessage().contains("JSON-special"), ex.getMessage());
  }

  @Test
  void registerRefusesNewline() {
    var registry = new SecretRegistry();
    var ex =
        assertThrows(IllegalArgumentException.class, () -> registry.register("X", "abc\ndef12345"));
    assertTrue(ex.getMessage().contains("control"), ex.getMessage());
  }

  @Test
  void registerRefusesTab() {
    var registry = new SecretRegistry();
    var ex =
        assertThrows(IllegalArgumentException.class, () -> registry.register("X", "abc\tdef12345"));
    assertTrue(ex.getMessage().contains("control"), ex.getMessage());
  }

  @Test
  void registerRefusesArbitraryControlByte() {
    var registry = new SecretRegistry();
    var ex =
        assertThrows(IllegalArgumentException.class, () -> registry.register("X", "abcdef12345"));
    assertTrue(ex.getMessage().contains("control"), ex.getMessage());
    assertTrue(ex.getMessage().contains("0x01"), ex.getMessage());
  }

  @Test
  void registerRefusesDelControlByte() {
    var registry = new SecretRegistry();
    var ex =
        assertThrows(IllegalArgumentException.class, () -> registry.register("X", "abcdef12345"));
    assertTrue(ex.getMessage().contains("control"), ex.getMessage());
    assertTrue(ex.getMessage().contains("0x7F"), ex.getMessage());
  }

  @Test
  void registerAcceptsPrintableAsciiWithCommonSymbols() {
    // Sanity check the inverse: every byte in the legal range registers cleanly. Covers the symbol
    // characters Jackson does NOT escape: =-_./+:@#$%& etc., plus spaces.
    var registry = new SecretRegistry();
    registry.register("X", "token-with_symbols.+/=@#:12345");
    assertEquals(1, registry.size());
    assertTrue(registry.leaks("noise token-with_symbols.+/=@#:12345 noise"));
  }

  @Test
  void leaksDetectsRegisteredValueAnywhere() {
    var registry = new SecretRegistry();
    registry.register("T", "ghp_secrettoken");
    assertTrue(registry.leaks("ghp_secrettoken"));
    assertTrue(registry.leaks("prefixghp_secrettokensuffix"));
    assertFalse(registry.leaks("ghp_other"));
  }

  @Test
  void leaksHandlesNullAndEmpty() {
    var registry = new SecretRegistry();
    registry.register("T", "validtoken123");
    assertFalse(registry.leaks(null));
    assertFalse(registry.leaks(""));
  }

  @Test
  void leaksFalseWhenCandidateShorterThanAnySecret() {
    var registry = new SecretRegistry();
    registry.register("T", "ghp_longertoken");
    assertFalse(registry.leaks("short"));
  }

  @Test
  void leaksFalseWhenNoSecretsRegistered() {
    var registry = new SecretRegistry();
    assertFalse(registry.leaks("anything goes"));
  }

  @Test
  void leaksHandlesPartialMatchThatThenDiverges() {
    var registry = new SecretRegistry();
    registry.register("T", "abcdefgh");
    assertFalse(registry.leaks("abcdefgX"));
    assertFalse(registry.leaks("abXdefgh"));
  }

  @Test
  void redactorIsSnapshot() {
    var registry = new SecretRegistry();
    registry.register("A", "alphaalphaalpha");
    var first = registry.redactor();
    registry.register("B", "betabetabeta");
    var second = registry.redactor();
    assertNotSame(first, second);
    assertEquals(1, first.patternCount());
    assertEquals(2, second.patternCount());
  }

  @Test
  void redactorEmptyWhenNoSecrets() {
    var registry = new SecretRegistry();
    assertEquals(0, registry.redactor().patternCount());
  }

  @Test
  void snapshotReturnsDefensiveCopies() {
    var registry = new SecretRegistry();
    registry.register("A", "alphavalue123");
    var snap = registry.snapshot();
    snap.get("A")[0] = 0;
    var snap2 = registry.snapshot();
    assertEquals('a', (char) (snap2.get("A")[0] & 0xFF));
  }

  @Test
  void concurrentRegistrationsAreSafe() throws Exception {
    var registry = new SecretRegistry();
    var threads = 8;
    var perThread = 50;
    var ready = new CountDownLatch(threads);
    var go = new CountDownLatch(1);
    var done = new CountDownLatch(threads);
    var failures = new AtomicInteger();
    for (var t = 0; t < threads; t++) {
      var threadId = t;
      Thread.startVirtualThread(
          () -> {
            ready.countDown();
            try {
              go.await();
              for (var i = 0; i < perThread; i++) {
                registry.register("T-%d-%d".formatted(threadId, i), "value%08d".formatted(i));
              }
            } catch (Exception e) {
              failures.incrementAndGet();
            } finally {
              done.countDown();
            }
          });
    }
    assertTrue(ready.await(5, TimeUnit.SECONDS));
    go.countDown();
    assertTrue(done.await(5, TimeUnit.SECONDS));
    assertEquals(0, failures.get());
    assertEquals(threads * perThread, registry.size());
  }
}
