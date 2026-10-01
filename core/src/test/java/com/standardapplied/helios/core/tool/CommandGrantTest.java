/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.singlr.core.common.SecretRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

@DisabledOnOs(OS.WINDOWS)
class CommandGrantTest {

  private static final Path BASH = Path.of("/bin/bash");
  private static final Path PRINTENV;

  static {
    var p1 = Path.of("/usr/bin/printenv");
    var p2 = Path.of("/bin/printenv");
    PRINTENV = Files.isExecutable(p1) ? p1 : (Files.isExecutable(p2) ? p2 : null);
  }

  @BeforeAll
  static void requireBash() {
    assumeTrue(Files.isExecutable(BASH), "/bin/bash is required");
  }

  private static CommandGrant.Builder bash() {
    return CommandGrant.builder(BASH.toString()).withSecretRegistry(new SecretRegistry());
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
    assertThrows(IllegalArgumentException.class, () -> CommandGrant.builder("").build());
  }

  @Test
  void specWithSeparatorButRelativeRejected() {
    assertThrows(IllegalArgumentException.class, () -> CommandGrant.builder("dir/binary").build());
  }

  @Test
  void simpleEcho() throws Exception {
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "echo hello"));
    assertEquals(0, result.exitCode());
    assertEquals("hello\n", result.stdout());
    assertEquals("", result.stderr());
    assertFalse(result.timedOut());
    assertFalse(result.truncated());
  }

  @Test
  void exitCodePreserved() throws Exception {
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "exit 7"));
    assertEquals(7, result.exitCode());
  }

  @Test
  void stderrCapturedSeparately() throws Exception {
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "echo err 1>&2; echo out"));
    assertEquals(0, result.exitCode());
    assertEquals("out\n", result.stdout());
    assertEquals("err\n", result.stderr());
  }

  @Test
  void envVarPassedToChild() throws Exception {
    var grant = bash().withEnv("GREETING", "hello1234").build();
    var result = grant.invoke(List.of("-c", "echo $GREETING"));
    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("<redacted:GREETING>"));
    assertFalse(result.stdout().contains("hello1234"));
  }

  @Test
  void jvmEnvNotInheritedByChild() throws Exception {
    assumeTrue(System.getenv("USER") != null, "USER env var required to assert non-inheritance");
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "echo USER=${USER:-unset}"));
    assertEquals(0, result.exitCode());
    assertEquals("USER=unset\n", result.stdout());
  }

  @Test
  void secretInArgvRefused() {
    var grant = bash().withEnv("TOKEN", "supersecret123").build();
    assertThrows(
        CommandGrant.RejectedException.class,
        () -> grant.invoke(List.of("-c", "echo prefix-supersecret123-suffix")));
  }

  @Test
  void argValidatorRejectionPropagated() {
    var grant =
        bash()
            .withArgValidator(
                args -> args.size() < 2 ? Optional.of("need at least 2 args") : Optional.empty())
            .build();
    var ex = assertThrows(CommandGrant.RejectedException.class, () -> grant.invoke(List.of("-c")));
    assertTrue(ex.getMessage().contains("need at least 2"));
  }

  @Test
  void argValidatorAcceptanceAllows() throws Exception {
    var grant = bash().withArgValidator(args -> Optional.empty()).build();
    var result = grant.invoke(List.of("-c", "echo ok"));
    assertEquals(0, result.exitCode());
  }

  @Test
  void timeoutKillsProcessTree() throws Exception {
    var grant = bash().withTimeout(Duration.ofMillis(300)).build();
    var start = System.nanoTime();
    var result = grant.invoke(List.of("-c", "sleep 30"));
    var elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertTrue(result.timedOut());
    assertEquals(-1, result.exitCode());
    assertTrue(elapsedMs < 5_000, "elapsed=" + elapsedMs + "ms");
  }

  @Test
  void outputCapTruncatesAndMarks() throws Exception {
    var grant = bash().withMaxOutputBytes(2048).build();
    var result = grant.invoke(List.of("-c", "head -c 100000 /dev/zero | tr '\\0' 'x'"));
    assertTrue(result.truncated());
    assertTrue(result.stdout().contains("[truncated"));
    assertTrue(result.stdout().length() < 4096);
  }

  @Test
  void secretInStdoutRedactedViaEnvAuto() throws Exception {
    var grant = bash().withEnv("MY_TOKEN", "abcdefgh1234").build();
    var result = grant.invoke(List.of("-c", "echo $MY_TOKEN"));
    assertFalse(result.stdout().contains("abcdefgh1234"));
    assertTrue(result.stdout().contains("<redacted:MY_TOKEN>"));
    assertEquals(1, (int) result.redactionCounts().get("MY_TOKEN"));
    assertEquals(1, result.totalRedactions());
  }

  @Test
  void secretInStderrRedacted() throws Exception {
    var grant = bash().withEnv("MY_TOKEN", "abcdefgh1234").build();
    var result = grant.invoke(List.of("-c", "echo $MY_TOKEN 1>&2"));
    assertFalse(result.stderr().contains("abcdefgh1234"));
    assertTrue(result.stderr().contains("<redacted:MY_TOKEN>"));
  }

  @Test
  void sharedRegistryRedactsAcrossGrants() throws Exception {
    var registry = new SecretRegistry();
    var producer =
        CommandGrant.builder(BASH.toString())
            .withSecretRegistry(registry)
            .withEnv("SHARED_TOKEN", "sharedvalue123")
            .build();
    var consumer = CommandGrant.builder(BASH.toString()).withSecretRegistry(registry).build();
    var result =
        consumer.invoke(List.of("-c", "A=share; B=value123; echo prefix-${A}d${B}-suffix"));
    assertFalse(result.stdout().contains("sharedvalue123"));
    assertTrue(result.stdout().contains("<redacted:SHARED_TOKEN>"));
    assertNotNull(producer);
  }

  @Test
  void stderrHiddenFromModelByDefault() throws Exception {
    var grant = bash().build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of("-c", "echo err 1>&2; echo out")));
    assertTrue(result.success());
    assertTrue(result.output().contains("out"));
    assertFalse(result.output().contains("err"));
  }

  @Test
  void stderrToModelOptInExposesStderr() throws Exception {
    var grant = bash().withStderrToModel(true).build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of("-c", "echo err 1>&2; echo out")));
    assertTrue(result.output().contains("out"));
    assertTrue(result.output().contains("err"));
    assertTrue(result.output().contains("[stderr]"));
  }

  @Test
  void toolMissingArgsParameter() {
    var grant = bash().build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of());
    assertFalse(result.success());
    assertTrue(result.output().contains("array of strings"));
  }

  @Test
  void toolNonStringEntryRejected() {
    var grant = bash().build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of(1, 2)));
    assertFalse(result.success());
    assertTrue(result.output().contains("must be a string"));
  }

  @Test
  void toolReportsTimeoutInOutput() {
    var grant = bash().withTimeout(Duration.ofMillis(200)).build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of("-c", "sleep 30")));
    assertTrue(result.output().contains("TIMEOUT"));
    assertTrue(result.success());
  }

  @Test
  void toolReportsTruncationInOutput() {
    var grant = bash().withMaxOutputBytes(1024).build();
    var tool = grant.toTool();
    var result =
        tool.execute(Map.of("args", List.of("-c", "head -c 50000 /dev/zero | tr '\\0' 'x'")));
    assertTrue(result.output().contains("TRUNCATED"));
  }

  @Test
  void toolWrapsRejectedAsFailure() {
    var grant = bash().withArgValidator(args -> Optional.of("nope")).build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of("-c", "echo hi")));
    assertFalse(result.success());
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
  void cwdSetExplicitlyHonored(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
    var grant = bash().withCwd(tmp).build();
    var result = grant.invoke(List.of("-c", "pwd"));
    var expected = tmp.toRealPath().toString();
    assertTrue(
        result.stdout().trim().endsWith(expected) || result.stdout().trim().equals(expected),
        "expected cwd=" + expected + " got=" + result.stdout());
  }

  @Test
  void perCallTempCwdCleanedUp() throws Exception {
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "pwd && echo > marker"));
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
  void privateRegistryCreatedWhenNotProvided() {
    var grant = CommandGrant.builder(BASH.toString()).withEnv("X", "valuevalue1").build();
    assertNotNull(grant.secretRegistry());
    assertTrue(grant.secretRegistry().leaks("valuevalue1"));
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
  void invocationResultRecordContainsDuration() throws Exception {
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "true"));
    assertNotNull(result.duration());
    assertTrue(result.duration().toNanos() > 0);
  }

  @Test
  void redactionCountsImmutable() throws Exception {
    var grant = bash().withEnv("TOKEN", "abcdefgh1234").build();
    var result = grant.invoke(List.of("-c", "echo $TOKEN"));
    assertThrows(UnsupportedOperationException.class, () -> result.redactionCounts().put("X", 1));
  }

  @Test
  void totalRedactionsZeroWhenNothingRedacted() throws Exception {
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "echo nothing"));
    assertEquals(0, result.totalRedactions());
    assertTrue(result.redactionCounts().isEmpty());
  }

  @Test
  void customPathRespected() throws Exception {
    var grant = bash().withPath("/usr/bin:/bin").build();
    var result = grant.invoke(List.of("-c", "echo $PATH"));
    assertEquals("/usr/bin:/bin\n", result.stdout());
  }

  @Test
  void concurrencyLimitOneEnforced() throws Exception {
    var grant = bash().withMaxConcurrent(1).withTimeout(Duration.ofSeconds(10)).build();
    var t1 =
        Thread.startVirtualThread(
            () -> {
              try {
                grant.invoke(List.of("-c", "sleep 5"));
              } catch (Exception ignored) {
              }
            });
    Thread.sleep(150);
    var ex =
        assertThrows(
            CommandGrant.RejectedException.class, () -> grant.invoke(List.of("-c", "echo hi")));
    assertTrue(ex.getMessage().contains("Concurrency"));
    t1.interrupt();
    t1.join();
  }

  @Test
  void argvNeverInheritsShellExpansion() throws Exception {
    var grant = bash().build();
    var result = grant.invoke(List.of("-c", "echo \"$0 $1 $2\"", "BIN", "ONE", "TWO"));
    assertEquals(0, result.exitCode());
    assertEquals("BIN ONE TWO\n", result.stdout());
  }

  @Test
  void secretRegistryConflictAcrossEnvOverwritesValue() throws Exception {
    var registry = new SecretRegistry();
    registry.register("TOKEN", "originalvalue");
    var grant =
        CommandGrant.builder(BASH.toString())
            .withSecretRegistry(registry)
            .withEnv("TOKEN", "newervalue1")
            .build();
    var result = grant.invoke(List.of("-c", "echo originalvalue"));
    assertFalse(result.stdout().contains("<redacted"));
    assertNotNull(grant);
  }

  @Test
  void redactionAcrossLargeOutput() throws Exception {
    var grant = bash().withEnv("TOKEN", "needleneedle").build();
    var result = grant.invoke(List.of("-c", "for i in 1 2 3; do echo padding-$TOKEN-$i; done"));
    assertFalse(result.stdout().contains("needleneedle"));
    assertEquals(3, (int) result.redactionCounts().get("TOKEN"));
  }

  @Test
  void stderrToModelButStderrEmpty() throws Exception {
    var grant = bash().withStderrToModel(true).build();
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of("-c", "echo only-stdout")));
    assertTrue(result.success());
    assertTrue(result.output().contains("only-stdout"));
    assertFalse(result.output().contains("[stderr]"));
  }

  @Test
  void stderrTruncatedAlone() throws Exception {
    var grant = bash().withMaxOutputBytes(1024).build();
    var result =
        grant.invoke(List.of("-c", "head -c 100000 /dev/zero | tr '\\0' 'x' 1>&2; echo ok"));
    assertTrue(result.truncated());
    assertEquals("ok\n", result.stdout());
  }

  @Test
  void binaryRegularFileButNotExecutable(@org.junit.jupiter.api.io.TempDir Path tmp)
      throws Exception {
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
    var start = System.nanoTime();
    var result = grant.invoke(List.of("-c", "trap '' TERM; sleep 30"));
    var elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertTrue(result.timedOut());
    assertEquals(-1, result.exitCode());
    assertTrue(elapsedMs < 6_000, "elapsed=" + elapsedMs + "ms");
  }

  @Test
  void resolveBinaryNullPathRejected() {
    var ex =
        assertThrows(IllegalStateException.class, () -> CommandGrant.resolveBinary("bash", null));
    assertTrue(ex.getMessage().contains("PATH is empty"));
  }

  @Test
  void resolveBinaryEmptyPathRejected() {
    assertThrows(IllegalStateException.class, () -> CommandGrant.resolveBinary("bash", ""));
  }

  @Test
  void resolveBinarySkipsEmptyPathEntries() {
    var resolved = CommandGrant.resolveBinary("bash", ":/bin:");
    assertTrue(resolved.toString().endsWith("bash"));
  }

  @Test
  void resolveBinaryFallsThroughNonExecutableMatch(@org.junit.jupiter.api.io.TempDir Path tmp)
      throws Exception {
    var fake = tmp.resolve("bash");
    Files.writeString(fake, "not executable");
    var pathEnv = tmp.toString() + ":/bin";
    var resolved = CommandGrant.resolveBinary("bash", pathEnv);
    assertEquals(BASH.toAbsolutePath(), resolved);
  }

  @Test
  void interruptedDuringInvokeReturnsFailureToTool() throws Exception {
    var grant = bash().withTimeout(Duration.ofSeconds(10)).build();
    var tool = grant.toTool();
    var result = new java.util.concurrent.atomic.AtomicReference<ToolResult>();
    var t =
        Thread.startVirtualThread(
            () -> result.set(tool.execute(Map.of("args", List.of("-c", "sleep 5")))));
    Thread.sleep(150);
    t.interrupt();
    t.join();
    assertNotNull(result.get());
    assertFalse(result.get().success());
    assertTrue(result.get().output().contains("Interrupted"));
  }

  @Test
  void invokeWithIOErrorWrapped(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
    var fakeBin = tmp.resolve("ephemeral");
    Files.writeString(fakeBin, "#!/bin/sh\necho hi\n");
    fakeBin.toFile().setExecutable(true);
    var grant =
        CommandGrant.builder(fakeBin.toString()).withSecretRegistry(new SecretRegistry()).build();
    Files.delete(fakeBin);
    var tool = grant.toTool();
    var result = tool.execute(Map.of("args", List.of()));
    assertFalse(result.success());
    assertTrue(result.output().contains("I/O error"));
  }
}
