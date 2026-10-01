/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.repl.host.HostFunction;
import ai.singlr.repl.sandbox.SandboxFactory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplConfigTest {

  private static final SandboxFactory DUMMY_FACTORY = registry -> null;
  private static final int DEFAULT_CAP = ReplConfig.DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL;

  // ── canonical constructor ───────────────────────────────────────────────

  @Test
  void canonicalConstructorReadsBack() {
    var fn = new HostFunction("test", "desc", params -> "ok");
    var config =
        new ReplConfig(
            DUMMY_FACTORY,
            Duration.ofSeconds(10),
            5,
            List.of(fn),
            DEFAULT_CAP,
            null,
            200,
            16384,
            5000);
    assertEquals(DUMMY_FACTORY, config.sandboxFactory());
    assertEquals(Duration.ofSeconds(10), config.executionTimeout());
    assertEquals(5, config.maxConcurrentSessions());
    assertEquals(1, config.hostFunctions().size());
    assertEquals(DEFAULT_CAP, config.maxOutputCharsToModel());
    assertNull(config.sandboxBindingsListener());
    assertEquals(200, config.maxBindingValueChars());
    assertEquals(16384, config.maxBindingSnapshotChars());
    assertEquals(5000, config.maxExecutedCodeChars());
  }

  @Test
  void nullFactoryThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                null, Duration.ofSeconds(10), 5, List.of(), DEFAULT_CAP, null, 200, 16384, 5000));
  }

  @Test
  void nullTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(DUMMY_FACTORY, null, 5, List.of(), DEFAULT_CAP, null, 200, 16384, 5000));
  }

  @Test
  void zeroTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY, Duration.ZERO, 5, List.of(), DEFAULT_CAP, null, 200, 16384, 5000));
  }

  @Test
  void negativeTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                Duration.ofSeconds(-1),
                5,
                List.of(),
                DEFAULT_CAP,
                null,
                200,
                16384,
                5000));
  }

  @Test
  void zeroConcurrencyThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                Duration.ofSeconds(10),
                0,
                List.of(),
                DEFAULT_CAP,
                null,
                200,
                16384,
                5000));
  }

  @Test
  void negativeMaxOutputCharsToModelThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY, Duration.ofSeconds(10), 5, List.of(), -1, null, 200, 16384, 5000));
  }

  @Test
  void zeroMaxOutputCharsToModelAllowed() {
    var config =
        new ReplConfig(
            DUMMY_FACTORY, Duration.ofSeconds(10), 5, List.of(), 0, null, 200, 16384, 5000);
    assertEquals(0, config.maxOutputCharsToModel());
  }

  @Test
  void negativeMaxBindingValueCharsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                Duration.ofSeconds(10),
                5,
                List.of(),
                DEFAULT_CAP,
                null,
                -1,
                16384,
                5000));
  }

  @Test
  void negativeMaxBindingSnapshotCharsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                Duration.ofSeconds(10),
                5,
                List.of(),
                DEFAULT_CAP,
                null,
                200,
                -1,
                5000));
  }

  @Test
  void negativeMaxExecutedCodeCharsThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ReplConfig(
                DUMMY_FACTORY,
                Duration.ofSeconds(10),
                5,
                List.of(),
                DEFAULT_CAP,
                null,
                200,
                16384,
                -1));
  }

  @Test
  void hostFunctionsDefensivelyCopied() {
    var mutable = new java.util.ArrayList<HostFunction>();
    mutable.add(new HostFunction("a", "desc", params -> "a"));
    var config =
        new ReplConfig(
            DUMMY_FACTORY, Duration.ofSeconds(10), 5, mutable, DEFAULT_CAP, null, 200, 16384, 5000);
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
    assertEquals(ReplConfig.DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL, config.maxOutputCharsToModel());
    assertEquals(ReplConfig.DEFAULT_MAX_BINDING_VALUE_CHARS, config.maxBindingValueChars());
    assertEquals(ReplConfig.DEFAULT_MAX_BINDING_SNAPSHOT_CHARS, config.maxBindingSnapshotChars());
    assertEquals(ReplConfig.DEFAULT_MAX_EXECUTED_CODE_CHARS, config.maxExecutedCodeChars());
    assertEquals(0, config.hostFunctions().size());
    assertNull(config.sandboxBindingsListener());
  }

  @Test
  void builderWithAllOptionsAppliesEverySetter() {
    var fn1 = new HostFunction("a", "desc", params -> "a");
    var fn2 = new HostFunction("b", "desc", params -> "b");
    SandboxBindingsListener listener = (bindings, result) -> {};
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(DUMMY_FACTORY)
            .withExecutionTimeout(Duration.ofMinutes(1))
            .withMaxConcurrentSessions(10)
            .withHostFunction(fn1)
            .withHostFunctions(List.of(fn2))
            .withMaxOutputCharsToModel(2000)
            .withSandboxBindingsListener(listener)
            .withMaxBindingValueChars(300)
            .withMaxBindingSnapshotChars(20_000)
            .withMaxExecutedCodeChars(8000)
            .build();
    assertEquals(DUMMY_FACTORY, config.sandboxFactory());
    assertEquals(Duration.ofMinutes(1), config.executionTimeout());
    assertEquals(10, config.maxConcurrentSessions());
    assertEquals(2, config.hostFunctions().size());
    assertEquals(2000, config.maxOutputCharsToModel());
    assertEquals(listener, config.sandboxBindingsListener());
    assertEquals(300, config.maxBindingValueChars());
    assertEquals(20_000, config.maxBindingSnapshotChars());
    assertEquals(8000, config.maxExecutedCodeChars());
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
