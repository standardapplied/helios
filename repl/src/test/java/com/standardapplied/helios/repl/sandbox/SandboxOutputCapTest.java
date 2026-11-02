/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static com.standardapplied.helios.repl.protocol.ProcessTransport.MAX_CAPTURED_OUTPUT_CHARS;
import static com.standardapplied.helios.repl.protocol.ProcessTransport.MAX_FRAME_CHARS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.repl.ReplConfig;
import com.standardapplied.helios.repl.ReplException;
import com.standardapplied.helios.repl.ReplSession;
import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import com.standardapplied.helios.repl.host.HostParameter;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Printing past the cap cuts the output and leaves the sandbox serving; only a frame the bootstrap
 * would never build, written to the RPC socket by code that reached it, ends the sandbox.
 */
class SandboxOutputCapTest {

  private static final JvmSandboxConfig CONFIG =
      JvmSandboxConfig.newBuilder()
          .withSubprocessStartupTimeout(Await.HANG_GUARD)
          .withCallTimeout(Await.HANG_GUARD)
          .withExecutionTimeout(Await.HANG_GUARD.multipliedBy(5))
          .build();

  private static final String MARKER = "\n[output truncated: 2000000 more chars discarded]";
  private static final String CUT_AT_THE_CAP = "x".repeat(MAX_CAPTURED_OUTPUT_CHARS) + MARKER;

  private static final String PRINT_THREE_MILLION_CHARS_TO =
      """
      var line = "x".repeat(1000);
      for (int i = 0; i < 3000; i++) { System.%s.print(line); }
      """;

  @AfterEach
  void leaveNoProcessBehind() {
    ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
  }

  @Test
  void anExecuteThatPrintsPastTheCapIsCutAndTheSessionGoesOnWithItsState() {
    try (var sandbox = JvmSandbox.create(CONFIG, new HostFunctionRegistry())) {
      var state = sandbox.execute(ExecutionRequest.java("int kept = 41;"));
      assertEquals(0, state.exitCode(), state.stderr());

      var printed =
          sandbox.execute(ExecutionRequest.java(PRINT_THREE_MILLION_CHARS_TO.formatted("out")));

      assertEquals(0, printed.exitCode(), printed.stderr());
      assertEquals(CUT_AT_THE_CAP, printed.stdout());
      assertTrue(sandbox.isAlive());

      var after = sandbox.execute(ExecutionRequest.java("System.out.print(kept + 1);"));

      assertEquals("42", after.stdout());
    }
  }

  @Test
  void controlCharactersFloodingBothStreamsAreCutAndTheSessionGoesOnWithItsState() {
    try (var sandbox = JvmSandbox.create(CONFIG, new HostFunctionRegistry())) {
      sandbox.execute(ExecutionRequest.java("int kept = 41;"));

      var printed =
          sandbox.execute(
              ExecutionRequest.java(
                  """
                  var flood = String.valueOf((char) 27).repeat(1000000);
                  System.out.print(flood);
                  System.err.print(flood);
                  """));

      assertEquals(0, printed.exitCode(), printed.stderr());
      assertTrue(printed.stdout().endsWith(" more chars discarded]"), "stdout cut");
      assertTrue(printed.stderr().endsWith(" more chars discarded]"), "stderr cut");
      assertTrue(sandbox.isAlive());

      var after = sandbox.execute(ExecutionRequest.java("System.out.print(kept + 1);"));

      assertEquals("42", after.stdout());
    }
  }

  @Test
  void anExecuteThatPrintsToStderrPastTheCapIsCutTheSameWay() {
    try (var sandbox = JvmSandbox.create(CONFIG, new HostFunctionRegistry())) {
      var printed =
          sandbox.execute(ExecutionRequest.java(PRINT_THREE_MILLION_CHARS_TO.formatted("err")));

      assertEquals(CUT_AT_THE_CAP, printed.stderr());
      assertTrue(sandbox.isAlive());
    }
  }

