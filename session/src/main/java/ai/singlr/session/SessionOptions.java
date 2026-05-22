/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import ai.singlr.core.common.CostCalculator;
import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Strings;
import ai.singlr.core.context.TokenCounter;
import ai.singlr.core.model.Model;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.session.execution.ExecutionProvider;
import ai.singlr.session.execution.NoopExecutionProvider;
import ai.singlr.session.hooks.Hook;
import ai.singlr.session.memory.MemoryBackend;
import ai.singlr.session.permissions.Permission;
import ai.singlr.session.tools.ToolRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Composition record bundling everything needed to construct an {@link AgentSession}.
 *
 * <p>Phase 1 carries the minimum set of fields the agent loop actually wires: the model, the
 * session id, the limits, the concurrency caps, and the clock. Subsequent phases extend the record
 * as their subsystems land — Phase 2 adds {@code tools} and {@code hooks}; Phase 3 adds {@code
 * workspace}; Phase 4 adds {@code memory}; Phase 5 adds {@code execution}; Phase 7 adds {@code
 * audit}; etc. Because v2 is clean-slate (no backwards-compat shims), the record's shape evolves
 * with the phases.
 *
 * <p>Build via {@link #newBuilder()} or one of the {@link SessionPresets} factories — every
 * optional field has a sensible default, and {@code model} is the only required input. The
 * canonical record constructor is preserved for serialization and framework internals; library
 * users should not call it directly because its argument order will shift as new phases extend the
 * record.
 *
 * @param model the LLM the session loop drives; non-null
 * @param sessionId stable, non-blank session id; auto-generated UUID if not set on the builder
 * @param limits per-session ceilings; non-null
 * @param concurrency per-session concurrency caps; non-null
 * @param clock clock supplying event timestamps and elapsed; non-null
 * @param tools the tool registry the loop advertises to the model and dispatches against; non-null,
 *     defaults to {@link ToolRegistry#empty()}
 * @param hooks the hooks fired at lifecycle phases (pre/post-model-turn, pre/post-tool-use,
 *     pre-stop, on-user-message, on-stream-event); non-null, defaults to {@link List#of()}
 * @param permission optional permission policy; when present, an internal {@code
 *     DefaultPermissionEvaluator} runs as a {@code PreToolUseHook} at priority 50 (before
 *     user-supplied hooks) and gates each tool dispatch through the policy
 * @param memoryBackend optional persistent memory backend; when present, the session auto-registers
 *     the {@code MemoryRead} tool so the model can view {@code /memories/...} files
 * @param costCalculator per-model {@code Usage → CostEstimate} lookup. Each completed model turn's
 *     usage flows through this calculator; the result accumulates on the session and gates {@link
 *     SessionLimits#maxBudgetMicroUsd()}. Defaults to {@link CostCalculator#ZERO} — cost tracking
 *     is opt-in
 * @param executionProvider routes {@code Execute} tool dispatches to an isolation boundary
 *     (subprocess, JShell sandbox, Incus instance, …). Defaults to {@link
 *     NoopExecutionProvider#INSTANCE} which refuses every runtime — wire {@code
 *     LocalProcessExecutionProvider.defaultPosix(secretRegistry)} (or your own implementation) when
 *     the session legitimately needs to run code. Non-null
 * @param outputSchema optional schema constraining the model's text output. When present, the
 *     schema rides every model turn via the provider's native structured-output channel — Gemini
 *     {@code response_format.schema}, OpenAI {@code text.format=json_schema}, Anthropic {@code
 *     system_instruction} text. The schema is dormant on tool-calling turns and activates on
 *     text-output turns, so the model produces conforming JSON exactly when it produces text.
 *     Sessions whose terminal answer is a tool-call payload (e.g. {@code CodeActPreset.withSubLm}
 *     with an in-sandbox {@code submit(...)} flow) leave this field empty and carry their schema on
 *     the tool itself. The post-hoc validator inside {@link AgentSession#runBlocking(UserMessage,
 *     OutputSchema)} takes its schema per-call and is independent of this field
 * @param systemPrompt optional system-role message prepended to the conversation history before the
 *     first user message. Presets (CodeAct, RLM, custom) supply their strategy text here so the
 *     agent loop carries it to every model turn through the standard system-role channel
 * @param tokenCounter estimates running history's token footprint after each model turn. When usage
 *     crosses {@code 0.85} of {@link SessionLimits#maxContextTokens()}, the loop emits a {@link
 *     QueryEvent.ContextWarning ContextWarning}; the same counter is used to detect the 0.95
 *     compaction watermark. Defaults to {@link TokenCounter#charBased()} — wire a provider-specific
 *     tokenizer when accuracy matters more than the per-turn cost. Non-null
 * @param contextCompactor shrinks the conversation history when usage crosses {@code 0.95} of
 *     {@link SessionLimits#maxContextTokens()}. Defaults to a {@link
 *     DropMiddleToolResultsCompactor} bound to {@code model} — preserves the system prompt +
 *     opening turn + recent trajectory, summarises the middle. Library users can override via
 *     {@code Builder.withContextCompactor} or opt out wholesale with {@link
 *     ContextCompactor#disabled()}. Non-null
 */
public record SessionOptions(
    Model model,
    String sessionId,
    SessionLimits limits,
    ConcurrencyLimits concurrency,
    Clock clock,
    ToolRegistry tools,
    List<Hook> hooks,
    Optional<Permission> permission,
    Optional<MemoryBackend> memoryBackend,
    CostCalculator costCalculator,
    ExecutionProvider executionProvider,
    Optional<OutputSchema<?>> outputSchema,
    Optional<String> systemPrompt,
    TokenCounter tokenCounter,
    ContextCompactor contextCompactor) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null
   * @throws IllegalArgumentException if {@code sessionId} is blank
   */
  public SessionOptions {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    Objects.requireNonNull(limits, "limits must not be null");
    Objects.requireNonNull(concurrency, "concurrency must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(tools, "tools must not be null");
    Objects.requireNonNull(hooks, "hooks must not be null");
    hooks = List.copyOf(hooks);
    Objects.requireNonNull(permission, "permission must not be null");
    Objects.requireNonNull(memoryBackend, "memoryBackend must not be null");
    Objects.requireNonNull(costCalculator, "costCalculator must not be null");
    Objects.requireNonNull(executionProvider, "executionProvider must not be null");
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    Objects.requireNonNull(tokenCounter, "tokenCounter must not be null");
    Objects.requireNonNull(contextCompactor, "contextCompactor must not be null");
  }

  /**
   * Start building a {@code SessionOptions}.
   *
   * @return a fresh builder with defaults applied for every optional field
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Start building a {@code SessionOptions} pre-populated with the values from this instance.
   * Convenience for callers that want to derive a variant of an existing options bundle.
   *
   * @return a builder seeded with this record's values
   */
  public Builder toBuilder() {
    return new Builder()
        .withModel(model)
        .withSessionId(sessionId)
        .withLimits(limits)
        .withConcurrencyLimits(concurrency)
        .withClock(clock)
        .withTools(tools)
        .withHooks(hooks)
        .withPermission(permission.orElse(null))
        .withMemoryBackend(memoryBackend.orElse(null))
        .withCostCalculator(costCalculator)
        .withExecutionProvider(executionProvider)
        .withOutputSchema(outputSchema.orElse(null))
        .withSystemPrompt(systemPrompt.orElse(null))
        .withTokenCounter(tokenCounter)
        .withContextCompactor(contextCompactor);
  }

  /**
   * Mutable builder for {@link SessionOptions}. Every {@code with*} setter returns {@code this} so
   * the API chains; {@link #build()} validates and produces the immutable record. Unset fields use
   * the defaults documented on each setter.
   */
  public static final class Builder {

    private Model model;
    private String sessionId;
    private SessionLimits limits = SessionLimits.defaults();
    private ConcurrencyLimits concurrency = ConcurrencyLimits.defaults();
    private Clock clock = Clock.systemUTC();
    private ToolRegistry tools = ToolRegistry.empty();
    private final ArrayList<Hook> hooks = new ArrayList<>();
    private Permission permission;
    private MemoryBackend memoryBackend;
    private CostCalculator costCalculator = CostCalculator.ZERO;
    private ExecutionProvider executionProvider = NoopExecutionProvider.INSTANCE;
    private OutputSchema<?> outputSchema;
    private String systemPrompt;
    private TokenCounter tokenCounter = TokenCounter.charBased();
    private ContextCompactor contextCompactor;

    private Builder() {}

    /**
     * Set the model. Required.
     *
     * @param model non-null model
     * @return this builder
     * @throws NullPointerException if {@code model} is null
     */
    public Builder withModel(Model model) {
      this.model = Objects.requireNonNull(model, "model must not be null");
      return this;
    }

    /**
     * Set the session id. Defaults to a fresh UUID if not set.
     *
     * @param sessionId non-blank id
     * @return this builder
     * @throws NullPointerException if {@code sessionId} is null
     * @throws IllegalArgumentException if {@code sessionId} is blank
     */
    public Builder withSessionId(String sessionId) {
      Objects.requireNonNull(sessionId, "sessionId must not be null");
      if (Strings.isBlank(sessionId)) {
        throw new IllegalArgumentException("sessionId must not be blank");
      }
      this.sessionId = sessionId;
      return this;
    }

    /**
     * Set the session limits. Defaults to {@link SessionLimits#defaults()}.
     *
     * @param limits non-null limits
     * @return this builder
     * @throws NullPointerException if {@code limits} is null
     */
    public Builder withLimits(SessionLimits limits) {
      this.limits = Objects.requireNonNull(limits, "limits must not be null");
      return this;
    }

    /**
     * Set the concurrency caps. Defaults to {@link ConcurrencyLimits#defaults()}.
     *
     * @param concurrency non-null caps
     * @return this builder
     * @throws NullPointerException if {@code concurrency} is null
     */
    public Builder withConcurrencyLimits(ConcurrencyLimits concurrency) {
      this.concurrency = Objects.requireNonNull(concurrency, "concurrency must not be null");
      return this;
    }

    /**
     * Set the clock. Defaults to {@link Clock#systemUTC()}. Tests pass a fixed clock for
     * deterministic timestamps.
     *
     * @param clock non-null clock
     * @return this builder
     * @throws NullPointerException if {@code clock} is null
     */
    public Builder withClock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock must not be null");
      return this;
    }

    /**
     * Set the tool registry the loop advertises to the model and dispatches against. Defaults to an
     * empty registry — Phase 2 text-only sessions leave this alone.
     *
     * @param tools non-null registry
     * @return this builder
     * @throws NullPointerException if {@code tools} is null
     */
    public Builder withTools(ToolRegistry tools) {
      this.tools = Objects.requireNonNull(tools, "tools must not be null");
      return this;
    }

    /**
     * Set the full hook list. Replaces any previously-added hooks. Defaults to empty.
     *
     * @param hooks non-null list of hooks
     * @return this builder
     * @throws NullPointerException if {@code hooks} is null or contains null elements
     */
    public Builder withHooks(List<Hook> hooks) {
      Objects.requireNonNull(hooks, "hooks must not be null");
      for (var h : hooks) {
        Objects.requireNonNull(h, "hooks must not contain null");
      }
      this.hooks.clear();
      this.hooks.addAll(hooks);
      return this;
    }

    /**
     * Append a single hook. Useful when registering hooks one at a time.
     *
     * @param hook non-null hook
     * @return this builder
     * @throws NullPointerException if {@code hook} is null
     */
    public Builder withHook(Hook hook) {
      Objects.requireNonNull(hook, "hook must not be null");
      hooks.add(hook);
      return this;
    }

    /**
     * Set (or clear) the permission policy. Pass {@code null} to clear. When non-null, an internal
     * {@code DefaultPermissionEvaluator} runs as a {@code PreToolUseHook} at priority 50 before
     * user-supplied hooks.
     *
     * @param permission nullable policy
     * @return this builder
     */
    public Builder withPermission(Permission permission) {
      this.permission = permission;
      return this;
    }

    /**
     * Set (or clear) the memory backend. Pass {@code null} to clear. When non-null, the session
     * auto-registers a {@code MemoryRead} tool wired to this backend.
     *
     * @param memoryBackend nullable backend
     * @return this builder
     */
    public Builder withMemoryBackend(MemoryBackend memoryBackend) {
      this.memoryBackend = memoryBackend;
      return this;
    }

    /**
     * Set the cost calculator. Defaults to {@link CostCalculator#ZERO}, which means cost tracking
     * is disabled and {@link SessionLimits#maxBudgetMicroUsd()} never fires. Wire a {@link
     * CostCalculator#staticTable(java.util.Map)} (or your own implementation) to enable per-turn
     * cost accumulation.
     *
     * @param costCalculator non-null calculator
     * @return this builder
     * @throws NullPointerException if {@code costCalculator} is null
     */
    public Builder withCostCalculator(CostCalculator costCalculator) {
      this.costCalculator =
          Objects.requireNonNull(costCalculator, "costCalculator must not be null");
      return this;
    }

    /**
     * Set the execution provider — the surface the {@code Execute} tool dispatches through.
     * Defaults to {@link NoopExecutionProvider#INSTANCE}, which refuses every runtime so a session
     * that forgot to wire execution cannot silently shell out. Pass {@code
     * LocalProcessExecutionProvider.defaultPosix(...)} (or your own implementation) to enable code
     * execution.
     *
     * @param executionProvider non-null provider
     * @return this builder
     * @throws NullPointerException if {@code executionProvider} is null
     */
    public Builder withExecutionProvider(ExecutionProvider executionProvider) {
      this.executionProvider =
          Objects.requireNonNull(executionProvider, "executionProvider must not be null");
      return this;
    }

    /**
     * Set (or clear) the {@link OutputSchema} the session is expected to produce. Pass {@code null}
     * to clear.
     *
     * <p>When set, the schema is transmitted to the model on every turn via the provider's native
     * structured-output channel — Gemini {@code response_format.schema}, OpenAI {@code
     * text.format=json_schema}, Anthropic {@code system_instruction} text. The model interprets it
     * as a constraint on its text output. On a turn where the model chooses to call a tool, the
     * schema is dormant (tool arguments validate against the tool's own schema, not this one); on a
     * terminal text-output turn, it activates and the model emits conforming JSON.
     *
     * <p>The schema is per-session config, not per-turn. The agent loop does not switch the schema
     * on and off across turns — every model dispatch carries it.
     *
     * <p>The post-hoc validator inside {@link AgentSession#runBlocking(UserMessage, OutputSchema)}
     * takes its schema per-call and is independent of this field; passing the same schema in both
     * places is the common shape but not required.
     *
     * @param outputSchema nullable schema
     * @return this builder
     */
    public Builder withOutputSchema(OutputSchema<?> outputSchema) {
      this.outputSchema = outputSchema;
      return this;
    }

    /**
     * Set (or clear) the session's system-role prompt. Pass {@code null} or a blank string to
     * clear. When present, the agent loop appends a {@code Message.system(systemPrompt)} to the
     * conversation history before the first user message, so every model turn sees the prompt
     * through the standard system-role channel.
     *
     * <p>Presets ({@code CodeActPreset.typed}, {@code CodeActPreset.withSubLm}, custom presets)
     * supply their strategy text here. Stacking presets that both set a system prompt is "later
     * wins" — for layered behaviour, build the combined prompt externally and set it once.
     *
     * @param systemPrompt nullable / blank-tolerant prompt text
     * @return this builder
     */
    public Builder withSystemPrompt(String systemPrompt) {
      this.systemPrompt = Strings.isBlank(systemPrompt) ? null : systemPrompt;
      return this;
    }

    /**
     * Set the token counter the agent loop uses to estimate context-window fill after each model
     * turn. Defaults to {@link TokenCounter#charBased()} — a cheap heuristic suitable for English
     * text. Wire a provider-specific tokenizer for multimodal-heavy or non-English workloads.
     *
     * @param tokenCounter non-null counter
     * @return this builder
     * @throws NullPointerException if {@code tokenCounter} is null
     */
    public Builder withTokenCounter(TokenCounter tokenCounter) {
      this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter must not be null");
      return this;
    }

    /**
     * Set the {@link ContextCompactor} the agent loop invokes when running history crosses {@code
     * 0.95} of {@link SessionLimits#maxContextTokens()}. If unset, the default is a {@link
     * DropMiddleToolResultsCompactor} bound to the session's {@code model}. Pass {@link
     * ContextCompactor#disabled()} to opt out of automatic compaction wholesale; pass a custom
     * implementation to override the head/tail policy or summarisation prompt.
     *
     * @param contextCompactor non-null compactor
     * @return this builder
     * @throws NullPointerException if {@code contextCompactor} is null
     */
    public Builder withContextCompactor(ContextCompactor contextCompactor) {
      this.contextCompactor =
          Objects.requireNonNull(contextCompactor, "contextCompactor must not be null");
      return this;
    }

    /**
     * Apply a {@link SessionPreset} to this builder. The preset's {@code apply} function receives
     * this builder, layers configuration onto it, and returns it for chaining. Multiple presets
     * stack associatively — later {@code apply(...)} calls overwrite earlier ones when they touch
     * the same field.
     *
     * @param preset the preset to apply; non-null
     * @return the builder returned by the preset (usually this one)
     * @throws NullPointerException if {@code preset} is null or if it returns a null builder
     */
    public Builder apply(SessionPreset preset) {
      Objects.requireNonNull(preset, "preset must not be null");
      var next = preset.apply(this);
      Objects.requireNonNull(next, "preset must return a non-null builder");
      return next;
    }

    /**
     * Build the immutable record.
     *
     * @return the options
     * @throws IllegalStateException if {@code model} was never set
     */
    public SessionOptions build() {
      if (model == null) {
        throw new IllegalStateException("model is required");
      }
      var id = sessionId != null ? sessionId : "sess-" + Ids.newId();
      var compactor =
          contextCompactor != null
              ? contextCompactor
              : DropMiddleToolResultsCompactor.newBuilder(model).build();
      return new SessionOptions(
          model,
          id,
          limits,
          concurrency,
          clock,
          tools,
          hooks,
          Optional.ofNullable(permission),
          Optional.ofNullable(memoryBackend),
          costCalculator,
          executionProvider,
          Optional.ofNullable(outputSchema),
          Optional.ofNullable(systemPrompt),
          tokenCounter,
          compactor);
    }
  }
}
