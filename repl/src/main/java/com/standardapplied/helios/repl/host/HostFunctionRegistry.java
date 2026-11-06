/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.host;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mutable registry of host functions, kept in registration order. Can be frozen to prevent further
 * modifications after sandbox startup.
 *
 * <p>Not thread-safe while it is being filled: register every function from one thread, then {@link
 * #freeze()} it before other threads read it.
 */
public final class HostFunctionRegistry {

  /**
   * The helper methods the sandbox prelude ({@code SandboxPrelude.SNIPPET}) declares. A host
   * function with one of these names would replace the helper the model calls, so {@link
   * #register(HostFunction)} refuses it.
   */
  public static final Set<String> PRELUDE_HELPER_NAMES =
      Set.of(
          "print", "println", "printf", "sum", "sumInts", "mean", "max", "min", "join", "filter",
          "map", "sorted", "countBy");

  private final Map<String, HostFunction> functions = new LinkedHashMap<>();
  private volatile boolean frozen;

  /** Creates an empty, unfrozen registry. */
  public HostFunctionRegistry() {}

  /**
   * Register a host function.
   *
   * @param function the function to register
   * @throws IllegalArgumentException if {@code function} is null or named after a prelude helper
   *     ({@link #PRELUDE_HELPER_NAMES})
   * @throws IllegalStateException if the registry is frozen or a function with the same name exists
   */
  public void register(HostFunction function) {
    if (function == null) {
      throw new IllegalArgumentException("Host function must not be null");
    }
    if (PRELUDE_HELPER_NAMES.contains(function.name())) {
      throw new IllegalArgumentException(
          "Host function name must not be a sandbox prelude helper: " + function.name());
    }
    if (frozen) {
      throw new IllegalStateException("Registry is frozen");
    }
    if (functions.containsKey(function.name())) {
      throw new IllegalStateException("Host function already registered: " + function.name());
    }
    functions.put(function.name(), function);
  }

  /**
   * Look up a function by name.
   *
   * @param name the function name
   * @return the function, or {@code null} if not found
   */
  public HostFunction get(String name) {
    return functions.get(name);
  }

  /**
   * All registered functions.
   *
   * @return unmodifiable view of the registered functions
   */
  public Collection<HostFunction> all() {
    return Collections.unmodifiableCollection(functions.values());
  }

  /**
   * Returns the number of registered functions.
   *
   * @return the count, zero for an empty registry
   */
  public int size() {
    return functions.size();
  }

  /**
   * Freezes the registry so every later {@link #register(HostFunction)} throws. Idempotent; a
   * frozen registry is never unfrozen.
   */
  public void freeze() {
    this.frozen = true;
  }

  /**
   * Returns whether the registry is frozen.
   *
   * @return {@code true} once {@link #freeze()} has been called
   */
  public boolean isFrozen() {
    return frozen;
  }
}