  @Test
  void aMultiByteCharacterAtTheCutStaysWhole() {
    try (var sandbox = JvmSandbox.create(CONFIG, new HostFunctionRegistry())) {
      var printed =
          sandbox.execute(
              ExecutionRequest.java(
                  "System.out.print(\"x\".repeat(%d) + \"\\u20AC\\u20AC\");"
                      .formatted(MAX_CAPTURED_OUTPUT_CHARS - 1)));

      assertEquals(
          "x".repeat(MAX_CAPTURED_OUTPUT_CHARS - 1)
              + "€\n[output truncated: 1 more chars discarded]",
          printed.stdout());
    }
  }

  @Test
  void aHostCallTooLongForAFrameFailsTheSnippetAndTheSessionGoesOnWithItsState() {
    var registry = new HostFunctionRegistry();
    registry.register(new HostFunction("ping", "answers", params -> "pong"));
    try (var sandbox = JvmSandbox.create(CONFIG, registry)) {
      sandbox.execute(ExecutionRequest.java("int kept = 41;"));

      var refused =
          sandbox.execute(
              ExecutionRequest.java(
                  "HostBridge.__call(\"ping\", Map.of(\"text\", \"x\".repeat(%d)));"
                      .formatted(MAX_FRAME_CHARS)));

      assertEquals(1, refused.exitCode());
      assertTrue(refused.stderr().contains("exceeds the limit"), refused.stderr());
      assertTrue(sandbox.isAlive());

      var after = sandbox.execute(ExecutionRequest.java("System.out.print(kept + 1);"));

      assertEquals("42", after.stdout());
    }
  }

  @Test
  void rawStderrPastThePipeBufferLeavesTheSnippetRunning() {
    try (var sandbox = JvmSandbox.create(CONFIG, new HostFunctionRegistry())) {
      var printed =
          sandbox.execute(
              ExecutionRequest.java(
                  """
                  new java.io.FileOutputStream(java.io.FileDescriptor.err).write(new byte[1 << 20]);
                  System.out.print("done");
                  """));

      assertEquals(0, printed.exitCode(), printed.stderr());
      assertEquals("done", printed.stdout());
    }
  }

  @Test
  void anOversizedFrameEndsTheSandboxAndTheNextExecuteTakesTheNotAlivePath() throws IOException {
    try (var fake = new FakeSandboxProcess()) {
      var sandbox = fake.sandbox();
      assertTrue(sandbox.isAlive());

      fake.feedRaw(ProcessTransport.RPC_PREFIX + "x".repeat(MAX_FRAME_CHARS));

      Await.until("the channel to end", () -> !sandbox.isAlive());
      assertTrue(sandbox.process().isAlive(), "only the channel ended");
      var next = sandbox.execute(ExecutionRequest.java("1 + 1"));
      assertFalse(next.succeeded());
      assertEquals("Sandbox is not alive: its RPC channel closed", next.stderr());
    }
  }

  /**
   * The subprocess exits on its own once the channel ends, so its child must go before it does:
   * {@link JvmSandbox#close()} finds a descendant only by walking from a live subprocess.
   */
  @Test
  void aSandboxEndedByAnOversizedFrameEndsItsChildrenWithoutWaitingForClose() {
    var childPid = new AtomicReference<Long>();
    var registry = new HostFunctionRegistry();
    registry.register(
        new HostFunction(
            "reportPid",
            "records the child's pid",
            List.of(HostParameter.required("pid", ParameterType.STRING, "the child's pid")),
            params -> {
              childPid.set(Long.parseLong(params.get("pid").toString()));
              return null;
            }));
    registry.register(
        new HostFunction(
            "sink",
            "takes a value",
            List.of(HostParameter.required("value", ParameterType.STRING, "the value")),
            params -> null));
    try (var sandbox = JvmSandbox.create(CONFIG, registry)) {
      var started =
          sandbox.execute(
              ExecutionRequest.java(
                  "var child = new ProcessBuilder(\"sleep\", \"600\").start();\n"
                      + "reportPid(String.valueOf(child.pid()));"));
      assertEquals(0, started.exitCode(), started.stderr());
      var child = ProcessHandle.of(childPid.get()).orElseThrow();
      try {
        var oversized =
            sandbox.execute(
                ExecutionRequest.java("sink(\"x\".repeat(%d));".formatted(MAX_FRAME_CHARS)));

        assertEquals("Channel closed", oversized.stderr());
        Await.value("the child to end with the sandbox", child.onExit());
      } finally {
        child.destroyForcibly();
      }
    }
  }

