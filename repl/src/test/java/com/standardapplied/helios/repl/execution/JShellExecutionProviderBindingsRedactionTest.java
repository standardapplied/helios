/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.repl.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.repl.SandboxBindingsListener;
import com.standardapplied.helios.repl.sandbox.ExecutionResult;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OutputRedaction#redactingBindingsListener(SecretRegistry,
 * SandboxBindingsListener)}. {@code stdout}/{@code stderr} are already redacted by the provider;
 * the bindings snapshot delivered to {@link SandboxBindingsListener#onBindings} used to bypass the
 * redaction and leak {@code var apiKey = "sk-..."} verbatim to operator-facing telemetry.
 */
final class JShellExecutionProviderBindingsRedactionTest {

  private static ExecutionResult stubResult() {
    return ExecutionResult.newBuilder()
        .withExecutedCode("noop")
        .withStdout("")
        .withStderr("")
        .withExitCode(0)
        .withBindings(Map.of())
        .withDuration(Duration.ZERO)
        .build();
  }

  @Test
  void wrapperRedactsRegisteredSecretFromBindingValues() {
    var registry = new SecretRegistry();
    registry.register("API_KEY", "sk-supersecret-token-xyz");

    var captured = new AtomicReference<Map<String, String>>();
    SandboxBindingsListener inner = (bindings, result) -> captured.set(bindings);

    var wrapped = OutputRedaction.redactingBindingsListener(registry, inner);
    assertNotNull(wrapped);

    var snapshot =
        Map.of(
            "apiKey", "sk-supersecret-token-xyz",
            "harmless", "hello world");
    wrapped.onBindings(snapshot, stubResult());

    var seen = captured.get();
    assertEquals("hello world", seen.get("harmless"), "non-secret values passed through unchanged");
    assertTrue(
        !seen.get("apiKey").contains("sk-supersecret-token-xyz"),
        "secret value must be scrubbed from the operator-facing bindings snapshot; saw: "
            + seen.get("apiKey"));
    assertTrue(
        seen.get("apiKey").contains("<redacted:API_KEY>"),
        "redaction marker preserves the secret's name for diagnostics; saw: " + seen.get("apiKey"));
  }

  @Test
  void wrapperLeavesBindingsAloneWhenRegistryEmpty() {
    var registry = new SecretRegistry();
    var captured = new AtomicReference<Map<String, String>>();
    SandboxBindingsListener inner = (bindings, result) -> captured.set(bindings);
    var wrapped = OutputRedaction.redactingBindingsListener(registry, inner);

    var snapshot = Map.of("x", "1", "y", "2");
    wrapped.onBindings(snapshot, stubResult());

    assertEquals(snapshot, captured.get());
  }

  @Test
  void wrapperDeliversAnEmptySnapshotAsIs() {
    var registry = new SecretRegistry();
    registry.register("API_KEY", "sk-supersecret-token-xyz");
    var captured = new AtomicReference<Map<String, String>>();
    SandboxBindingsListener inner = (bindings, result) -> captured.set(bindings);
    var wrapped = OutputRedaction.redactingBindingsListener(registry, inner);

    Map<String, String> snapshot = Map.of();
    wrapped.onBindings(snapshot, stubResult());

    assertSame(snapshot, captured.get());
  }

  @Test
  void wrapperKeepsANullBindingValueNull() {
    var registry = new SecretRegistry();
    registry.register("API_KEY", "sk-supersecret-token-xyz");
    var captured = new AtomicReference<Map<String, String>>();
    SandboxBindingsListener inner = (bindings, result) -> captured.set(bindings);
    var wrapped = OutputRedaction.redactingBindingsListener(registry, inner);

    var snapshot = new java.util.LinkedHashMap<String, String>();
    snapshot.put("unset", null);
    snapshot.put("apiKey", "sk-supersecret-token-xyz");
    wrapped.onBindings(snapshot, stubResult());

    var expected = new java.util.LinkedHashMap<String, String>();
    expected.put("unset", null);
    expected.put("apiKey", "<redacted:API_KEY>");
    assertEquals(expected, captured.get());
  }

  @Test
  void wrapperOfNullDelegateIsNull() {
    var registry = new SecretRegistry();
    assertNull(
        OutputRedaction.redactingBindingsListener(registry, null),
        "no listener configured → no wrapper allocated; preserves the null-disables semantics");
  }

  @Test
  void wrapperPreservesIterationOrder() {
    var registry = new SecretRegistry();
    registry.register("S", "abcdefgh");

    var captured = new AtomicReference<Map<String, String>>();
    SandboxBindingsListener inner = (bindings, result) -> captured.set(bindings);
    var wrapped = OutputRedaction.redactingBindingsListener(registry, inner);

    var snapshot = new java.util.LinkedHashMap<String, String>();
    snapshot.put("z", "1");
    snapshot.put("a", "2");
    snapshot.put("m", "abcdefgh");
    wrapped.onBindings(snapshot, stubResult());

    var keys = captured.get().keySet().iterator();
    assertEquals("z", keys.next(), "insertion order preserved");
    assertEquals("a", keys.next());
    assertEquals("m", keys.next());
  }
}
