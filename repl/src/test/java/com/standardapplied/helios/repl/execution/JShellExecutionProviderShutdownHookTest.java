/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.test.Await;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the provider's JVM shutdown hook does when the host JVM exits, observed from a probe JVM
 * that leaves one provider with a live session and closes another from a shutdown hook of its own.
 */
class JShellExecutionProviderShutdownHookTest {

  private static final String PROBE =
      """
      import com.standardapplied.helios.core.runtime.SessionContext;
      import com.standardapplied.helios.repl.ReplConfig;
      import com.standardapplied.helios.repl.execution.JShellExecutionProvider;
      import com.standardapplied.helios.repl.sandbox.ExecutionRequest;
      import com.standardapplied.helios.repl.sandbox.ExecutionResult;
      import com.standardapplied.helios.repl.sandbox.Sandbox;

      public class ShutdownHookProbe {
        public static void main(String[] args) {
          var config = ReplConfig.newBuilder().withSandboxFactory(r -> new Sandbox() {
            public ExecutionResult execute(ExecutionRequest request) {
              return ExecutionResult.newBuilder().build();
            }
            public boolean isAlive() {
              return true;
            }
            public void close() {
              System.out.println("reaped-by=" + Thread.currentThread().getName());
            }
          }).build();
          var live = JShellExecutionProvider.create(config);
          System.out.println("started=" + live.onSessionStart(SessionContext.forTesting("s")));
          var closedDuringShutdown = JShellExecutionProvider.create(config);
          Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closedDuringShutdown.close();
            System.out.println("closed-during-shutdown=" + closedDuringShutdown.isClosed());
          }));
        }
      }
      """;

  @Test
  void hostJvmExitReapsLiveSessionsOnTheShutdownThreadAndCloseDuringShutdownStillCompletes(
      @TempDir Path dir) throws Exception {
    var source = dir.resolve("ShutdownHookProbe.java");
    Files.writeString(source, PROBE);
    var stdout = dir.resolve("stdout.txt");
    var stderr = dir.resolve("stderr.txt");

    var process =
        new ProcessBuilder(probeCommand(source))
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start();
    Await.termination("the probe JVM to exit", process);

    assertEquals(0, process.exitValue(), () -> read(stderr));
    assertEquals(
        List.of(
            "closed-during-shutdown=true", "reaped-by=helios-jshell-shutdown", "started=Accept[]"),
        Files.readAllLines(stdout).stream().sorted().toList(),
        () -> read(stderr));
  }

  private static List<String> probeCommand(Path source) {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    var parentArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
    var classpath = new ArrayList<String>();
    for (var arg : parentArgs) {
      if (arg.startsWith("--module-path=")) {
        classpath.add(arg.substring("--module-path=".length()));
      } else if (arg.startsWith("-javaagent:") && arg.contains("jacoco")) {
        command.add(arg);
      }
    }
    classpath.add(System.getProperty("java.class.path"));
    command.add("-cp");
    command.add(String.join(File.pathSeparator, classpath));
    command.add(source.toString());
    return command;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      return "unreadable: " + e;
    }
  }
}