  @Test
  void aReplSessionReportsADeadChannelAndRefusesTheNextExecute() throws IOException {
    try (var fake = new FakeSandboxProcess()) {
      var config =
          ReplConfig.newBuilder()
              .withSandboxFactory(registry -> fake.sandbox())
              .withExecutionTimeout(Duration.ofMinutes(10))
              .build();
      var session = ReplSession.create(config, new Semaphore(1));
      assertTrue(session.isOpen());

      fake.feedRaw(ProcessTransport.RPC_PREFIX + "x".repeat(MAX_FRAME_CHARS));

      Await.until("the session to report the dead channel", () -> !session.isOpen());
      var refused = assertThrows(ReplException.class, () -> session.execute("1 + 1"));
      assertEquals("Sandbox is no longer alive", refused.getMessage());
    }
  }

  @Test
  void aReplyTheBootstrapBuiltNeverReachesTheFrameLimit() throws IOException {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(
          Map.of(
              "stdout",
              "x".repeat(MAX_CAPTURED_OUTPUT_CHARS),
              "stdoutDiscarded",
              2_000_000,
              "stderr",
              CUT_AT_THE_CAP,
              "exitCode",
              0));

      var result = fake.sandbox().execute(ExecutionRequest.java("1 + 1"));

      assertEquals(CUT_AT_THE_CAP, result.stdout());
      assertEquals(CUT_AT_THE_CAP, result.stderr());
      assertTrue(fake.sandbox().isAlive());
    }
  }

  @Test
  void stdoutFromBothPathsIsCutAsOneOutputThoughEachFitsItsOwnCap() throws IOException {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(
          Map.of("stdout", "b".repeat(750_000), "stderr", "", "exitCode", 0), "a".repeat(750_000));

      var result = fake.sandbox().execute(ExecutionRequest.java("1 + 1"));

      assertEquals(
          "a".repeat(750_000)
              + "\n"
              + "b".repeat(249_999)
              + "\n[output truncated: 500001 more chars discarded]",
          result.stdout());
    }
  }

  @Test
  void stdoutFromBothPathsPastTheirCapsEndsInOneMarkerCountingBoth() throws IOException {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(
          Map.of(
              "stdout",
              "b".repeat(MAX_CAPTURED_OUTPUT_CHARS),
              "stdoutDiscarded",
              2_000_000,
              "stderr",
              "",
              "exitCode",
              0),
          "a".repeat(3_000_000));

      var result = fake.sandbox().execute(ExecutionRequest.java("1 + 1"));

      assertEquals(
          "a".repeat(MAX_CAPTURED_OUTPUT_CHARS)
              + "\n[output truncated: 5000001 more chars discarded]",
          result.stdout());
    }
  }

  @Test
  void aBareValueReplyKeepsTheMarkerOfCapturedStdout() throws IOException {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext("a value", "x".repeat(MAX_CAPTURED_OUTPUT_CHARS + 2));

      var result = fake.sandbox().execute(ExecutionRequest.java("1 + 1"));

      assertEquals(
          "x".repeat(MAX_CAPTURED_OUTPUT_CHARS) + "\n[output truncated: 2 more chars discarded]",
          result.stdout());
    }
  }

  @Test
  void stdoutFromBothPathsThatFitsTogetherIsNotCut() throws IOException {
    try (var fake = new FakeSandboxProcess()) {
      var half = "x".repeat(MAX_CAPTURED_OUTPUT_CHARS / 2);
      var rest = half.substring(1);
      fake.answerNext(Map.of("stdout", rest, "stderr", "", "exitCode", 0), half);

      var result = fake.sandbox().execute(ExecutionRequest.java("1 + 1"));

      assertEquals(half + "\n" + rest, result.stdout());
    }
  }
}
