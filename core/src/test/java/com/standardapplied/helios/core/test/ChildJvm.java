/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A fresh JVM on the test's classpath, with the test JVM's JaCoCo agent passed through so what it
 * runs counts towards coverage. For behaviour that differs between JVM runs, such as the iteration
 * order of {@code Map.of}, which the JDK seeds anew in every JVM.
 */
public final class ChildJvm {

  private static final int RUNS = 3;

  private ChildJvm() {}

  private static List<String> command(String main) {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    var classpath = new ArrayList<String>();
    for (var arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      if (arg.startsWith("--module-path=")) {
        classpath.add(arg.substring("--module-path=".length()));
      } else if (arg.startsWith("-javaagent:") && arg.contains("jacoco")) {
        command.add(arg);
      }
    }
    classpath.add(System.getProperty("java.class.path"));
    command.add("-cp");
    command.add(String.join(File.pathSeparator, classpath));
    command.add(main);
    return command;
  }

  /**
   * What {@code main} prints to standard output in a fresh JVM, which must exit with status 0. A
   * JVM that outlives the wait, by a timeout or an interrupt, is killed rather than left running.
   *
   * @param dir a directory for the captured output
   * @param main the main class name or Java source file to launch
   * @return the standard output, exactly
   */
  public static String output(Path dir, String main) {
    try {
      var stdout = Files.createTempFile(dir, "stdout", ".txt");
      var stderr = Files.createTempFile(dir, "stderr", ".txt");
      var process =
          new ProcessBuilder(command(main))
              .redirectOutput(stdout.toFile())
              .redirectError(stderr.toFile())
              .start();
      try {
        Await.termination("the JVM running " + main + " to exit", process);
        assertEquals(0, process.exitValue(), () -> read(stderr));
        return Files.readString(stdout);
      } finally {
        process.destroyForcibly();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * What {@code main} prints, asserted to be the same in each of three fresh JVMs.
   *
   * @param dir a directory for the captured output
   * @param main the class whose {@code main} runs
   * @return the output every run printed
   */
  public static String sameInEveryJvm(Path dir, Class<?> main) {
    var first = output(dir, main.getName());
    for (var run = 2; run <= RUNS; run++) {
      assertEquals(first, output(dir, main.getName()), "run " + run + " of " + main.getName());
    }
    return first;
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      return "unreadable: " + e;
    }
  }
}
