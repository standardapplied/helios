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
 * Mutable registry of host functions. Can be frozen to prevent further modifications after sandbox
 * startup.
 */
public final class HostFunctionRegistry {

  /**
   * Names reserved by the framework: {@code __call}, the dispatcher on the sandbox's {@code
   * HostBridge} that every synthesized wrapper calls. The prelude synthesizer emits no typed
   * wrapper for a reserved name, so the dispatcher is never shadowed, and {@code ReplSession}
   * excludes reserved names from its called-host-function counts.
   *
   * <p>Not enforced at {@link #register(HostFunction)}; reservation is the concern of the
   * components that read this constant, its single source of truth.
   */
  public static final Set<String> RESERVED_NAMES = Set.of("__call");

  private final Map<String, HostFunction> functions = new LinkedHashMap<>();
  private volatile boolean frozen;

  /**
   * Register a host function.
   *
   * @param function the function to register
   * @throws IllegalStateException if the registry is frozen or a function with the same name exists
   */
  public void register(HostFunction function) {
    if (function == null) {
      throw new IllegalArgumentException("Host function must not be null");
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

  /** Number of registered functions. */
  public int size() {
    return functions.size();
  }

  /** Freeze the registry to prevent further modifications. */
  public void freeze() {
    this.frozen = true;
  }

  /** Whether the registry is frozen. */
  public boolean isFrozen() {
    return frozen;
  }
}
