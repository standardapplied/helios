/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.ChildJvm;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * A process whose output pipes stay open and silent leaves the virtual-thread scheduler free. The
 * probe JVM has one carrier and no spare ({@link ChildJvm#ONE_CARRIER}); a drain blocked on a
 * virtual thread would hold that carrier, the probe's 100 ms timed park would never end, and the
 * wait for the probe would fail at the hang guard (60 s).
 */
@DisabledOnOs(OS.WINDOWS)
class BoundedProcessSchedulerTest {

  @Test
  void aTimedParkEndsWhileAChildsOutputPipesAreOpenAndSilent(@TempDir Path dir) {
    assertEquals("parked\n", ChildJvm.output(dir, ChildJvm.ONE_CARRIER, Probe.class.getName()));
  }

  static final class Probe {
    public static void main(String[] args) throws IOException {
      try (var process = BoundedProcess.newBuilder(List.of("cat")).start()) {
        Await.timedPark();
        System.out.println("parked");
        process.kill();
      }
    }
  }
}
