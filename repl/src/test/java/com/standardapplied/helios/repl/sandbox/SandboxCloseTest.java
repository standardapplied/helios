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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
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
    var socketDir = sandbox.directoriesForTests().socketDirectory();
    var workingDir = sandbox.directoriesForTests().ephemeralWorkingDirectory();
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
    var socketDir = sandbox.directoriesForTests().socketDirectory();
    var workingDir = sandbox.directoriesForTests().ephemeralWorkingDirectory();

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

  @Test
  void anInterruptedWaitForTheExitIsLoggedKeepsTheInterruptAndTheLaterStepsStillRun()
      throws Exception {
    var process =
        new FailingProcess(
            new ProcessBuilder("sleep", "600").start(), null, new InterruptedException());
    var transport = new ProcessTransport(new FeedableInputStream(), new LineSink());
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), Await.HANG_GUARD);
    var directories = SandboxDirectories.create(null);
    var sandbox =
        new JvmSandbox(
            process,
            new SandboxRpc(transport, channel, null),
            CONFIG,
            StdoutCapture.of(transport),
            directories);

    try (var log = new LogCapture()) {
      sandbox.close();

      assertTrue(Thread.interrupted());
      assertEquals(
          List.of("FINE Interrupted while waiting for sandbox process to exit"), log.records());
    }
    Await.termination("the sandbox's subprocess", process);
    assertFalse(channel.isActive());
    assertFalse(Files.exists(directories.socketDirectory()));
    assertFalse(Files.exists(directories.workingDirectory()));
  }

  /**
   * A live child whose exit cannot be awaited and, when {@code killFailure} is set, whose
   * descendants cannot be listed.
   */
  private static final class FailingProcess extends Process {
    private final Process child;
    private final RuntimeException killFailure;
    private final Exception waitFailure;

    FailingProcess(Process child, RuntimeException killFailure, Exception waitFailure) {
      this.child = child;
      this.killFailure = killFailure;
      this.waitFailure = waitFailure;
    }

    @Override
    public Stream<ProcessHandle> descendants() {
      if (killFailure != null) {
        throw killFailure;
      }
      return child.descendants();
    }

    @Override
    public boolean waitFor(Duration duration) throws InterruptedException {
      if (waitFailure instanceof InterruptedException interrupted) {
        throw interrupted;
      }
      throw (RuntimeException) waitFailure;
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

  /** The records {@link JvmSandbox}'s logger publishes while open, as "LEVEL message". */
  private static final class LogCapture extends Handler implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(JvmSandbox.class.getName());

    private final List<String> records = new CopyOnWriteArrayList<>();
    private final Level priorLevel = LOGGER.getLevel();

    LogCapture() {
      LOGGER.addHandler(this);
      LOGGER.setLevel(Level.ALL);
    }

    List<String> records() {
      return List.copyOf(records);
    }

    @Override
    public void publish(LogRecord record) {
      records.add(record.getLevel() + " " + record.getMessage());
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
      LOGGER.removeHandler(this);
      LOGGER.setLevel(priorLevel);
    }
  }
}
