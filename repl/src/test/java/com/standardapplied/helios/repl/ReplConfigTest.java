/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.sandbox.SandboxFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplConfigTest {

  private static final SandboxFactory DUMMY_FACTORY = registry -> null;

  // ── canonical constructor ───────────────────────────────────────────────

  @Test
  void canonicalConstructorReadsBack() {
    var fn = new HostFunction("test", "desc", params -> "ok");
    var config = new ReplConfig(DUMMY_FACTORY, Duration.ofSeconds(10), 5, List.of(fn));
    assertEquals(DUMMY_FACTORY, config.sandboxFactory());
    assertEquals(Duration.ofSeconds(10), config.executionTimeout());
    assertEquals(5, config.maxConcurrentSessions());
    assertEquals(1, config.hostFunctions().size());
  }

  @Test
  void nullFactoryThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReplConfig(null, Duration.ofSeconds(10), 5, List.of()));
  }

  @Test
  void nullTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class, () -> new ReplConfig(DUMMY_FACTORY, null, 5, List.of()));
  }

  @Test
  void zeroTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReplConfig(DUMMY_FACTORY, Duration.ZERO, 5, List.of()));
  }

  @Test
  void negativeTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReplConfig(DUMMY_FACTORY, Duration.ofSeconds(-1), 5, List.of()));
  }

  @Test
  void zeroConcurrencyThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ReplConfig(DUMMY_FACTORY, Duration.ofSeconds(10), 0, List.of()));
  }

  @Test
  void hostFunctionsDefensivelyCopied() {
    var mutable = new java.util.ArrayList<HostFunction>();
    mutable.add(new HostFunction("a", "desc", params -> "a"));
    var config = new ReplConfig(DUMMY_FACTORY, Duration.ofSeconds(10), 5, mutable);
    mutable.clear();
    // Calculator still sees the original entry — proves the snapshot was taken.
    assertEquals(1, config.hostFunctions().size());
  }

  // ── builder ─────────────────────────────────────────────────────────────

  @Test
  void newBuilderProducesDefaults() {
    var config = ReplConfig.newBuilder().withSandboxFactory(DUMMY_FACTORY).build();
    assertEquals(ReplConfig.DEFAULT_EXECUTION_TIMEOUT, config.executionTimeout());
    assertEquals(ReplConfig.DEFAULT_MAX_CONCURRENT_SESSIONS, config.maxConcurrentSessions());
    assertEquals(0, config.hostFunctions().size());
  }

  @Test
  void builderWithAllOptionsAppliesEverySetter() {
    var fn1 = new HostFunction("a", "desc", params -> "a");
    var fn2 = new HostFunction("b", "desc", params -> "b");
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(DUMMY_FACTORY)
            .withExecutionTimeout(Duration.ofMinutes(1))
            .withMaxConcurrentSessions(10)
            .withHostFunction(fn1)
            .withHostFunctions(List.of(fn2))
            .build();
    assertEquals(DUMMY_FACTORY, config.sandboxFactory());
    assertEquals(Duration.ofMinutes(1), config.executionTimeout());
    assertEquals(10, config.maxConcurrentSessions());
    assertEquals(2, config.hostFunctions().size());
  }

  @Test
  void builderWithHostFunctionsList() {
    var fn1 = new HostFunction("a", "desc", params -> "a");
    var fn2 = new HostFunction("b", "desc", params -> "b");
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(DUMMY_FACTORY)
            .withHostFunctions(List.of(fn1, fn2))
            .build();
    assertEquals(2, config.hostFunctions().size());
  }
}
