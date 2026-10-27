/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolVisibilityContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One session's tools, addressable by name: the bindings its options list followed by the built-in
 * tools its options switch on, in that order. Duplicate tool names are rejected, a user tool
 * against a built-in included: the loop, hooks, permission rules and the model all key off the tool
 * name, so a collision would let one tool silently shadow another. Immutable.
 */
public final class ToolRegistry {

  private final Map<String, ToolBinding> bindingsByName;
  private final List<ToolBinding> bindings;

  /**
   * Build a registry from {@code bindings}.
   *
   * @throws IllegalArgumentException if two bindings share a tool name
   */
  public ToolRegistry(List<ToolBinding> bindings) {
    var byName = new LinkedHashMap<String, ToolBinding>();
    for (var b : bindings) {
      if (byName.putIfAbsent(b.name(), b) != null) {
        throw new IllegalArgumentException("duplicate tool name in registry: " + b.name());
      }
    }
    this.bindingsByName = Map.copyOf(byName);
    this.bindings = List.copyOf(byName.values());
  }

  /** A registry with no tools. */
  public static ToolRegistry empty() {
    return new ToolRegistry(List.of());
  }

  /** Every binding, in registration order. */
  public List<ToolBinding> bindings() {
    return bindings;
  }

  /** The binding named {@code name}, if one is registered. */
  public Optional<ToolBinding> get(String name) {
    return Optional.ofNullable(bindingsByName.get(name));
  }

  /** The bindings advertised on the turn {@code ctx} describes, in registration order. */
  List<ToolBinding> visible(ToolVisibilityContext ctx) {
    return bindings.stream().filter(b -> b.visible(ctx)).toList();
  }
}
