/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.PidFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class CommandGrantTest {

  private static final Path BASH = Path.of("/bin/bash");
  private static final Path PRINTENV;
  private static final Duration NEVER_REACHED = Duration.ofMinutes(10);
  private static final String SECRET = "sk-test-0123456789abcdef";
  private static final CancellationToken NEVER_CANCELLED = new CancellationToken();

  /**
   * A command that cannot finish on its own: it marks its working directory once it runs, then
   * waits for the test to create {@code release} there. A test that interrupts the invocation
   * releases the command afterwards, and the command also stops once its directory is deleted, so
   * no child process outlives the test.
   */
  private static final String RUN_UNTIL_RELEASED =
      ": > started; until [ -e release ] || [ ! -e started ]; do sleep 0.02; done";

  static {
    var p1 = Path.of("/usr/bin/printenv");
    var p2 = Path.of("/bin/printenv");
    PRINTENV = Files.isExecutable(p1) ? p1 : (Files.isExecutable(p2) ? p2 : null);
  }

  /**
   * A test that fails before its children are reaped must not leave one running; nothing else in
   * this JVM starts a process while a test of this class runs.
   */
  @AfterEach
  void noChildOutlivesItsTest() {
    ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
  }

  @BeforeAll
  static void requireBash() {
    assumeTrue(Files.isExecutable(BASH), "/bin/bash is required");
  }

  private static CommandGrant.Builder bash() {
    return CommandGrant.builder(BASH.toString()).withSecretRegistry(new SecretRegistry());
  }

  /** A tool context for a session whose registry is {@code secrets}. */
  private static ToolContext sessionWith(SecretRegistry secrets) {
    return callIn(secrets, new CancellationToken());
  }

  /**
   * A tool call whose token is {@code cancellation}, in a session whose registry is {@code
   * secrets}.
   */
  private static ToolContext callIn(SecretRegistry secrets, CancellationToken cancellation) {
    return new ToolContext(
        new SessionContext("grant-session", new CancellationToken(), Clock.systemUTC(), secrets),
        cancellation,
        NEVER_REACHED,
        key -> false);
  }

  /** A tool context for a session that uses the grant's own registry. */
  private static ToolContext sessionOf(CommandGrant grant) {
    return sessionWith(grant.secretRegistry());
  }

  private static void awaitStarted(Path cwd) {
    Await.until("the command to start", () -> Files.exists(cwd.resolve("started")));
  }

  private static void release(Path cwd) throws IOException {
    Files.createFile(cwd.resolve("release"));
  }

  @Test
  void absolutePathBinaryUsedAsIs() throws Exception {
    var grant = bash().build();
    assertEquals(BASH.toAbsolutePath(), grant.binaryPath());
  }

  @Test
  void basenameResolvedAgainstPath() {
    var grant = CommandGrant.builder("bash").withSecretRegistry(new SecretRegistry()).build();
    assertTrue(grant.binaryPath().toString().endsWith("bash"));
    assertTrue(grant.binaryPath().isAbsolute());
  }

  @Test
  void nonexistentBinaryRejectedAtBuild() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                CommandGrant.builder("/nonexistent/path/zzzzzz")
                    .withSecretRegistry(new SecretRegistry())
                    .build());
    assertTrue(ex.getMessage().contains("not executable"));
  }

  @Test
  void unfindableBasenameRejectedAtBuild() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                CommandGrant.builder("definitely-not-a-real-tool-xyz123")
                    .withSecretRegistry(new SecretRegistry())
                    .build());
    assertTrue(ex.getMessage().contains("not found on PATH"));
  }

  @Test
  void blankSpecRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CommandGrant.builder("").withSecretRegistry(new SecretRegistry()).build());
  }

  @Test
  void specWithSeparatorButRelativeRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CommandGrant.builder("dir/binary").withSecretRegistry(new SecretRegistry()).build());
  }

  @Test
  void buildWithoutRegistryNamesWhatToPass() {
    var ex = assertThrows(IllegalStateException.class, () -> CommandGrant.builder("bash").build());
    assertEquals(
        "CommandGrant bash needs withSecretRegistry(registry): pass the SecretRegistry of the"
            + " sessions that will run it",
        ex.getMessage());
  }

  @Test
  void toolRefusesASessionWithAnotherRegistryWithoutStartingAProcess(@TempDir Path tmp) {
    var grant = bash().withCwd(tmp).build();

    var result =
        grant
            .toTool()
            .execute(
                Map.of("args", List.of("-c", ": > marker")), sessionWith(new SecretRegistry()));

    assertFalse(result.succeeded());
    assertEquals(
        "CommandGrant bash was built with a different SecretRegistry than session grant-session;"
            + " build it with the registry passed to SessionOptions.withSecretRegistry",
        result.output());
    assertFalse(Files.exists(tmp.resolve("marker")));
  }

  @Test
  void toolRunInTheGrantsOwnRegistryStartsTheProcess(@TempDir Path tmp) {
    var grant = bash().withCwd(tmp).build();

    var result =
        grant.toTool().execute(Map.of("args", List.of("-c", ": > marker")), sessionOf(grant));

    assertTrue(result.succeeded(), result.output());
    assertTrue(Files.exists(tmp.resolve("marker")));
  }

  @Test
  void nullRegistryRefusedWhereItIsPassed() {
    var builder = CommandGrant.builder("bash");

    var ex = assertThrows(NullPointerException.class, () -> builder.withSecretRegistry(null));
    assertEquals("registry", ex.getMessage());
  }

  @Test
  void outputCutInsideASecretKeepsNoPartOfIt() throws Exception {
    var registry = new SecretRegistry();
    registry.register("API_KEY", SECRET);
    var grant = bash().withSecretRegistry(registry).withMaxOutputBytes(1024).build();

    var result =
        grant.invoke(
            List.of(
                "-c",
                "head -c 1014 /dev/zero | tr '\\0' 'x'; printf %s%s \"$1\" \"$2\";"
                    + " printf %s \"tail $1\" >&2",
                "BIN",
                SECRET.substring(0, 8),
                SECRET.substring(8)),
            NEVER_CANCELLED);

    assertEquals("x".repeat(1014) + "\n[truncated: output exceeded cap]", result.stdout());
    assertEquals("tail sk-test-", result.stderr());
  }

  @Test
  void simpleEcho() throws Exception {
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "echo hello"), NEVER_CANCELLED);
    assertEquals(0, result.exitCode());
    assertEquals("hello\n", result.stdout());
    assertEquals("", result.stderr());
    assertFalse(result.timedOut());
    assertFalse(result.truncated());
  }

  @Test
  void exitCodePreserved() throws Exception {
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "exit 7"), NEVER_CANCELLED);
    assertEquals(7, result.exitCode());
  }

  @Test
  void stderrCapturedSeparately() throws Exception {
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "echo err 1>&2; echo out"), NEVER_CANCELLED);
    assertEquals(0, result.exitCode());
    assertEquals("out\n", result.stdout());
    assertEquals("err\n", result.stderr());
  }

  @Test
  void envVarPassedToChild() throws Exception {
    var grant = bash().withEnv("GREETING", "hello1234").build();
    var result = grant.invoke(List.of("-c", "echo $GREETING"), NEVER_CANCELLED);
    assertEquals(0, result.exitCode());
    assertEquals("hello1234\n", result.stdout());
  }

  @Test
  void jvmEnvNotInheritedByChild() throws Exception {
    assumeTrue(System.getenv("USER") != null, "USER env var required to assert non-inheritance");
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "echo USER=${USER:-unset}"), NEVER_CANCELLED);
    assertEquals(0, result.exitCode());
    assertEquals("USER=unset\n", result.stdout());
  }

  @Test
  void secretInArgvRefused() {
    var grant = bash().withEnv("TOKEN", "supersecret123").build();
    assertThrows(
        CommandGrant.RejectedException.class,
        () -> grant.invoke(List.of("-c", "echo prefix-supersecret123-suffix"), NEVER_CANCELLED));
  }

  @Test
  void argValidatorRejectionPropagated() {
    var grant =
        bash()
            .withArgValidator(
                args -> args.size() < 2 ? Optional.of("need at least 2 args") : Optional.empty())
            .build();
    var ex =
        assertThrows(
            CommandGrant.RejectedException.class,
            () -> grant.invoke(List.of("-c"), NEVER_CANCELLED));
    assertTrue(ex.getMessage().contains("need at least 2"));
  }

  @Test
  void argValidatorAcceptanceAllows() throws Exception {
    var grant = bash().withArgValidator(args -> Optional.empty()).build();
    var result = grant.invoke(List.of("-c", "echo ok"), NEVER_CANCELLED);
    assertEquals(0, result.exitCode());
  }

  @Test
  void timeoutKillsProcessTree() throws Exception {
    var grant = bash().withTimeout(Duration.ofMillis(300)).build();
    var result = grant.invoke(List.of("-c", "exec sleep 600"), NEVER_CANCELLED);
    assertTrue(result.timedOut());
    assertEquals(-1, result.exitCode());
  }

  @Test
  void outputCapTruncatesAndMarks() throws Exception {
    var grant = bash().withMaxOutputBytes(2048).build();
    var result =
        grant.invoke(List.of("-c", "head -c 100000 /dev/zero | tr '\\0' 'x'"), NEVER_CANCELLED);
    assertTrue(result.truncated());
    assertTrue(result.stdout().contains("[truncated"));
    assertTrue(result.stdout().length() < 4096);
  }

  @Test
  void stderrHiddenFromModelByDefault() throws Exception {
    var grant = bash().build();
    var tool = grant.toTool();
    var result =
        tool.execute(Map.of("args", List.of("-c", "echo err 1>&2; echo out")), sessionOf(grant));
    assertTrue(result.succeeded());
    assertTrue(result.output().contains("out"));
    assertFalse(result.output().contains("err"));
  }

  @Test
  void stderrToModelOptInExposesStderr() throws Exception {
    var grant = bash().withStderrToModel(true).build();
    var tool = grant.toTool();
    var result =
        tool.execute(Map.of("args", List.of("-c", "echo err 1>&2; echo out")), sessionOf(grant));
    assertTrue(result.output().contains("out"));
    assertTrue(result.output().contains("err"));
    assertTrue(result.output().contains("[stderr]"));
  }

  @Test
  void toolMissingArgsParameter() {
    var grant = bash().build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of(), sessionOf(grant));
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("array of strings"));
  }

  @Test
  void toolNonStringEntryRejected() {
    var grant = bash().build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of(1, 2)), sessionOf(grant));
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("must be a string"));
  }

  @Test
  void toolReportsTimeoutInOutput() {
    var grant = bash().withTimeout(Duration.ofMillis(200)).build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of("-c", "sleep 30")), sessionOf(grant));
    assertTrue(result.output().contains("TIMEOUT"));
    assertTrue(result.succeeded());
  }

  @Test
  void toolReportsTruncationInOutput() {
    var grant = bash().withMaxOutputBytes(1024).build();
    var tool = grant.toTool();
    var result =
        tool.execute(
            Map.of("args", List.of("-c", "head -c 50000 /dev/zero | tr '\\0' 'x'")),
            sessionOf(grant));
    assertTrue(result.output().contains("TRUNCATED"));
  }

  @Test
  void toolWrapsRejectedAsFailure() {
    var grant = bash().withArgValidator(args -> Optional.of("nope")).build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of("-c", "echo hi")), sessionOf(grant));
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("nope"));
  }

  @Test
  void toolNameDefaultsToBinaryBasename() {
    assertEquals("bash", bash().build().name());
  }

  @Test
  void toolNameOverride() {
    var grant = bash().withName("shell").build();
    assertEquals("shell", grant.name());
    assertEquals("shell", grant.toTool().name());
  }

  @Test
  void descriptionOverride() {
    var grant = bash().withName("shell").withDescription("Run a shell snippet").build();
    assertEquals("Run a shell snippet", grant.toTool().description());
  }

  @Test
  void cwdSetExplicitlyHonored(@TempDir Path tmp) throws Exception {
    var grant = bash().withCwd(tmp).build();
    var result = grant.invoke(List.of("-c", "pwd"), NEVER_CANCELLED);
    var expected = tmp.toRealPath().toString();
    assertTrue(
        result.stdout().trim().endsWith(expected) || result.stdout().trim().equals(expected),
        "expected cwd=" + expected + " got=" + result.stdout());
  }

  @Test
  void perCallTempCwdCleanedUp() throws Exception {
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "pwd && echo > marker"), NEVER_CANCELLED);
    assertEquals(0, result.exitCode());
    var tmpPathLine = result.stdout().split("\n")[0];
    var tmpPath = Path.of(tmpPathLine);
    assertFalse(Files.exists(tmpPath), "temp cwd should be removed: " + tmpPath);
  }

  @Test
  void secretRegistryAccessor() {
    var registry = new SecretRegistry();
    var grant = bash().withSecretRegistry(registry).build();
    assertTrue(grant.secretRegistry() == registry);
  }

  @Test
  void blankEnvNameRefused() {
    assertThrows(IllegalArgumentException.class, () -> bash().withEnv("  ", "validvalue1234"));
  }

  @Test
  void nullEnvValueRefused() {
    assertThrows(IllegalArgumentException.class, () -> bash().withEnv("X", null));
  }

  @Test
  void zeroOrNegativeTimeoutRefused() {
    assertThrows(IllegalArgumentException.class, () -> bash().withTimeout(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> bash().withTimeout(Duration.ofSeconds(-1)));
  }

  @Test
  void nullTimeoutRefused() {
    assertThrows(IllegalArgumentException.class, () -> bash().withTimeout(null));
  }

  @Test
  void tooSmallMaxOutputRefused() {
    assertThrows(IllegalArgumentException.class, () -> bash().withMaxOutputBytes(100));
  }

  @Test
  void tooSmallMaxConcurrentRefused() {
    assertThrows(IllegalArgumentException.class, () -> bash().withMaxConcurrent(0));
  }

  @Test
  void envVarNamesExposed() {
    var grant = bash().withEnv("A", "valuevalue1").withEnv("B", "valuevalue2").build();
    assertEquals(java.util.Set.of("A", "B"), grant.envVarNames());
  }

  @Test
  void customPathRespected() throws Exception {
    var grant = bash().withPath("/usr/bin:/bin").build();
    var result = grant.invoke(List.of("-c", "echo $PATH"), NEVER_CANCELLED);
    assertEquals("/usr/bin:/bin\n", result.stdout());
  }

  @Test
  void concurrencyLimitOneEnforced(@TempDir Path tmp) throws Exception {
    var grant = bash().withMaxConcurrent(1).withCwd(tmp).withTimeout(NEVER_REACHED).build();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(() -> grant.invoke(List.of("-c", RUN_UNTIL_RELEASED), NEVER_CANCELLED));
      awaitStarted(tmp);

      var ex =
          assertThrows(
              CommandGrant.RejectedException.class,
              () -> grant.invoke(List.of("-c", "echo hi"), NEVER_CANCELLED));
      assertTrue(ex.getMessage().contains("Concurrency"));

      release(tmp);
      assertEquals(0, Await.value("the released first invocation", first).exitCode());
    }
  }

  @Test
  void argvNeverInheritsShellExpansion() throws Exception {
    var grant = bash().build();
    var result =
        grant.invoke(List.of("-c", "echo \"$0 $1 $2\"", "BIN", "ONE", "TWO"), NEVER_CANCELLED);
    assertEquals(0, result.exitCode());
    assertEquals("BIN ONE TWO\n", result.stdout());
  }

  @Test
  void secretRegistryConflictAcrossEnvOverwritesValue() {
    var registry = new SecretRegistry();
    registry.register("TOKEN", "originalvalue");
    var grant =
        CommandGrant.builder(BASH.toString())
            .withSecretRegistry(registry)
            .withEnv("TOKEN", "newervalue1")
            .build();
    assertTrue(grant.secretRegistry().leaks("newervalue1"));
    assertFalse(grant.secretRegistry().leaks("originalvalue"));
  }

  @Test
  void stderrToModelButStderrEmpty() throws Exception {
    var grant = bash().withStderrToModel(true).build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of("-c", "echo only-stdout")), sessionOf(grant));
    assertTrue(result.succeeded());
    assertTrue(result.output().contains("only-stdout"));
    assertFalse(result.output().contains("[stderr]"));
  }

  @Test
  void stderrTruncatedAlone() throws Exception {
    var grant = bash().withMaxOutputBytes(1024).build();
    var result =
        grant.invoke(
            List.of("-c", "head -c 100000 /dev/zero | tr '\\0' 'x' 1>&2; echo ok"),
            NEVER_CANCELLED);
    assertTrue(result.truncated());
    assertEquals("ok\n", result.stdout());
  }

  @Test
  void binaryRegularFileButNotExecutable(@TempDir Path tmp) throws Exception {
    var notExec = tmp.resolve("notexec");
    Files.writeString(notExec, "not actually executable");
    var ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                CommandGrant.builder(notExec.toString())
                    .withSecretRegistry(new SecretRegistry())
                    .build());
    assertTrue(ex.getMessage().contains("not executable"));
  }

  @Test
  void forciblyKilledProcessIgnoringSigterm() throws Exception {
    var grant = bash().withTimeout(Duration.ofMillis(300)).build();
    var result = grant.invoke(List.of("-c", "trap '' TERM; exec sleep 600"), NEVER_CANCELLED);
    assertTrue(result.timedOut());
    assertEquals(-1, result.exitCode());
  }

  @Test
  void interruptedDuringInvokeReturnsFailureToTool(@TempDir Path tmp) throws Exception {
    var grant = bash().withCwd(tmp).withTimeout(NEVER_REACHED).build();
    var tool = grant.toTool();
    var result = new CompletableFuture<ToolResult>();
    var invoker =
        Thread.startVirtualThread(
            () ->
                result.complete(
                    tool.execute(
                        Map.of("args", List.of("-c", RUN_UNTIL_RELEASED)), sessionOf(grant))));
    awaitStarted(tmp);

    invoker.interrupt();

    var interrupted = Await.value("the interrupted invocation", result);
    release(tmp);
    assertFalse(interrupted.succeeded());
    assertTrue(interrupted.output().contains("Interrupted"));
  }

  @Test
  void cancellingTheCallKillsTheChildAndFreesThePermit(@TempDir Path tmp) throws Exception {
    var grant = bash().withMaxConcurrent(1).withCwd(tmp).withTimeout(NEVER_REACHED).build();
    var tool = grant.toTool();
    var token = new CancellationToken();
    var result =
        CompletableFuture.supplyAsync(
            () ->
                tool.execute(
                    Map.of("args", List.of("-c", "echo $$ > pid; exec sleep 600")),
                    callIn(grant.secretRegistry(), token)));
    var child = PidFile.await("the child to start", tmp.resolve("pid"));
    try {
      token.cancel("test");

      var cancelled = Await.value("the cancelled invocation", result);
      assertFalse(cancelled.succeeded());
      assertEquals("Cancelled while invoking bash", cancelled.output());
      Await.value("the killed child to end", child.onExit());
      assertTrue(
          tool.execute(Map.of("args", List.of("-c", "echo again")), sessionOf(grant)).succeeded());
    } finally {
      child.destroyForcibly();
    }
  }

  @Test
  void invokeWithIOErrorWrapped(@TempDir Path tmp) throws Exception {
    var fakeBin = tmp.resolve("ephemeral");
    Files.writeString(fakeBin, "#!/bin/sh\necho hi\n");
    fakeBin.toFile().setExecutable(true);
    var grant =
        CommandGrant.builder(fakeBin.toString()).withSecretRegistry(new SecretRegistry()).build();
    Files.delete(fakeBin);
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of()), sessionOf(grant));
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("I/O error"));
  }
}
