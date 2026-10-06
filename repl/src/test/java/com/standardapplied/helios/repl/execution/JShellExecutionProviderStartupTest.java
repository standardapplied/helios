/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.repl.ReplConfig;
import com.standardapplied.helios.repl.ReplException;
import com.standardapplied.helios.repl.sandbox.ExecutionRequest;
import com.standardapplied.helios.repl.sandbox.ExecutionResult;
import com.standardapplied.helios.repl.sandbox.Sandbox;
import com.standardapplied.helios.session.execution.Runtime;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The provider's startup snippet, the pool permit a failed start gives back, and the redaction of
 * what a sandbox reports: its output and, for a configured bindings listener, its bindings.
 */
class JShellExecutionProviderStartupTest {

  private static final String SECRET = "sk-live-0123456789";

  @Test
  void startupSnippetRunsBeforeTheFirstExecute() {
    var sandbox = new ScriptedSandbox(code -> result(0, "", ""));
    try (var provider = provider(sandbox, "var ready = true;", 1)) {
      assertSame(SessionStartOutcome.accept(), provider.onSessionStart(ctx("s")));

      assertEquals(List.of("var ready = true;"), sandbox.codes);
      assertEquals(1, provider.liveSessionCount());
    }
  }

  @Test
  void startupSnippetExitingNonZeroRefusesWithItsStderrAndGivesThePermitBack() {
    var sandbox = new ScriptedSandbox(code -> result(1, "out", "bad input"));
    try (var provider = provider(sandbox, "throw broken;", 1)) {
      var refuse =
          assertInstanceOf(SessionStartOutcome.Refuse.class, provider.onSessionStart(ctx("s")));

      assertEquals(
          "JShell startup snippet failed for session s (exit=1): bad input", refuse.reason());
      assertNull(refuse.cause());
      assertTrue(sandbox.closed);
      assertEquals(0, provider.liveSessionCount());
      var second =
          assertInstanceOf(SessionStartOutcome.Refuse.class, provider.onSessionStart(ctx("t")));
      assertEquals(
          "JShell startup snippet failed for session t (exit=1): bad input", second.reason());
      assertEquals(List.of("throw broken;", "throw broken;"), sandbox.codes);
    }
  }

  @Test
  void startupSnippetExitingNonZeroWithoutStderrRefusesWithItsStdout() {
    var sandbox = new ScriptedSandbox(code -> result(2, "printed", " "));
    try (var provider = provider(sandbox, "System.exit(2);", 1)) {
      var refuse =
          assertInstanceOf(SessionStartOutcome.Refuse.class, provider.onSessionStart(ctx("s")));

      assertEquals(
          "JShell startup snippet failed for session s (exit=2): printed", refuse.reason());
    }
  }

  @Test
  void startupSnippetThatThrowsRefusesWithTheFailureAndGivesThePermitBack() {
    var failure = new ReplException("sandbox gone");
    var sandbox =
        new ScriptedSandbox(
            code -> {
              throw failure;
            });
    try (var provider = provider(sandbox, "int x = 1;", 1)) {
      var refuse =
          assertInstanceOf(SessionStartOutcome.Refuse.class, provider.onSessionStart(ctx("s")));

      assertEquals("JShell startup snippet failed for session s: sandbox gone", refuse.reason());
      assertSame(failure, refuse.cause());
      assertTrue(sandbox.closed);
      assertEquals(0, provider.liveSessionCount());
      sandbox.script = code -> result(0, "", "");
      assertSame(SessionStartOutcome.accept(), provider.onSessionStart(ctx("t")));
    }
  }

  @Test
  void outputAndListenedBindingsAreRedacted() {
    var bindings = Map.of("token", "Bearer " + SECRET, "count", "3");
    var sandbox =
        new ScriptedSandbox(
            code ->
                ExecutionResult.newBuilder()
                    .withStdout("token=" + SECRET)
                    .withStderr(SECRET + " " + SECRET)
                    .withBindings(bindings)
                    .build());
    var listened = new AtomicReference<Map<String, String>>();
    var registry = new SecretRegistry();
    registry.register("API_KEY", SECRET);
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(r -> sandbox)
            .withSandboxBindingsListener((seen, result) -> listened.set(seen))
            .build();
    try (var provider =
        JShellExecutionProvider.newBuilder()
            .withReplConfig(config)
            .withSecretRegistry(registry)
            .withShutdownHook(false)
            .build()) {
      provider.onSessionStart(ctx("s"));

      var result =
          Await.value(
              "the execution",
              provider
                  .execute(
                      ctx("s"),
                      com.standardapplied.helios.session.execution.ExecutionRequest.newBuilder()
                          .withRuntime(Runtime.JSHELL)
                          .withScript("token")
                          .build(),
                      new CancellationToken())
                  .toCompletableFuture());

      assertEquals("token=<redacted:API_KEY>", result.stdout());
      assertEquals("<redacted:API_KEY> <redacted:API_KEY>", result.stderr());
      assertEquals(Map.of("API_KEY", 3), result.secretRedactionCounts());
      assertEquals(Map.of("token", "Bearer <redacted:API_KEY>", "count", "3"), listened.get());
    }
  }

  private static JShellExecutionProvider provider(
      ScriptedSandbox sandbox, String startupSnippet, int maxSessions) {
    return JShellExecutionProvider.newBuilder()
        .withReplConfig(ReplConfig.newBuilder().withSandboxFactory(r -> sandbox).build())
        .withStartupSnippet(startupSnippet)
        .withMaxConcurrentSessions(maxSessions)
        .withShutdownHook(false)
        .build();
  }

  private static SessionContext ctx(String id) {
    return SessionContext.forTesting(id);
  }

  private static ExecutionResult result(int exitCode, String stdout, String stderr) {
    return ExecutionResult.newBuilder()
        .withExitCode(exitCode)
        .withStdout(stdout)
        .withStderr(stderr)
        .build();
  }

  /** A sandbox that answers each snippet through {@link #script} and records what it ran. */
  private static final class ScriptedSandbox implements Sandbox {
    private final List<String> codes = new ArrayList<>();
    private volatile Function<String, ExecutionResult> script;
    private volatile boolean closed;

    ScriptedSandbox(Function<String, ExecutionResult> script) {
      this.script = script;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
      codes.add(request.code());
      return script.apply(request.code());
    }

    @Override
    public boolean isAlive() {
      return true;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
