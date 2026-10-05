/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.process.BinaryResolver;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.test.Await;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalProcessExecutionProviderTest {

  private static final SessionContext CTX = SessionContext.forTesting("provider-test");

  /** A request timeout no test can reach: only the mechanism a test exercises ends its wait. */
  private static final Duration BEYOND_HANG_GUARD = Await.HANG_GUARD.multipliedBy(5);

  /** Cannot end on its own within the hang guard, so a result proves the timeout killed it. */
  private static final String HANG = "exec sleep 600";

  // ── Builder validation ────────────────────────────────────────────────────

  /**
   * A test that fails before its children are reaped must not leave one running; nothing else in
   * this JVM starts a process while a test of this class runs.
   */
  @AfterEach
  void noChildOutlivesItsTest() {
    ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
  }

  @Test
  void builderRequiresAtLeastOneRuntime() {
    var b = LocalProcessExecutionProvider.newBuilder();
    var ex = assertThrows(IllegalStateException.class, b::build);
    assertTrue(ex.getMessage().startsWith("at least one runtime handler"));
  }

  @Test
  void builderRejectsNullRuntime() {
    var b = LocalProcessExecutionProvider.newBuilder();
    var handler = LocalProcessExecutionProvider.RuntimeHandler.dashC("bash");
    var ex = assertThrows(NullPointerException.class, () -> b.withRuntime(null, handler));
    assertEquals("runtime must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsNullHandler() {
    var b = LocalProcessExecutionProvider.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withRuntime(Runtime.BASH, null));
    assertEquals("handler must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsNullSecretRegistry() {
    var b = LocalProcessExecutionProvider.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withSecretRegistry(null));
    assertEquals("secretRegistry must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsNullPath() {
    var b = LocalProcessExecutionProvider.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withPath(null));
    assertEquals("path must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsBlankPath() {
    var b = LocalProcessExecutionProvider.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withPath("   "));
    assertEquals("path must not be blank", ex.getMessage());
  }

  @Test
  void builderRejectsTinyMaxOutputBytes() {
    var b = LocalProcessExecutionProvider.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withMaxOutputBytes(512));
    assertTrue(ex.getMessage().startsWith("maxOutputBytes must be at least 1024"));
  }

  @Test
  void builderRejectsZeroMaxConcurrent() {
    var b = LocalProcessExecutionProvider.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withMaxConcurrent(0));
    assertTrue(ex.getMessage().startsWith("maxConcurrent must be at least 1"));
  }

  @Test
  void builderRejectsNullMaxTimeout() {
    var b = LocalProcessExecutionProvider.newBuilder();
    var ex = assertThrows(NullPointerException.class, () -> b.withMaxTimeout(null));
    assertEquals("maxTimeout must not be null", ex.getMessage());
  }

  @Test
  void builderRejectsZeroMaxTimeout() {
    var b = LocalProcessExecutionProvider.newBuilder();
    var ex = assertThrows(IllegalArgumentException.class, () -> b.withMaxTimeout(Duration.ZERO));
    assertTrue(ex.getMessage().startsWith("maxTimeout must be strictly positive"));
  }

  @Test
  void builderWithAllOptions() {
    assumeBashAvailable();
    var registry = new SecretRegistry();
    var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withSecretRegistry(registry)
            .withPath("/usr/bin:/bin")
            .withMaxOutputBytes(8192)
            .withMaxConcurrent(2)
            .withMaxTimeout(Duration.ofSeconds(30))
            .withNetworkAllowed(false)
            .withFilesystemWriteAllowed(false)
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .build();
    assertSame(registry, provider.secretRegistry());
    assertFalse(provider.capabilities().networkAllowed());
    assertFalse(provider.capabilities().filesystemWriteAllowed());
    assertEquals(Duration.ofSeconds(30), provider.capabilities().maxTimeout());
    assertTrue(provider.capabilities().supports(Runtime.BASH));
  }

  // ── defaultPosix factory ─────────────────────────────────────────────────

  @Test
  void defaultPosixRequiresSecretRegistry() {
    var ex =
        assertThrows(
            NullPointerException.class, () -> LocalProcessExecutionProvider.defaultPosix(null));
    assertEquals("secretRegistry must not be null", ex.getMessage());
  }

  @Test
  void defaultPosixAdvertisesBashAndPython() {
    assumeBashAvailable();
    assumePythonAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    assertTrue(provider.capabilities().supports(Runtime.BASH));
    assertTrue(provider.capabilities().supports(Runtime.PYTHON));
    assertFalse(provider.capabilities().supports(Runtime.JSHELL));
  }

  // ── RuntimeHandler.dashC ─────────────────────────────────────────────────

  @Test
  void dashCRejectsBlankSpec() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> LocalProcessExecutionProvider.RuntimeHandler.dashC("   "));
    assertEquals("binarySpec must not be blank", ex.getMessage());
  }

  @Test
  void dashCBuildsArgvInExpectedOrder() {
    assumeBashAvailable();
    var handler = LocalProcessExecutionProvider.RuntimeHandler.dashC("bash");
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("echo $1")
            .withArgs(List.of("hello"))
            .build();
    var argv = handler.buildArgv(req);
    assertTrue(argv.get(0).endsWith("/bash"));
    assertEquals("-c", argv.get(1));
    assertEquals("echo $1", argv.get(2));
    assertEquals("hello", argv.get(3));
  }

  // ── unsupported runtime returns refusal ──────────────────────────────────

  @Test
  void executeReturnsRefusalForUnsupportedRuntime() {
    assumeBashAvailable();
    var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .build();
    var req =
        ExecutionRequest.newBuilder().withRuntime(Runtime.PYTHON).withScript("print(1)").build();
    var result = run(provider, req);
    assertEquals(-1, result.exitCode());
    assertTrue(result.stderr().contains("not supported"));
    assertFalse(result.timedOut());
  }

  // ── successful execution ─────────────────────────────────────────────────

  @Test
  void executeCapturesStdout() {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("printf hello")
            .withTimeout(BEYOND_HANG_GUARD)
            .build();
    var result = run(provider, req);
    assertEquals(0, result.exitCode());
    assertEquals("hello", result.stdout());
    assertEquals("", result.stderr());
    assertFalse(result.timedOut());
  }

  @Test
  void executeCapturesStderrAndExitCode() {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("echo oops >&2; exit 3")
            .withTimeout(BEYOND_HANG_GUARD)
            .build();
    var result = run(provider, req);
    assertEquals(3, result.exitCode());
    assertTrue(result.stderr().contains("oops"));
  }

  @Test
  void executeRespectsTimeout() {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript(HANG)
            .withTimeout(Duration.ofMillis(150))
            .build();
    var result = run(provider, req);
    assertTrue(result.timedOut());
    assertEquals(-1, result.exitCode());
  }

  @Test
  void executeClampsRequestTimeoutToCapabilitiesMax() {
    assumeBashAvailable();
    var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .withMaxTimeout(Duration.ofMillis(150))
            .build();
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript(HANG)
            .withTimeout(Duration.ofMinutes(10))
            .build();
    var result = run(provider, req);
    assertTrue(result.timedOut());
  }

  @Test
  void executeRedactsSecretsInStdout() {
    assumeBashAvailable();
    var registry = new SecretRegistry();
    registry.register("TOKEN", "supersecret123");
    var provider = LocalProcessExecutionProvider.defaultPosix(registry);
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("printf supersecret123")
            .withTimeout(BEYOND_HANG_GUARD)
            .build();
    var result = run(provider, req);
    assertEquals("<redacted:TOKEN>", result.stdout());
    assertEquals(Integer.valueOf(1), result.secretRedactionCounts().get("TOKEN"));
  }

  @Test
  void executeInjectsEnvironment() {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("printf '%s' \"$GREETING\"")
            .withEnv("GREETING", "ahoy")
            .withTimeout(BEYOND_HANG_GUARD)
            .build();
    var result = run(provider, req);
    assertEquals(0, result.exitCode());
    assertEquals("ahoy", result.stdout());
  }

  @Test
  void executeFeedsStdin() {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("cat")
            .withStdin("piped-in")
            .withTimeout(BEYOND_HANG_GUARD)
            .build();
    var result = run(provider, req);
    assertEquals("piped-in", result.stdout());
  }

  @Test
  void executeUsesProvidedWorkingDirectory(@TempDir Path tmp) throws Exception {
    assumeBashAvailable();
    Files.writeString(tmp.resolve("hello.txt"), "world");
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("cat hello.txt")
            .withWorkingDirectory(tmp)
            .withTimeout(BEYOND_HANG_GUARD)
            .build();
    var result = run(provider, req);
    assertEquals("world", result.stdout());
  }

  @Test
  void executeUsesTempCwdWhenWorkingDirectoryOmitted() {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("pwd")
            .withTimeout(BEYOND_HANG_GUARD)
            .build();
    var result = run(provider, req);
    assertEquals(0, result.exitCode());
    assertTrue(
        result.stdout().contains("helios-exec-"),
        "expected temp cwd in pwd output, got: " + result.stdout());
  }

  @Test
  void executeRejectsNullRequest() {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var ex =
        assertThrows(
            NullPointerException.class, () -> provider.execute(CTX, null, new CancellationToken()));
    assertEquals("request must not be null", ex.getMessage());
  }

  @Test
  void executeRejectsNullCancellation() {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req = ExecutionRequest.newBuilder().withRuntime(Runtime.BASH).withScript("true").build();
    var ex = assertThrows(NullPointerException.class, () -> provider.execute(CTX, req, null));
    assertEquals("cancellation must not be null", ex.getMessage());
  }

  @Test
  void executeFailsWhenCancellationAlreadyFiredBeforeAcquire() {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var token = new CancellationToken();
    token.cancel("pre-acquired");
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("true")
            .withTimeout(Duration.ofSeconds(1))
            .build();
    var failure =
        Await.failure(
            "the pre-cancelled call to fail",
            provider.execute(CTX, req, token).toCompletableFuture());
    assertInstanceOf(CancellationException.class, failure);
    assertEquals("pre-acquired", failure.getMessage());
  }

  @Test
  void executeOutputTruncatedPastCap() {
    assumeBashAvailable();
    var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withSecretRegistry(new SecretRegistry())
            .withMaxOutputBytes(1024)
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .build();
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("yes hello | head -c 5000")
            .withTimeout(BEYOND_HANG_GUARD)
            .build();
    var result = run(provider, req);
    assertTrue(result.stdout().contains("truncated"));
    assertTrue(result.stdout().length() < 2048);
  }

  @Test
  void executeRunsWithEmptyEnvironmentSoJvmSecretsDoNotLeak() {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("env | grep -E '^USER=|^HOME=' | wc -l | tr -d ' '")
            .withTimeout(BEYOND_HANG_GUARD)
            .build();
    var result = run(provider, req);
    assertEquals("0", result.stdout().trim());
  }

  @Test
  void multipleRequestsRunUnderTheSameProvider() {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var args = Map.of(1, "first", 2, "second", 3, "third");
    for (var e : args.entrySet()) {
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              .withScript("printf '%s' " + e.getValue())
              .withTimeout(BEYOND_HANG_GUARD)
              .build();
      var r = run(provider, req);
      assertEquals(e.getValue(), r.stdout());
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static ExecutionResult run(
      LocalProcessExecutionProvider provider, ExecutionRequest request) {
    return Await.value(
        "the result of `" + request.script() + "`",
        provider.execute(CTX, request, new CancellationToken()).toCompletableFuture());
  }

  private static void assumeBashAvailable() {
    assumeTrue(
        Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash")),
        "bash is not available on PATH; skipping subprocess tests");
  }

  private static void assumePythonAvailable() {
    try {
      BinaryResolver.resolve("python3", System.getenv("PATH"));
    } catch (RuntimeException e) {
      assumeTrue(false, "python3 is not available on PATH; skipping");
    }
  }
}
