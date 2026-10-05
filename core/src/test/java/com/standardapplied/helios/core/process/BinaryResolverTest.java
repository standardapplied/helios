/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class BinaryResolverTest {

  private static final Path BASH = Path.of("/bin/bash");

  @Test
  void resolveNullPathRejected() {
    var ex = assertThrows(IllegalStateException.class, () -> BinaryResolver.resolve("bash", null));
    assertTrue(ex.getMessage().contains("PATH is empty"));
  }

  @Test
  void resolveEmptyPathRejected() {
    assertThrows(IllegalStateException.class, () -> BinaryResolver.resolve("bash", ""));
  }

  @Test
  void resolveSkipsEmptyPathEntries() {
    var resolved = BinaryResolver.resolve("bash", ":/bin:");
    assertTrue(resolved.toString().endsWith("bash"));
  }

  @Test
  void resolveFallsThroughNonExecutableMatch(@TempDir Path tmp) throws Exception {
    var fake = tmp.resolve("bash");
    Files.writeString(fake, "not executable");
    var pathEnv = tmp.toString() + ":/bin";
    var resolved = BinaryResolver.resolve("bash", pathEnv);
    assertEquals(BASH.toAbsolutePath(), resolved);
  }
}
