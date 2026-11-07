/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChildJvmTest {

  /**
   * Blocks a virtual thread in a pipe read, which asks the scheduler for a spare carrier, then
   * reports the scheduler's parallelism and how many carriers it has.
   */
  private static final String CARRIER_PROBE =
      """
      import com.standardapplied.helios.core.test.Await;
      import java.io.IOException;
      import java.io.UncheckedIOException;
      import java.lang.management.ManagementFactory;
      import java.util.Arrays;
      import jdk.management.VirtualThreadSchedulerMXBean;

      public class CarrierProbe {
        public static void main(String[] args) throws Exception {
          var cat = new ProcessBuilder("cat").start();
          var reader = Thread.ofVirtual().start(() -> {
            try {
              cat.getInputStream().read();
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
          Await.until("the read to block", () -> Arrays.stream(reader.getStackTrace())
              .anyMatch(frame -> frame.getMethodName().equals("readBytes")));
          var scheduler = ManagementFactory.getPlatformMXBean(VirtualThreadSchedulerMXBean.class);
          System.out.println(scheduler.getParallelism() + " " + scheduler.getPoolSize());
          cat.destroyForcibly();
        }
      }
      """;

  @TempDir Path dir;

  @Test
  void outputIsWhatTheChildPrints() {
    assertEquals("hello\n", ChildJvm.output(dir, Prints.class.getName()));
  }

  @Test
  void aChildStillRunningWhenTheWaitIsInterruptedIsKilled() {
    Thread.currentThread().interrupt();
    try {
      var failure =
          assertThrows(AssertionError.class, () -> ChildJvm.output(dir, Blocks.class.getName()));
      assertTrue(failure.getMessage().startsWith("Interrupted while waiting for"));
    } finally {
      Thread.interrupted();
    }

    ProcessHandle.current()
        .children()
        .filter(ChildJvmTest::runsBlocks)
        .forEach(child -> Await.value("the blocked child to die", child.onExit()));
  }

  @Test
  void aProcessTheChildStartedIsKilledWithIt() {
    var pidFile = dir.resolve("grandchild.pid");
    var grandchild = new CompletableFuture<ProcessHandle>();
    var waiter = Thread.currentThread();
    Thread.startVirtualThread(
        () -> {
          grandchild.complete(PidFile.await("the grandchild to start", pidFile));
          waiter.interrupt();
        });
    try {
      assertThrows(
          AssertionError.class,
          () ->
              ChildJvm.output(
                  dir, List.of("-Dpid.file=" + pidFile), StartsGrandchild.class.getName()));
      Thread.interrupted();
      Await.value("the grandchild to die", Await.value("its handle", grandchild).onExit());
    } finally {
      Thread.interrupted();
      PidFile.destroy(pidFile);
    }
  }

  @Test
  void oneCarrierLeavesAVirtualThreadBlockedInAPipeReadWithNoSpareCarrier() throws IOException {
    var source = Files.writeString(dir.resolve("CarrierProbe.java"), CARRIER_PROBE);

    assertEquals("1 1\n", ChildJvm.output(dir, ChildJvm.ONE_CARRIER, source.toString()));
  }

  private static boolean runsBlocks(ProcessHandle child) {
    return child
        .info()
        .arguments()
        .map(args -> Arrays.asList(args).contains(Blocks.class.getName()))
        .orElse(false);
  }

  static final class Prints {
    public static void main(String[] args) {
      System.out.println("hello");
    }
  }

  static final class StartsGrandchild {
    public static void main(String[] args) throws IOException, InterruptedException {
      var grandchild = new ProcessBuilder("sleep", "600").start();
      Files.writeString(Path.of(System.getProperty("pid.file")), Long.toString(grandchild.pid()));
      Thread.currentThread().join();
    }
  }

  static final class Blocks {
    public static void main(String[] args) throws InterruptedException {
      Thread.currentThread().join();
    }
  }
}
