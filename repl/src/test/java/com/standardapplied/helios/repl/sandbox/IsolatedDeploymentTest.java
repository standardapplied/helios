/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.standardapplied.helios.core.test.Await;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the least-privilege deployment recipe documented in the README's "Sandbox security
 * model" section: the Helios process and its {@link JvmSandbox} child run inside a rootless Podman
 * container with no network, a read-only root, all capabilities dropped and only the JDK and the
 * classpath mounted. A permissive {@link
 * com.standardapplied.helios.repl.sandbox.policy.SandboxPolicy} is used on purpose so the observed
 * denials come from the OS boundary alone.
 *
 * <p>The container root is a disposable directory with the host's {@code /usr} bind-mounted
 * read-only, so no image pull is needed. Skips with an explicit reason when the platform is not
 * Linux, {@code podman} is absent, or rootless Podman cannot start a rootfs container here.
 */
class IsolatedDeploymentTest {

  private static final List<String> PATH_FLAGS =
      List.of("-cp", "-classpath", "--class-path", "-p", "--module-path");
  private static final List<String> RECIPE =
      List.of(
          "--network",
          "none",
          "--read-only",
          "--cap-drop",
          "ALL",
          "--security-opt",
          "no-new-privileges",
          "--tmpfs",
          "/tmp",
          "--pids-limit",
          "256",
          "--memory",
          "1g");

  /**
   * The shell exits at once and leaves a child holding its output open for ten minutes. Getting the
   * outcome at all proves {@code run} waits for the process, not for its output to close.
   */
  @Test
  void processRunDoesNotWaitForOutputToClose() throws Exception {
    var shell = findOnPath("sh");
    assumeTrue(shell != null, "sh is not on PATH; process output probe unavailable");

    var outcome = run(List.of(shell, "-c", "sleep 600 & echo $!"));

    var outputHolder = ProcessHandle.of(Long.parseLong(outcome.output().strip())).orElseThrow();
    try {
      assertEquals(0, outcome.exitCode());
      assertTrue(outputHolder.isAlive(), "the child holding the output must still be running");
    } finally {
      outputHolder.destroyForcibly();
    }
  }

  @Test
  void processOutputLargerThanAPipeBufferIsCaptured() throws Exception {
    var shell = findOnPath("sh");
    assumeTrue(shell != null, "sh is not on PATH; process output probe unavailable");
    var outcome = run(List.of(shell, "-c", "printf '%131072s' x; printf 'stderr' >&2"));
    assertEquals(0, outcome.exitCode());
    assertEquals(" ".repeat(131071) + "xstderr", outcome.output());
  }

