/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChildJvmTest {

  @TempDir Path dir;

  @Test
  void outputIsWhatTheChildPrints() {
    assertEquals("hello\n", ChildJvm.output(dir, Prints.class));
  }

  @Test
  void aChildStillRunningWhenTheWaitIsInterruptedIsKilled() {
    Thread.currentThread().interrupt();
    try {
      var failure = assertThrows(AssertionError.class, () -> ChildJvm.output(dir, Blocks.class));
      assertTrue(failure.getMessage().startsWith("Interrupted while waiting for"));
    } finally {
      Thread.interrupted();
    }

    ProcessHandle.current()
        .children()
        .filter(ChildJvmTest::runsBlocks)
        .forEach(child -> Await.value("the blocked child to die", child.onExit()));
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

  static final class Blocks {
    public static void main(String[] args) throws InterruptedException {
      Thread.currentThread().join();
    }
  }
}
