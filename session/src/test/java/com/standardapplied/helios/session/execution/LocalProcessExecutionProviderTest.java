/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.singlr.core.common.SecretRegistry;
import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import ai.singlr.core.tool.CommandGrant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalProcessExecutionProviderTest {

  private static final SessionContext CTX = SessionContext.forTesting("provider-test");

  // ── Builder validation ────────────────────────────────────────────────────

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
  void executeReturnsRefusalForUnsupportedRuntime()
      throws ExecutionException, InterruptedException, TimeoutException {
    assumeBashAvailable();
    var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .build();
    var req =
        ExecutionRequest.newBuilder().withRuntime(Runtime.PYTHON).withScript("print(1)").build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
    assertEquals(-1, result.exitCode());
    assertTrue(result.stderr().contains("not supported"));
    assertFalse(result.timedOut());
  }

  // ── successful execution ─────────────────────────────────────────────────

  @Test
  void executeCapturesStdout() throws Exception {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("printf hello")
            .withTimeout(Duration.ofSeconds(5))
            .build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertEquals(0, result.exitCode());
    assertEquals("hello", result.stdout());
    assertEquals("", result.stderr());
    assertFalse(result.timedOut());
  }

  @Test
  void executeCapturesStderrAndExitCode() throws Exception {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("echo oops >&2; exit 3")
            .withTimeout(Duration.ofSeconds(5))
            .build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertEquals(3, result.exitCode());
    assertTrue(result.stderr().contains("oops"));
  }

  @Test
  void executeRespectsTimeout() throws Exception {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("sleep 5")
            .withTimeout(Duration.ofMillis(150))
            .build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertTrue(result.timedOut());
    assertEquals(-1, result.exitCode());
  }

  @Test
  void executeClampsRequestTimeoutToCapabilitiesMax() throws Exception {
    assumeBashAvailable();
    var provider =
        LocalProcessExecutionProvider.newBuilder()
            .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
            .withMaxTimeout(Duration.ofMillis(150))
            .build();
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("sleep 5")
            .withTimeout(Duration.ofMinutes(10))
            .build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertTrue(result.timedOut());
  }

  @Test
  void executeRedactsSecretsInStdout() throws Exception {
    assumeBashAvailable();
    var registry = new SecretRegistry();
    registry.register("TOKEN", "supersecret123");
    var provider = LocalProcessExecutionProvider.defaultPosix(registry);
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("printf supersecret123")
            .withTimeout(Duration.ofSeconds(5))
            .build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertEquals("<redacted:TOKEN>", result.stdout());
    assertEquals(Integer.valueOf(1), result.secretRedactionCounts().get("TOKEN"));
  }

  @Test
  void executeInjectsEnvironment() throws Exception {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("printf '%s' \"$GREETING\"")
            .withEnv("GREETING", "ahoy")
            .withTimeout(Duration.ofSeconds(5))
            .build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertEquals(0, result.exitCode());
    assertEquals("ahoy", result.stdout());
  }

  @Test
  void executeFeedsStdin() throws Exception {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("cat")
            .withStdin("piped-in")
            .withTimeout(Duration.ofSeconds(5))
            .build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
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
            .withTimeout(Duration.ofSeconds(5))
            .build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertEquals("world", result.stdout());
  }

  @Test
  void executeUsesTempCwdWhenWorkingDirectoryOmitted() throws Exception {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("pwd")
            .withTimeout(Duration.ofSeconds(5))
            .build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
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
    var future = provider.execute(CTX, req, token).toCompletableFuture();
    var ex = assertThrows(CancellationException.class, future::join);
    assertNotNull(ex.getMessage());
  }

  @Test
  void executeOutputTruncatedPastCap() throws Exception {
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
            .withTimeout(Duration.ofSeconds(5))
            .build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertTrue(result.stdout().contains("truncated"));
    assertTrue(result.stdout().length() < 2048);
  }

  @Test
  void executeRunsWithEmptyEnvironmentSoJvmSecretsDoNotLeak() throws Exception {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var req =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("env | grep -E '^USER=|^HOME=' | wc -l | tr -d ' '")
            .withTimeout(Duration.ofSeconds(5))
            .build();
    var result =
        provider
            .execute(CTX, req, new CancellationToken())
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    assertEquals("0", result.stdout().trim());
  }

  @Test
  void multipleRequestsRunUnderTheSameProvider() throws Exception {
    assumeBashAvailable();
    var provider = LocalProcessExecutionProvider.defaultPosix(new SecretRegistry());
    var args = Map.of(1, "first", 2, "second", 3, "third");
    for (var e : args.entrySet()) {
      var req =
          ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              .withScript("printf '%s' " + e.getValue())
              .withTimeout(Duration.ofSeconds(5))
              .build();
      var r =
          provider
              .execute(CTX, req, new CancellationToken())
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      assertEquals(e.getValue(), r.stdout());
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private static void assumeBashAvailable() {
    assumeTrue(
        Files.isExecutable(Path.of("/bin/bash")) || Files.isExecutable(Path.of("/usr/bin/bash")),
        "bash is not available on PATH; skipping subprocess tests");
  }

  private static void assumePythonAvailable() {
    try {
      CommandGrant.resolveBinary("python3", System.getenv("PATH"));
    } catch (RuntimeException e) {
      assumeTrue(false, "python3 is not available on PATH; skipping");
    }
  }
}
