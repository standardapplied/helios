/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

/**
 * Singular Agentic Framework — core primitives.
 *
 * <p>Provider-agnostic foundations shared by every higher-level module:
 *
 * <ul>
 *   <li>{@code model} — the {@code Model} interface every provider implements + the message /
 *       streaming / tool-call value types.
 *   <li>{@code tool} — the {@code Tool} builder, executor contract, and result types.
 *   <li>{@code common} — value types reused across modules ({@code Result}, {@code CostEstimate},
 *       {@code CostCalculator}, {@code Strings}, {@code Ids}, {@code SecretRegistry}, etc.).
 *   <li>{@code context} — {@code TokenCounter} SPI used by the agent loop to estimate conversation
 *       context fill and trigger watermark events.
 *   <li>{@code fault} — retry / circuit-breaker / timeout primitives.
 *   <li>{@code schema} — output-schema + provenance support for structured generation.
 *   <li>{@code embedding} — provider-agnostic embedding interface (impl in helios-onnx).
 *   <li>{@code knowledge} — sandboxed filesystem-knowledge tools.
 *   <li>{@code prompt} — versioned prompt registry + template rendering.
 *   <li>{@code trace} / {@code events} — observability primitives.
 *   <li>{@code runtime} — durable-run primitives (RunStore, ToolCallJournal, DurableResumeScanner)
 *       the v2 session SDK can plug into.
 * </ul>
 *
 * <p>The v1 {@code agent} / {@code workflow} / {@code memory} / {@code eval} surface was removed in
 * the v2 cut; sessions live in {@code helios-session}.
 */
module ai.singlr.core {
  requires java.logging;
  requires java.net.http;

  exports ai.singlr.core.common;
  exports ai.singlr.core.context;
  exports ai.singlr.core.embedding;
  exports ai.singlr.core.events;
  exports ai.singlr.core.fault;
  exports ai.singlr.core.knowledge;
  exports ai.singlr.core.model;
  exports ai.singlr.core.prompt;
  exports ai.singlr.core.runtime;
  exports ai.singlr.core.schema;
  exports ai.singlr.core.tool;
  exports ai.singlr.core.trace;

  uses ai.singlr.core.embedding.EmbeddingProvider;
  uses ai.singlr.core.model.ModelProvider;
}
