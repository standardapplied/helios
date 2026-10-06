/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.execution;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.repl.ReplConfig;
import com.standardapplied.helios.repl.SandboxBindingsListener;
import com.standardapplied.helios.session.execution.ExecutionResult;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Scrubs what a sandbox reports against the provider's {@link SecretRegistry}: the stdout and
 * stderr of each execute, with per-secret hit counts, and every binding value delivered to a
 * configured {@link SandboxBindingsListener}.
 */
final class OutputRedaction {

  private final SecretRegistry registry;

  OutputRedaction(SecretRegistry registry) {
    this.registry = registry;
  }

  /** The session-level result of {@code raw}, its output redacted, taking {@code elapsed}. */
  ExecutionResult redact(
      com.standardapplied.helios.repl.sandbox.ExecutionResult raw, Duration elapsed) {
    var redactor = registry.redactor();
    var stdout = redactor.redact(raw.stdout());
    var stderr = redactor.redact(raw.stderr());
    return new ExecutionResult(
        raw.exitCode(), stdout.text(), stderr.text(), elapsed, false, stdout.mergeCounts(stderr));
  }

  /**
   * Return a copy of {@code config} whose {@link SandboxBindingsListener} is wrapped to scrub every
   * binding value through the registry's redactor before delivery. {@code stdout} and {@code
   * stderr} are redacted by {@link #redact}; without this wrapper, operator telemetry receiving the
   * bindings snapshot would see {@code var apiKey = "sk-..."} verbatim.
   *
   * <p>Returns {@code config} unchanged when no listener is configured.
   */
  ReplConfig withRedactingBindingsListener(ReplConfig config) {
    var listener = redactingBindingsListener(registry, config.sandboxBindingsListener());
    if (listener == config.sandboxBindingsListener()) {
      return config;
    }
    return new ReplConfig(
        config.sandboxFactory(),
        config.executionTimeout(),
        config.maxConcurrentSessions(),
        config.hostFunctions(),
        config.maxOutputCharsToModel(),
        listener,
        config.maxBindingValueChars(),
        config.maxBindingSnapshotChars(),
        config.maxExecutedCodeChars());
  }

  /**
   * Build a {@link SandboxBindingsListener} that decorates {@code delegate} with per-value
   * redaction against {@code registry}. Returns {@code null} when {@code delegate} is null
   * (preserves the null-disables semantics of {@link ReplConfig#sandboxBindingsListener()}).
   *
   * <p>Package-private for testing — the wrapping logic is the security-critical bit and is worth
   * exercising directly without needing a full sandbox subprocess.
   */
  static SandboxBindingsListener redactingBindingsListener(
      SecretRegistry registry, SandboxBindingsListener delegate) {
    if (delegate == null) {
      return null;
    }
    return (bindings, result) -> {
      if (bindings.isEmpty()) {
        delegate.onBindings(bindings, result);
        return;
      }
      var redactor = registry.redactor();
      var redacted = new LinkedHashMap<String, String>(bindings.size());
      for (var entry : bindings.entrySet()) {
        var value = entry.getValue();
        redacted.put(entry.getKey(), value == null ? null : redactor.redact(value).text());
      }
      // Preserve declaration order — Map.copyOf would lose it. The listener never mutates.
      delegate.onBindings(Collections.unmodifiableMap(redacted), result);
    };
  }
}
