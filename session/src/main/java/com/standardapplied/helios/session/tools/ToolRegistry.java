/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.tools;

import ai.singlr.core.tool.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable registry of {@link ToolBinding}s addressable by tool name. One instance per session,
 * built from the bindings supplied via {@link #ToolRegistry(java.util.List)} or via {@link
 * ai.singlr.session.SessionOptions.Builder#withTools(ToolRegistry)} and threaded through the agent
 * loop.
 *
 * <p>Bindings are stored in registration order. Duplicate tool names are rejected at construction
 * time — the agent loop, hooks, and the model all key off the tool name, so collisions would let
 * one tool silently shadow another.
 *
 * <h2>Thread-safety</h2>
 *
 * Immutable. Safe to share.
 */
public final class ToolRegistry {

  private final Map<String, ToolBinding> bindingsByName;
  private final List<ToolBinding> bindings;

  /**
   * Build a registry from the given bindings.
   *
   * @param bindings the bindings; non-null, may be empty, must contain no duplicate tool names
   * @throws NullPointerException if {@code bindings} is null or contains null elements
   * @throws IllegalArgumentException if two bindings share a tool name
   */
  public ToolRegistry(List<ToolBinding> bindings) {
    Objects.requireNonNull(bindings, "bindings must not be null");
    var byName = new LinkedHashMap<String, ToolBinding>();
    for (var b : bindings) {
      Objects.requireNonNull(b, "bindings must not contain null");
      var prev = byName.putIfAbsent(b.name(), b);
      if (prev != null) {
        throw new IllegalArgumentException("duplicate tool name in registry: " + b.name());
      }
    }
    this.bindingsByName = Map.copyOf(byName);
    this.bindings = List.copyOf(byName.values());
  }

  /**
   * Empty registry.
   *
   * @return a fresh empty registry
   */
  public static ToolRegistry empty() {
    return new ToolRegistry(List.of());
  }

  /**
   * Number of registered bindings.
   *
   * @return non-negative count
   */
  public int size() {
    return bindings.size();
  }

  /**
   * Immutable snapshot of all bindings in registration order.
   *
   * @return defensive snapshot
   */
  public List<ToolBinding> bindings() {
    return bindings;
  }

  /**
   * Look up a binding by tool name.
   *
   * @param name the tool name; non-null
   * @return the binding, or empty if not registered
   * @throws NullPointerException if {@code name} is null
   */
  public Optional<ToolBinding> get(String name) {
    Objects.requireNonNull(name, "name must not be null");
    return Optional.ofNullable(bindingsByName.get(name));
  }

  /**
   * Look up the underlying core {@link Tool} by name.
   *
   * @param name the tool name; non-null
   * @return the tool, or empty if not registered
   * @throws NullPointerException if {@code name} is null
   */
  public Optional<Tool> tool(String name) {
    return get(name).map(ToolBinding::tool);
  }

  /**
   * Bindings whose {@link ToolBinding#visible(ToolVisibilityContext) visibility predicate} returns
   * true for the given context. The agent loop uses this to compute the per-turn tool list it
   * advertises to the model.
   *
   * @param ctx the visibility context; non-null
   * @return immutable list of visible bindings in registration order
   * @throws NullPointerException if {@code ctx} is null
   */
  public List<ToolBinding> visible(ToolVisibilityContext ctx) {
    Objects.requireNonNull(ctx, "ctx must not be null");
    return bindings.stream().filter(b -> b.visible(ctx)).toList();
  }

  /**
   * Underlying core {@link Tool} list, in registration order. Convenience for the agent loop's
   * {@code Model.chat(messages, tools)} call sites.
   *
   * @return immutable list of core tools
   */
  public List<Tool> tools() {
    return bindings.stream().map(ToolBinding::tool).toList();
  }
}
