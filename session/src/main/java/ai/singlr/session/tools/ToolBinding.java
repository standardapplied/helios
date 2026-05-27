/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.tools;

import ai.singlr.core.tool.Tool;
import java.util.Map;
import java.util.Objects;
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
 * <p>Construct via {@link #newBuilder(Tool)}; the builder requires the tool and a category, and
 * defaults the permission-key extractor to {@code ToolPermissionKey.of(tool.name())} and the
 * visibility predicate to "always visible".
 *
 * @param tool the underlying core tool; non-null
 * @param category the tool's classification axis; non-null
 * @param permissionKeyExtractor function from invocation arguments to the canonical permission key;
 *     non-null
 * @param visibility predicate deciding whether the tool is advertised to the model for a given
 *     turn; non-null
 */
public record ToolBinding(
    Tool tool,
    ToolCategory category,
    Function<Map<String, Object>, ToolPermissionKey> permissionKeyExtractor,
    Predicate<ToolVisibilityContext> visibility) {

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
   * Compute the permission key for an invocation.
   *
   * @param arguments the call arguments; non-null
   * @return the canonical permission key
   * @throws NullPointerException if {@code arguments} is null
   */
  public ToolPermissionKey permissionKey(Map<String, Object> arguments) {
    Objects.requireNonNull(arguments, "arguments must not be null");
    var key = permissionKeyExtractor.apply(arguments);
    return Objects.requireNonNull(key, "permissionKeyExtractor returned null");
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

  /** Mutable builder for {@link ToolBinding}. */
  public static final class Builder {

    private final Tool tool;
    private ToolCategory category;
    private Function<Map<String, Object>, ToolPermissionKey> permissionKeyExtractor;
    private Predicate<ToolVisibilityContext> visibility = ctx -> true;

    private Builder(Tool tool) {
      this.tool = Objects.requireNonNull(tool, "tool must not be null");
      this.permissionKeyExtractor = args -> ToolPermissionKey.of(tool.name());
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
     * Set the permission-key extractor. Defaults to {@code args -> ToolPermissionKey.of(name)}
     * which returns the tool name with empty canonical args.
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
     * Build the immutable binding.
     *
     * @return the binding
     * @throws IllegalStateException if {@code category} was never set
     */
    public ToolBinding build() {
      if (category == null) {
        throw new IllegalStateException("category is required");
      }
      return new ToolBinding(tool, category, permissionKeyExtractor, visibility);
    }
  }
}
