/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class PidFileTest {

  @Test
  void awaitReturnsTheProcessThatWroteItsPidAndDestroyEndsIt(@TempDir Path tmp) throws Exception {
    var file = tmp.resolve("pid");
    var process =
        new ProcessBuilder("/bin/sh", "-c", "echo $$ > \"$0\"; exec sleep 600", file.toString())
            .start();
    try {
      var recorded = PidFile.await("the pid to be written", file);
      assertEquals(process.pid(), recorded.pid());

      PidFile.destroy(file);

      assertFalse(recorded.isAlive());
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void destroyWithoutARecordedPidDoesNothing(@TempDir Path tmp) {
    assertDoesNotThrow(() -> PidFile.destroy(tmp.resolve("absent")));
  }
}
