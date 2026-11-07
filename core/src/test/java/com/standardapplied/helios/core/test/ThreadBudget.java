/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * A Linux JVM in which only a given number of further Java threads can start, for code whose {@link
 * Thread#start()} fails with {@code OutOfMemoryError: unable to create native thread}, as it does
 * when a container's pid limit is reached. The JVM runs with {@link #OPTIONS}, under which every
 * Java thread reserves 1 GiB of stack; {@link #run} caps the address space for the action.
 */
public final class ThreadBudget {

  /**
   * The JVM options {@link #run} needs: 1 GiB stacks, compiler threads that all start with the JVM
   * so none takes address space during the action, no warning on standard output for a refused
   * thread, and native access for {@code setrlimit}.
   */
  public static final List<String> OPTIONS =
      List.of(
          "-Xss1g",
          "-XX:-UseDynamicNumberOfCompilerThreads",
          "-Xlog:os+thread=off",
          "--enable-native-access=ALL-UNNAMED");

  private static final int RLIMIT_AS = 9;
  private static final long STACK = 1L << 30;
  private static final long HEADROOM = 512L << 20;
  private static final long UNLIMITED = -1L;

  private static final MethodHandle SETRLIMIT =
      Linker.nativeLinker()
          .downcallHandle(
              Linker.nativeLinker().defaultLookup().find("setrlimit").orElseThrow(),
              FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));

  private ThreadBudget() {}

  /**
   * Runs {@code action} while at most {@code threads} more Java threads can start, then lifts the
   * cap. The threads it starts must outlive the action, since one that ends frees its stack.
   *
   * @return what {@code action} returned
   * @throws Exception what {@code action} threw
   */
  public static <T> T run(int threads, Callable<T> action) throws Exception {
    try (var arena = Arena.ofConfined()) {
      var limit = arena.allocate(2 * JAVA_LONG.byteSize());
      limit.set(JAVA_LONG, JAVA_LONG.byteSize(), UNLIMITED);
      setAddressSpace(limit, addressSpace() + threads * STACK + HEADROOM);
      try {
        return action.call();
      } finally {
        setAddressSpace(limit, UNLIMITED);
      }
    }
  }

  private static long addressSpace() throws IOException {
    return Files.readAllLines(Path.of("/proc/self/status")).stream()
            .filter(line -> line.startsWith("VmSize:"))
            .mapToLong(line -> Long.parseLong(line.replaceAll("\\D", "")))
            .findFirst()
            .orElseThrow()
        * 1024;
  }

  private static void setAddressSpace(MemorySegment limit, long bytes) {
    limit.set(JAVA_LONG, 0, bytes);
    int result;
    try {
      result = (int) SETRLIMIT.invokeExact(RLIMIT_AS, limit);
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
    if (result != 0) {
      throw new IllegalStateException("setrlimit(RLIMIT_AS, " + bytes + ") failed");
    }
  }
}
