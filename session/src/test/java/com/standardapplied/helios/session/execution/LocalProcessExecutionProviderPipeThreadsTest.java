/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.session.execution;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.ChildJvm;
import com.standardapplied.helios.core.test.PidFile;
import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.session.test.ExecutionCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The threads that read a call's output and write its stdin leave the virtual-thread scheduler free
 * while they block, and none outlives the call, whether it completes, times out or is cancelled.
 */
final class LocalProcessExecutionProviderPipeThreadsTest {

  private static final SessionContext CTX = SessionContext.forTesting("pipe-threads");

  private static final Duration BEYOND_HANG_GUARD = Await.HANG_GUARD.multipliedBy(5);

  private static final Set<String> PIPE_THREADS =
      Set.of(
          "helios-process-stdout-reader",
          "helios-process-stderr-reader",
          "helios-process-stdin-writer");

  /**
   * Runs a call whose child reads one byte of a 1 MiB stdin and then sleeps, so the write of the
   * rest blocks for as long as the child lives. The probe JVM has one carrier and no spare ({@link
   * ChildJvm#ONE_CARRIER}): a write blocked on a virtual thread would hold that carrier, the
   * probe's 100 ms timed park would never end, and the wait for the probe would fail at the hang
   * guard (60 s).
   */
  private static final String PROBE =
      """
      import com.standardapplied.helios.core.model.CancellationToken;
      import com.standardapplied.helios.core.test.Await;
      import com.standardapplied.helios.core.tool.SessionContext;
      import com.standardapplied.helios.session.execution.ExecutionRequest;
      import com.standardapplied.helios.session.execution.LocalProcessExecutionProvider;
      import com.standardapplied.helios.session.execution.Runtime;
      import com.standardapplied.helios.session.test.ExecutionCall;
      import java.nio.file.Files;
      import java.time.Duration;

      public class BlockedStdinProbe {
        public static void main(String[] args) throws Exception {
          var dir = Files.createTempDirectory("blocked-stdin");
          var ready = dir.resolve("ready");
          var provider = LocalProcessExecutionProvider.newBuilder()
              .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
              .withMaxTimeout(Duration.ofMinutes(10))
              .withShutdownHook(false)
              .build();
          var request = ExecutionRequest.newBuilder()
              .withRuntime(Runtime.BASH)
              .withScript("head -c 1 > /dev/null && touch ready && exec sleep 600")
              .withWorkingDirectory(dir)
              .withStdin("x".repeat(1 << 20))
              .withTimeout(Duration.ofMinutes(10))
              .build();
          var token = new CancellationToken();
          var call = ExecutionCall.start(provider, SessionContext.forTesting("probe"), request, token);
          Await.until("the child to read its stdin", () -> Files.exists(ready));
          var park = Thread.ofVirtual().start(() -> {
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              throw new IllegalStateException(e);
            }
          });
          park.join();
          System.out.println("parked");
          token.cancel("probe done");
          System.out.println(Await.failure("the cancelled call", call.outcome()).getClass().getName());
          Files.delete(ready);
          Files.delete(dir);
        }
      }
      """;

  private Set<Thread> before;

  @BeforeEach
  void requireBash() {
    assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "/bin/bash is required");
    before = pipeThreads();
  }

  @AfterEach
  void noChildOutlivesItsTest() {
    ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
  }

  @Test
  void aTimedParkEndsWhileAWriteToAChildsStdinIsBlocked(@TempDir Path dir) throws Exception {
    var source = Files.writeString(dir.resolve("BlockedStdinProbe.java"), PROBE);

    assertEquals(
        "parked\n" + CancellationException.class.getName() + "\n",
        ChildJvm.output(dir, ChildJvm.ONE_CARRIER, source.toString()));
  }

  @Test
  void noPipeThreadOutlivesACallThatCompletes() {
    var result =
        Await.value(
            "the call",
            ExecutionCall.start(
                    provider(), CTX, request("cat; echo err >&2", "in"), new CancellationToken())
                .outcome());

    assertEquals("in", result.stdout());
    assertEquals("err\n", result.stderr());
    assertEquals(Set.of(), leftBehind());
  }

  @Test
  void noPipeThreadOutlivesACallThatTimesOut() {
    var request =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.BASH)
            .withScript("exec sleep 600")
            .withStdin("in")
            .withTimeout(Duration.ofMillis(150))
            .build();

    var result =
        Await.value(
            "the call",
            ExecutionCall.start(provider(), CTX, request, new CancellationToken()).outcome());

    assertTrue(result.timedOut());
    assertEquals(Set.of(), leftBehind());
  }

  @Test
  void noPipeThreadOutlivesACallThatIsCancelled(@TempDir Path dir) {
    var pidFile = dir.resolve("pid");
    var token = new CancellationToken();
    var call =
        ExecutionCall.start(
            provider(), CTX, request("echo $$ > " + pidFile + "; exec sleep 600", "in"), token);
    var child = PidFile.await("the child to write its pid", pidFile);

    token.cancel("test");

    assertInstanceOf(
        CancellationException.class, Await.failure("the cancelled call", call.outcome()));
    assertFalse(child.isAlive());
    assertEquals(Set.of(), leftBehind());
  }

  private static LocalProcessExecutionProvider provider() {
    return LocalProcessExecutionProvider.newBuilder()
        .withRuntime(Runtime.BASH, LocalProcessExecutionProvider.RuntimeHandler.dashC("bash"))
        .withMaxTimeout(BEYOND_HANG_GUARD)
        .withShutdownHook(false)
        .build();
  }

  private static ExecutionRequest request(String script, String stdin) {
    return ExecutionRequest.newBuilder()
        .withRuntime(Runtime.BASH)
        .withScript(script)
        .withStdin(stdin)
        .withTimeout(BEYOND_HANG_GUARD)
        .build();
  }

  private Set<Thread> leftBehind() {
    var alive = new HashSet<>(pipeThreads());
    alive.removeAll(before);
    return alive;
  }

  private static Set<Thread> pipeThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> PIPE_THREADS.contains(thread.getName()))
        .collect(toSet());
  }
}
