/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.tools;

import com.standardapplied.helios.core.common.Validate;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.Tool;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Pairs a core {@link Tool} record with the v2 metadata the spec calls for — category, permission
 * key extractor, and visibility predicate.
 *
 * <p>The spec sketches Tool as a generic interface {@code Tool<I, O>} carrying these methods
 * directly. The current core {@link Tool} is a record without type parameters, so the v2 metadata
 * lives in this wrapper. When core Tool gets migrated to an interface in a future cleanup, this
 * binding collapses into the interface itself.
 *
 * <p>A path tool declares its {@link PathArgument}: {@link #canonicalize} replaces the argument's
 * value with its {@link PathSpace#canonical canonical} spelling ({@code .} or {@code ./a/b} in a
 * workspace, {@code /memories/} or {@code /memories/a/b} in memory), and the permission key is
 * computed from that canonical call, so the key names the target the tool runs on.
 *
 * <p>Construct via {@link #newBuilder(Tool)}; the builder requires the tool and a category, and
 * defaults the permission-key extractor to {@code ToolPermissionKey.of(tool.name())}, or to the
 * canonical path when a path argument is declared, and the visibility predicate to "always
 * visible".
 *
 * @param tool the underlying core tool; non-null
 * @param category the tool's classification axis; non-null
 * @param permissionKeyExtractor function from the canonical call's arguments to the permission key;
 *     non-null
 * @param visibility predicate deciding whether the tool is advertised to the model for a given
 *     turn; non-null
 * @param pathArgument the argument naming the path the tool touches, if any; non-null
 */
public record ToolBinding(
    Tool tool,
    ToolCategory category,
    Function<Map<String, Object>, ToolPermissionKey> permissionKeyExtractor,
    Predicate<ToolVisibilityContext> visibility,
    Optional<PathArgument> pathArgument) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   */
  public ToolBinding {
    Objects.requireNonNull(tool, "tool must not be null");
    Objects.requireNonNull(category, "category must not be null");
    Objects.requireNonNull(permissionKeyExtractor, "permissionKeyExtractor must not be null");
    Objects.requireNonNull(visibility, "visibility must not be null");
    Objects.requireNonNull(pathArgument, "pathArgument must not be null");
  }

  /**
   * The bound tool's name. Convenience for callers that don't care about the rest of the tool.
   *
   * @return non-blank name
   */
  public String name() {
    return tool.name();
  }

  /**
   * The call as it runs and the permission key it is authorised on. Without a path argument the
   * call is unchanged. With one, the argument's value, or its default when absent or empty, is
   * replaced by its canonical spelling, and the key is computed from that canonical call.
   *
   * @param call the call as the {@code PreToolUse} hooks left it; non-null
   * @return the canonical call and its key
   * @throws NullPointerException if {@code call} is null, or the extractor returns null
   * @throws IllegalArgumentException if a required path argument is absent or empty, or the path
   *     argument is not a string (an explicit JSON {@code null} included)
   * @throws RuntimeException whatever the path space throws for a path that names no target
   */
  public CanonicalCall canonicalize(ToolCall call) {
    Objects.requireNonNull(call, "call must not be null");
    var canonical = pathArgument.map(path -> path.canonicalize(call)).orElse(call);
    var key = permissionKeyExtractor.apply(canonical.arguments());
    return new CanonicalCall(
        canonical, Objects.requireNonNull(key, "permissionKeyExtractor returned null"));
  }

  /**
   * Decide whether the tool is visible to the model for the given turn.
   *
   * @param ctx the visibility context; non-null
   * @return {@code true} if the tool should appear in the model's tool list
   * @throws NullPointerException if {@code ctx} is null
   */
  public boolean visible(ToolVisibilityContext ctx) {
    Objects.requireNonNull(ctx, "ctx must not be null");
    return visibility.test(ctx);
  }

  /**
   * Start building a binding for the given tool.
   *
   * @param tool the tool; non-null
   * @return a fresh builder
   * @throws NullPointerException if {@code tool} is null
   */
  public static Builder newBuilder(Tool tool) {
    return new Builder(tool);
  }

  /**
   * A call as it runs, with the permission key it is authorised on.
   *
   * @param call the call with its path argument, if any, canonical; non-null
   * @param key the permission key computed from {@code call}; non-null
   */
  public record CanonicalCall(ToolCall call, ToolPermissionKey key) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException if either argument is null
     */
    public CanonicalCall {
      Objects.requireNonNull(call, "call must not be null");
      Objects.requireNonNull(key, "key must not be null");
    }
  }

  /**
   * The argument naming the path a tool touches.
   *
   * @param name the argument's name; non-blank
   * @param defaultValue the path used when the argument is absent or empty; empty when the argument
   *     is required; non-null
   * @param space the namespace the path names a target in; non-null
   * @param denyRulesOf the other tools whose DENY rules also withhold the entries a walk below this
   *     path returns; non-null
   */
  public record PathArgument(
      String name, Optional<String> defaultValue, PathSpace space, Set<String> denyRulesOf) {

    /**
     * Canonical constructor; copies {@code denyRulesOf}.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public PathArgument {
      Validate.notBlank("name", name);
      Objects.requireNonNull(defaultValue, "defaultValue must not be null");
      Objects.requireNonNull(space, "space must not be null");
      denyRulesOf = Set.copyOf(denyRulesOf);
    }

    /**
     * A path argument the call must carry.
     *
     * @param name the argument's name; non-blank
     * @param space the namespace it names a target in; non-null
     * @return the argument
     */
    public static PathArgument required(String name, PathSpace space) {
      return new PathArgument(name, Optional.empty(), space, Set.of());
    }

    /**
     * A path argument that takes {@code defaultValue} when absent or empty.
     *
     * @param name the argument's name; non-blank
     * @param defaultValue the path used in its absence; non-null
     * @param space the namespace it names a target in; non-null
     * @return the argument
     */
    public static PathArgument optional(String name, String defaultValue, PathSpace space) {
      return new PathArgument(name, Optional.of(defaultValue), space, Set.of());
    }

    /**
     * This argument, with the tools whose DENY rules also withhold what a walk below it returns.
     *
     * @param toolNames the tools' names; non-null
     * @return a new argument
     */
    public PathArgument withDenyRulesOf(String... toolNames) {
      return new PathArgument(name, defaultValue, space, Set.of(toolNames));
    }

    private ToolCall canonicalize(ToolCall call) {
      var value = call.arguments().get(name);
      var requested =
          !call.arguments().containsKey(name) || "".equals(value)
              ? defaultValue.orElseThrow(
                  () -> new IllegalArgumentException("'" + name + "' is required"))
              : value;
      if (!(requested instanceof String path)) {
        throw new IllegalArgumentException("'" + name + "' must be a string");
      }
      var arguments = new LinkedHashMap<>(call.arguments());
      arguments.put(name, space.canonical(path));
      return new ToolCall(call.id(), call.name(), arguments);
    }
  }

  /** Mutable builder for {@link ToolBinding}. */
  public static final class Builder {

    private final Tool tool;
    private ToolCategory category;
    private Function<Map<String, Object>, ToolPermissionKey> permissionKeyExtractor;
    private Predicate<ToolVisibilityContext> visibility = ctx -> true;
    private Optional<PathArgument> pathArgument = Optional.empty();

    private Builder(Tool tool) {
      this.tool = Objects.requireNonNull(tool, "tool must not be null");
    }

    /**
     * Set the category. Required.
     *
     * @param category non-null category
     * @return this builder
     * @throws NullPointerException if {@code category} is null
     */
    public Builder withCategory(ToolCategory category) {
      this.category = Objects.requireNonNull(category, "category must not be null");
      return this;
    }

    /**
     * Set the permission-key extractor, applied to the canonical call's arguments. Defaults to
     * {@code args -> ToolPermissionKey.of(name)}, the tool name with empty canonical args, or with
     * a {@link #withPathArgument path argument} to the argument's canonical path.
     *
     * @param extractor non-null function
     * @return this builder
     * @throws NullPointerException if {@code extractor} is null
     */
    public Builder withPermissionKeyExtractor(
        Function<Map<String, Object>, ToolPermissionKey> extractor) {
      this.permissionKeyExtractor = Objects.requireNonNull(extractor, "extractor must not be null");
      return this;
    }

    /**
     * Set the visibility predicate. Defaults to always-visible.
     *
     * @param visibility non-null predicate
     * @return this builder
     * @throws NullPointerException if {@code visibility} is null
     */
    public Builder withVisibility(Predicate<ToolVisibilityContext> visibility) {
      this.visibility = Objects.requireNonNull(visibility, "visibility must not be null");
      return this;
    }

    /**
     * Declare the argument naming the path the tool touches; see {@link ToolBinding#canonicalize}.
     *
     * @param pathArgument non-null argument
     * @return this builder
     * @throws NullPointerException if {@code pathArgument} is null
     */
    public Builder withPathArgument(PathArgument pathArgument) {
      this.pathArgument =
          Optional.of(Objects.requireNonNull(pathArgument, "pathArgument must not be null"));
      return this;
    }

    /**
     * Build the immutable binding.
     *
     * @return the binding
     * @throws IllegalStateException if {@code category} was never set
     */
    public ToolBinding build() {
      if (category == null) {
        throw new IllegalStateException("category is required");
      }
      var extractor = permissionKeyExtractor == null ? defaultExtractor() : permissionKeyExtractor;
      return new ToolBinding(tool, category, extractor, visibility, pathArgument);
    }

    private Function<Map<String, Object>, ToolPermissionKey> defaultExtractor() {
      var name = tool.name();
      return pathArgument
          .<Function<Map<String, Object>, ToolPermissionKey>>map(
              path -> args -> new ToolPermissionKey(name, (String) args.get(path.name())))
          .orElse(args -> ToolPermissionKey.of(name));
    }
  }
}
