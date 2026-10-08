/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import java.util.Map;

/**
 * Static bridge for sandbox code to call host functions. Sandbox code evaluated by JShell reaches
 * the host through {@link #__call}, which delegates to the sandbox's installed {@link
 * HostBridgeState}. Every non-reserved host function registered on the parent gets a typed wrapper
 * synthesized by {@link SandboxPrelude} that calls it, so {@code marketQuote("AAPL")} is the form
 * sandbox code uses.
 */
public final class HostBridge {

  private HostBridge() {}

  /**
   * Internal relay for {@code SandboxPrelude}-synthesized wrappers around custom host functions.
   * The synthesizer emits a typed JShell wrapper per registered {@code HostFunction} (e.g. {@code
   * static Object marketQuote(String ticker)}); each wrapper packs its arguments into a {@code Map}
   * and calls this method, which forwards to the JSON-RPC channel.
   *
   * <p>Sandbox code should call the typed wrappers (e.g. {@code marketQuote("AAPL")}) — this
   * dispatcher is named {@code __call} on purpose so it's discoverable but not the obvious entry
   * point. Calling it directly skips the model's typed-signature contract.
   *
   * @param name the registered host function name
   * @param args the call arguments keyed by parameter name (caller supplies, may be empty)
   * @return whatever the handler returns
   * @throws IllegalStateException if called outside a sandbox
   */
  public static Object __call(String name, Map<String, Object> args) {
    var bootstrap = requireBootstrap();
    return bootstrap.callHost(name, args == null ? Map.of() : args);
  }

  private static HostBridgeState requireBootstrap() {
    var bootstrap = HostBridgeState.instance();
    if (bootstrap == null) {
      throw new IllegalStateException("HostBridge can only be called from within a sandbox");
    }
    return bootstrap;
  }
}
