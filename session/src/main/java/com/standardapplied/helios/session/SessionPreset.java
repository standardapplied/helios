/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

/**
 * A composable configuration recipe applied to a {@link SessionOptions.Builder}. Each preset
 * receives a builder, layers configuration onto it (tools, hooks, permission policy, memory
 * backend, execution provider, output schema, …), and returns it for further chaining.
 *
 * <p>Presets are the canonical extension point for sharing session shapes across the SDK and across
 * user codebases. Three properties make them work:
 *
 * <ul>
 *   <li><b>Functional.</b> A preset is a single {@code Builder -> Builder} function, so any lambda
 *       or method reference satisfies the interface. Custom presets are typically one-line lambdas
 *       — no class needed:
 *       <pre>{@code
 * SessionPreset withTracing(Tracer t) { return b -> b.withHook(new TracingHook(t)); }
 * }</pre>
 *   <li><b>Composable.</b> {@link SessionOptions.Builder#apply(SessionPreset)} stacks them
 *       associatively; later presets overwrite earlier presets when they touch the same field.
 *       Building the same options twice always produces the same result.
 *   <li><b>Uniform.</b> Every built-in preset in {@link SessionPresets} returns a {@code
 *       SessionPreset}, so call sites do not have to distinguish "library preset" from "user
 *       preset" — both compose the same way.
 * </ul>
 *
 * <p>The model itself is set through {@link SessionOptions.Builder#withModel} before applying
 * presets — every session needs a model, so it stays a builder-level required field rather than a
 * preset-supplied one.
 *
 * <p>Typical use:
 *
 * <pre>{@code
 * var opts = SessionOptions.newBuilder()
 *     .withModel(model)
 *     .apply(SessionPresets.workspace(workspaceRoot))
 *     .apply(myCustomTracingPreset)
 *     .withCostCalculator(calc)
 *     .build();
 * }</pre>
 */
@FunctionalInterface
public interface SessionPreset {

  /**
   * Apply this preset to {@code builder}. Implementations typically mutate {@code builder} in place
   * and return it for chaining. Returning a different builder is permitted but unusual.
   *
   * @param builder the builder to configure; non-null
   * @return the resulting builder (usually the same instance)
   */
  SessionOptions.Builder apply(SessionOptions.Builder builder);
}
