/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class ThreadBudgetTest {

  @TempDir Path dir;

  @Test
  void theThreadsInTheBudgetStartAndTheNextIsRefused() {
    assertEquals(
        "started 2\nrefused: OutOfMemoryError\nstarted after the cap\n",
        ChildJvm.output(dir, ThreadBudget.OPTIONS, StartsThreads.class.getName()));
  }

  static final class StartsThreads {
    public static void main(String[] args) throws Exception {
      var release = new CountDownLatch(1);
      Runnable held = () -> Await.latch("the release", release);
      ThreadBudget.run(
          2,
          () -> {
            Thread.ofPlatform().start(held);
            Thread.ofPlatform().start(held);
            System.out.println("started 2");
            try {
              Thread.ofPlatform().start(held);
            } catch (OutOfMemoryError e) {
              System.out.println("refused: " + e.getClass().getSimpleName());
            }
            return null;
          });
      Thread.ofPlatform().start(held);
      System.out.println("started after the cap");
      release.countDown();
    }
  }
}
