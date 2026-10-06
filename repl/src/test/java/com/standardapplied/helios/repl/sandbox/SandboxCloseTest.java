/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.LineSink;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcChannel;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What closing a launched sandbox leaves behind: no subprocess, no private socket directory and no
 * ephemeral working directory, whether the caller closes it or the JVM shutdown hook does; and that
 * every cleanup step runs when an earlier one throws.
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

  @Test
  void everyCleanupStepRunsWhenEarlierOnesThrowAndTheFirstFailureIsRethrown() throws Exception {
    var killFailure = new UnsupportedOperationException("cannot list descendants");
    var waitFailure = new IllegalStateException("cannot wait");
    var process =
        new FailingProcess(new ProcessBuilder("sleep", "600").start(), killFailure, waitFailure);
    var transport = new ProcessTransport(new FeedableInputStream(), new LineSink());
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), Await.HANG_GUARD);
    var directories = SandboxDirectories.create(null);
    Files.writeString(directories.workingDirectory().resolve("scratch.txt"), "left by a snippet");
    var sandbox =
        new JvmSandbox(
            process,
            new SandboxRpc(transport, channel, null),
            CONFIG,
            StdoutCapture.of(transport),
            directories);

    var thrown = assertThrows(UnsupportedOperationException.class, sandbox::close);

    assertSame(killFailure, thrown);
    assertEquals(List.of(waitFailure), List.of(thrown.getSuppressed()));
    assertFalse(channel.isActive());
    assertFalse(transport.isOpen());
    assertFalse(Files.exists(directories.socketDirectory()));
    assertFalse(Files.exists(directories.workingDirectory()));
    assertFalse(sandbox.isAlive());
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

  /** A live child whose descendants cannot be listed and whose exit cannot be awaited. */
  private static final class FailingProcess extends Process {
    private final Process child;
    private final RuntimeException killFailure;
    private final RuntimeException waitFailure;

    FailingProcess(Process child, RuntimeException killFailure, RuntimeException waitFailure) {
      this.child = child;
      this.killFailure = killFailure;
      this.waitFailure = waitFailure;
    }

    @Override
    public Stream<ProcessHandle> descendants() {
      throw killFailure;
    }

    @Override
    public boolean waitFor(Duration duration) {
      throw waitFailure;
    }

    @Override
    public OutputStream getOutputStream() {
      return child.getOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return child.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return child.getErrorStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
      return child.waitFor();
    }

    @Override
    public int exitValue() {
      return child.exitValue();
    }

    @Override
    public void destroy() {
      child.destroy();
    }

    @Override
    public boolean isAlive() {
      return child.isAlive();
    }
  }
}
