/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.test;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A Linux JVM in which only a given number of further Java threads can start, for code whose {@link
 * Thread#start()} fails with {@code OutOfMemoryError: unable to create native thread}, as it does
 * when a container's pid limit is reached. The JVM runs with {@link #OPTIONS}, under which every
 * Java thread reserves 1 GiB of stack; {@link #of} caps the address space until {@link #close()}.
 */
public final class ThreadBudget implements AutoCloseable {

  /**
   * The JVM options {@link #of} needs: 1 GiB stacks, compiler threads that all start with the JVM
   * so none takes address space under the cap, no warning on standard output for a refused thread,
   * and native access for {@code setrlimit}.
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
   * Caps this JVM's address space so that at most {@code threads} more Java threads can start,
   * until the budget is closed. The threads started under the cap must outlive it, since one that
   * ends frees its stack.
   *
   * @return the budget, which lifts the cap when closed
   */
  public static ThreadBudget of(int threads) {
    try {
      setAddressSpace(addressSpace() + threads * STACK + HEADROOM);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new ThreadBudget();
  }

  /** Lifts the cap. */
  @Override
  public void close() {
    setAddressSpace(UNLIMITED);
  }

  private static long addressSpace() throws IOException {
    return Files.readAllLines(Path.of("/proc/self/status")).stream()
            .filter(line -> line.startsWith("VmSize:"))
            .mapToLong(line -> Long.parseLong(line.replaceAll("\\D", "")))
            .findFirst()
            .orElseThrow()
        * 1024;
  }

  private static void setAddressSpace(long bytes) {
    int result;
    try (var arena = Arena.ofConfined()) {
      var limit = arena.allocate(2 * JAVA_LONG.byteSize());
      limit.set(JAVA_LONG, 0, bytes);
      limit.set(JAVA_LONG, JAVA_LONG.byteSize(), UNLIMITED);
      result = (int) SETRLIMIT.invokeExact(RLIMIT_AS, limit);
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
    if (result != 0) {
      throw new IllegalStateException("setrlimit(RLIMIT_AS, " + bytes + ") failed");
    }
  }
}
