/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.execution;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.model.CancellationToken;
import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.ChildJvm;
import com.standardapplied.helios.core.tool.SessionContext;
import com.standardapplied.helios.repl.ReplConfig;
import com.standardapplied.helios.repl.sandbox.JvmSandbox;
import com.standardapplied.helios.repl.sandbox.JvmSandboxConfig;
import com.standardapplied.helios.session.execution.ExecutionRequest;
import com.standardapplied.helios.session.execution.Runtime;
import com.standardapplied.helios.session.execution.SessionStartOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A sandbox's stdout reader leaves the virtual-thread scheduler free while it blocks, at the scale
 * of a few dozen concurrent sessions, and none outlives its sandbox.
 */
final class JShellExecutionProviderPipeThreadsTest {

  private static final Duration BEYOND_HANG_GUARD = Await.HANG_GUARD.multipliedBy(5);

  private static final String STDOUT_READER = "helios-sandbox-stdout-reader";

  private static final int CPUS = java.lang.Runtime.getRuntime().availableProcessors();

  /**
   * More sandboxes than the scheduler's parallelism, so more readers block at once than there are
   * carriers. The 50-session scale is the release comparison's; here a sandbox start costs about 4
   * CPU-seconds, on every build.
   */
  private static final int SESSIONS = Math.max(8, 2 * CPUS);

  /**
   * Starts a session's sandbox, whose subprocess prints nothing to stdout once started. The probe
   * JVM has one carrier and no spare ({@link ChildJvm#ONE_CARRIER}): a stdout read blocked on a
   * virtual thread would hold that carrier, the sandbox start and the probe's 100 ms timed park
   * would never end, and the wait for the probe would fail at the hang guard (60 s).
   */
  private static final String PROBE =
      """
      import com.standardapplied.helios.core.tool.SessionContext;
      import com.standardapplied.helios.repl.ReplConfig;
      import com.standardapplied.helios.repl.execution.JShellExecutionProvider;
      import com.standardapplied.helios.repl.sandbox.JvmSandbox;
      import com.standardapplied.helios.repl.sandbox.JvmSandboxConfig;
      import java.time.Duration;

      public class SilentSandboxProbe {
        public static void main(String[] args) throws Exception {
          var beyondHangGuard = Duration.ofMinutes(5);
          var sandboxes = JvmSandbox.factory(JvmSandboxConfig.newBuilder()
              .withSubprocessStartupTimeout(beyondHangGuard)
              .withCallTimeout(beyondHangGuard)
              .build());
          try (var provider = JShellExecutionProvider.newBuilder()
              .withReplConfig(ReplConfig.newBuilder().withSandboxFactory(sandboxes).build())
              .withShutdownHook(false)
              .build()) {
            System.out.println(provider.onSessionStart(SessionContext.forTesting("probe")));
            var park = Thread.ofVirtual().start(() -> {
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                throw new IllegalStateException(e);
              }
            });
            park.join();
            System.out.println("parked");
          }
        }
      }
      """;

  @Test
  void aTimedParkEndsWhileASandboxsStdoutIsOpenAndSilent(@TempDir Path dir) throws Exception {
    var source = Files.writeString(dir.resolve("SilentSandboxProbe.java"), PROBE);

    assertEquals(
        SessionStartOutcome.accept() + "\nparked\n",
        ChildJvm.output(dir, ChildJvm.ONE_CARRIER, source.toString()));
  }

  /**
   * Twice as many sandboxes as CPUs are alive at once, each with its stdout reader blocked, while
   * each runs a snippet, with the default scheduler; then every session ends at once, and every
   * stdout reader with it. They start one per CPU at a time, so no wait outgrows one start. Takes
   * about 8 s on a 4-CPU machine.
   */
  @Test
  void moreSandboxesThanCarriersRunASnippetEachAndLeaveNoReaderBehind() {
    var before = stdoutReaders();
    var sandboxes =
        JvmSandbox.factory(
            JvmSandboxConfig.newBuilder()
                .withSubprocessStartupTimeout(BEYOND_HANG_GUARD)
                .withCallTimeout(BEYOND_HANG_GUARD)
                .build());
    var sessions =
        IntStream.range(0, SESSIONS)
            .mapToObj(i -> SessionContext.forTesting("session-" + i))
            .toList();
    try (var provider =
            JShellExecutionProvider.newBuilder()
                .withReplConfig(ReplConfig.newBuilder().withSandboxFactory(sandboxes).build())
                .withMaxConcurrentSessions(SESSIONS)
                .withMaxTimeout(BEYOND_HANG_GUARD)
                .withShutdownHook(false)
                .build();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var wave = 0; wave < SESSIONS; wave += CPUS) {
        var starts =
            sessions.subList(wave, Math.min(wave + CPUS, SESSIONS)).stream()
                .map(session -> executor.submit(() -> provider.onSessionStart(session)))
                .toList();
        starts.forEach(
            start ->
                assertEquals(
                    SessionStartOutcome.accept(), Await.value("a sandbox to start", start)));
      }

      var runs =
          sessions.stream().map(session -> executor.submit(() -> run(provider, session))).toList();
      var outputs = runs.stream().map(run -> Await.value("a snippet to run", run)).toList();
      var ends =
          sessions.stream()
              .map(session -> executor.submit(() -> provider.onSessionEnd(session)))
              .toList();
      ends.forEach(end -> Await.value("a session to end", end));

      assertEquals(Collections.nCopies(SESSIONS, "42"), outputs);
      assertEquals(0, provider.liveSessionCount());
    }
    var leftBehind = new HashSet<>(stdoutReaders());
    leftBehind.removeAll(before);
    assertEquals(Set.of(), leftBehind);
  }

  private static String run(JShellExecutionProvider provider, SessionContext session) {
    var request =
        ExecutionRequest.newBuilder()
            .withRuntime(Runtime.JSHELL)
            .withScript("System.out.print(6 * 7);")
            .withTimeout(BEYOND_HANG_GUARD)
            .build();
    return provider.execute(session, request, new CancellationToken()).stdout();
  }

  private static Set<Thread> stdoutReaders() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().equals(STDOUT_READER))
        .collect(toSet());
  }
}