  @Test
  void leastPrivilegeContainerHidesHostFilesAndNetworkFromSandbox(@TempDir Path hostDir)
      throws Exception {
    assumeTrue(
        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux"),
        "least-privilege recipe is rootless Podman on Linux; not applicable on this OS");
    var podman = findOnPath("podman");
    assumeTrue(podman != null, "podman is not on PATH; isolated deployment not verified here");

    var rootfs = Files.createDirectory(hostDir.resolve("rootfs"));
    var baseCommand = new ArrayList<>(List.of(podman, "run", "--rm"));
    baseCommand.addAll(RECIPE);
    baseCommand.addAll(mountsForHostUserland(rootfs));

    var preflight = run(concat(baseCommand, "--rootfs", rootfs + ":O", "/usr/bin/true"));
    assumeTrue(
        preflight.exitCode() == 0,
        "rootless podman cannot start a rootfs container here; isolated deployment not verified: "
            + preflight.output());

    var marker = "SENTINEL-" + UUID.randomUUID();
    var sentinel = hostDir.resolve("sentinel.txt");
    Files.writeString(sentinel, marker);
    try (var listener = ServerSocketChannel.open()) {
      listener.bind(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0), 1);
      listener.configureBlocking(false);
      var command = new ArrayList<>(baseCommand);
      var javaBin = System.getProperty("java.home") + "/bin/java";
      var launch = SandboxLauncher.buildLaunchCommand(javaBin, JvmSandboxConfig.defaults());
      var jvmArgs = launch.subList(1, launch.indexOf(JvmSandboxBootstrap.class.getName()));
      for (var mount : hostPathsReferencedBy(jvmArgs)) {
        command.addAll(List.of("-v", mount + ":" + mount + ":ro"));
      }
      command.addAll(List.of("--rootfs", rootfs + ":O", javaBin));
      command.addAll(jvmArgs);
      command.addAll(
          List.of(
              IsolatedDeploymentProbe.class.getName(),
              sentinel.toString(),
              String.valueOf(listener.socket().getLocalPort())));

      var outcome = run(command);
      assertEquals(0, outcome.exitCode(), () -> "container run failed: " + outcome.output());
      assertTrue(outcome.output().contains("FS:DENIED"), outcome::output);
      assertTrue(outcome.output().contains("NET:DENIED"), outcome::output);
      assertFalse(outcome.output().contains(marker), outcome::output);
      assertNull(
          listener.accept(),
          "the container has exited, so a connection it made would be waiting on the host"
              + " listener; the isolated sandbox must never reach it");
    }
  }

  /**
   * Symlinks {@code /bin}, {@code /lib}, {@code /lib64} and {@code /sbin} the way the host lays
   * them out (merged-usr symlinks are recreated; real directories are bind-mounted read-only) and
   * always mounts {@code /usr} read-only, which is where the dynamic loader and libc live.
   */
  private static List<String> mountsForHostUserland(Path rootfs) throws IOException {
    var mounts = new ArrayList<>(List.of("-v", "/usr:/usr:ro"));
    for (var name : List.of("bin", "lib", "lib64", "sbin")) {
      var host = Path.of("/", name);
      if (Files.isSymbolicLink(host)) {
        Files.createSymbolicLink(rootfs.resolve(name), Files.readSymbolicLink(host));
      } else if (Files.isDirectory(host)) {
        mounts.addAll(List.of("-v", host + ":" + host + ":ro"));
      }
    }
    for (var name : List.of("usr", "tmp", "proc", "dev", "etc")) {
      Files.createDirectories(rootfs.resolve(name));
    }
    return mounts;
  }

  private static LinkedHashSet<Path> hostPathsReferencedBy(List<String> jvmArgs) {
    var paths = new LinkedHashSet<Path>();
    paths.add(Path.of(System.getProperty("java.home")));
    for (var i = 0; i < jvmArgs.size(); i++) {
      var arg = jvmArgs.get(i);
      String value = null;
      if (PATH_FLAGS.contains(arg) && i + 1 < jvmArgs.size()) {
        value = jvmArgs.get(i + 1);
      }
      for (var flag : PATH_FLAGS) {
        if (arg.startsWith(flag + "=")) {
          value = arg.substring(flag.length() + 1);
        }
      }
      if (value == null) {
        continue;
      }
      for (var entry : value.split(File.pathSeparator)) {
        var path = Path.of(entry).toAbsolutePath();
        if (Files.exists(path)) {
          paths.add(path);
        }
      }
    }
    return paths;
  }

  private static List<String> concat(List<String> head, String... tail) {
    var command = new ArrayList<>(head);
    command.addAll(List.of(tail));
    return command;
  }

  private static String findOnPath(String binary) {
    var path = System.getenv("PATH");
    if (path == null) {
      return null;
    }
    for (var dir : path.split(File.pathSeparator)) {
      var candidate = Path.of(dir, binary);
      if (Files.isExecutable(candidate)) {
        return candidate.toString();
      }
    }
    return null;
  }

  private record Outcome(int exitCode, String output) {}

  private static Outcome run(List<String> command) throws IOException {
    var output = Files.createTempFile("podman-probe-", ".log");
    try {
      var process =
          new ProcessBuilder(command)
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      try {
        process.getOutputStream().close();
        Await.termination("the process to exit: " + command, process);
        return new Outcome(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
      } finally {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    } finally {
      Files.deleteIfExists(output);
    }
  }
}
