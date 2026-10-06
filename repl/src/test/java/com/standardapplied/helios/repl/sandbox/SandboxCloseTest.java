/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What closing a launched sandbox leaves behind: no subprocess, no private socket directory and no
 * ephemeral working directory, whether the caller closes it or the JVM shutdown hook does.
 */
class SandboxCloseTest {

  private static final JvmSandboxConfig CONFIG =
      JvmSandboxConfig.newBuilder()
          .withSubprocessStartupTimeout(Await.HANG_GUARD)
          .withCallTimeout(Await.HANG_GUARD)
          .build();

  @AfterEach
  void leaveNoProcessBehind() {
    ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
  }

  @Test
  void closeLeavesNoProcessSocketDirectoryOrWorkingDirectory() {
    var sandbox = JvmSandbox.create(CONFIG, new HostFunctionRegistry());
    var socketDir = socketDirectory(sandbox.process());
    var workingDir = sandbox.ephemeralWorkingDirForTests();
    assertTrue(Files.isDirectory(socketDir), socketDir.toString());
    assertTrue(Files.isDirectory(workingDir), workingDir.toString());

    sandbox.close();

    assertFalse(sandbox.process().isAlive());
    assertFalse(Files.exists(socketDir), socketDir.toString());
    assertFalse(Files.exists(workingDir), workingDir.toString());
  }

  @Test
  void shutdownHookLeavesNoProcessSocketDirectoryOrWorkingDirectory() {
    var sandbox = JvmSandbox.create(CONFIG, new HostFunctionRegistry());
    var socketDir = socketDirectory(sandbox.process());
    var workingDir = sandbox.ephemeralWorkingDirForTests();

    sandbox.destroyOnJvmShutdown();

    Await.termination("the sandbox the shutdown hook destroys", sandbox.process());
    assertFalse(Files.exists(socketDir), socketDir.toString());
    assertFalse(Files.exists(workingDir), workingDir.toString());
    sandbox.close();
  }

  private static Path socketDirectory(Process process) {
    var socketArg =
        Arrays.stream(process.info().arguments().orElseThrow())
            .filter(arg -> arg.startsWith("--rpc-socket="))
            .findFirst()
            .orElseThrow();
    return Path.of(socketArg.substring("--rpc-socket=".length())).getParent();
  }
}
