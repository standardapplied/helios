/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.memory.MemoryBackend;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.tools.ToolBinding;
import java.time.Clock;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Composition record bundling everything needed to construct an {@link AgentSession}.
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
 * @param maxQueuedUserMessages how many user messages {@link AgentSession#send} may queue before
 *     the loop consumes them; a send beyond it throws {@link SteeringQueueFullException}; positive,
 *     defaults to 256
 * @param clock time source supplying event timestamps and elapsed; non-null
 * @param tools the tools the loop advertises to the model and dispatches against, before the
 *     built-in tools the options switch on (the memory tools, {@code Execute}, {@code
 *     AskUserQuestion}); copied, non-null with no null element, defaults to none. Two tools with
 *     one name, a built-in included, fail {@link AgentSession#open}
 * @param hooks the hooks fired at lifecycle phases (pre/post-model-turn, pre/post-tool-use,
 *     pre-stop, on-user-message, on-stream-event); non-null, defaults to {@link List#of()}
 * @param permission the permission policy; an internal {@code DefaultPermissionEvaluator}
 *     authorizes every tool call as a fixed stage after every {@code PreToolUseHook}, on the exact
 *     call that runs, asking the user through {@link AgentSession#answer} when the policy asks.
 *     Non-null, defaults to {@link Permission#empty} in {@link PermissionMode#DEFAULT}, which asks
 *     before every write, execution and network call
 * @param memoryBackend optional persistent memory backend; when present, the session registers
 *     {@code MemoryRead} and {@code MemoryWrite} over it
 * @param costCalculator per-model {@code Usage → CostEstimate} lookup. Each completed model turn's
 *     usage flows through this calculator; the result accumulates on the session and gates {@link
 *     SessionLimits#maxBudgetMicroUsd()}. Defaults to {@link CostCalculator#ZERO} — cost tracking
 *     is opt-in
 * @param executionProvider optional provider that runs {@code Execute} scripts behind an isolation
 *     boundary (subprocess, JShell sandbox, Incus instance, …); when present, the session registers
 *     {@code Execute} over it and runs its {@code onSessionStart} and {@code onSessionEnd} with the
 *     session. Absent by default: no {@code Execute} tool and no start or end step
 * @param askUserQuestion whether the session registers the {@code AskUserQuestion} tool; {@code
 *     false} by default. Permission approvals ask the user either way
 * @param outputSchema optional schema constraining the model's text output. When present, the
 *     schema rides every model turn via the provider's native structured-output channel — Gemini
 *     {@code response_format.schema}, OpenAI {@code text.format=json_schema}, Anthropic {@code
 *     system_instruction} text. The schema is dormant on tool-calling turns and activates on
 *     text-output turns, so the model produces conforming JSON exactly when it produces text.
 *     {@link AgentSession#runBlocking(UserMessage, OutputSchema)} on such a session accepts only
 *     this schema
 * @param systemPrompt optional system-role message prepended to the conversation history before the
 *     first user message. Presets supply their strategy text here so the agent loop carries it to
 *     every model turn through the standard system-role channel
 * @param tokenCounter estimates running history's token footprint after each model turn. When usage
 *     crosses {@code 0.85} of {@link SessionLimits#maxContextTokens()}, the loop emits a {@link
 *     QueryEvent.ContextWarning ContextWarning}; the same counter is used to detect the 0.95
 *     compaction watermark. Defaults to {@link TokenCounter#charBased()} — wire a provider-specific
 *     tokenizer when accuracy matters more than the per-turn cost. Non-null
 * @param contextCompactor shrinks the conversation history when usage crosses {@code 0.95} of
 *     {@link SessionLimits#maxContextTokens()}. Defaults to a {@link SummarizingCompactor} bound to
 *     {@code model} — keeps the system prompt and the pending user turn and replaces the rest with
 *     one summary. Library users can override via {@code Builder.withContextCompactor} or opt out
 *     wholesale with {@link ContextCompactor#disabled()}. Non-null
 * @param secretRegistry the secrets no tool output or session error text may carry. The session
 *     redacts every tool result once, before any event, hook or the model sees it, and the error
 *     text of every {@link SerializedError} it builds, each time against a fresh snapshot of this
 *     registry so a secret registered mid-session is honoured from the next redaction on. Sessions
 *     built from the same options share it. Defaults to a new empty registry, which redacts
 *     nothing. Non-null
 */
public record SessionOptions(
    Model model,
    String sessionId,
    SessionLimits limits,
    int maxQueuedUserMessages,
    InstantSource clock,
    List<ToolBinding> tools,
    List<Hook> hooks,
    Permission permission,
    Optional<MemoryBackend> memoryBackend,
    CostCalculator costCalculator,
    Optional<ExecutionProvider> executionProvider,
    boolean askUserQuestion,
    Optional<OutputSchema<?>> outputSchema,
    Optional<String> systemPrompt,
    TokenCounter tokenCounter,
    ContextCompactor contextCompactor,
    SecretRegistry secretRegistry) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null or {@code tools} or {@code hooks} holds a
   *     null element
   * @throws IllegalArgumentException if {@code sessionId} is blank or {@code maxQueuedUserMessages}
   *     is not positive
   */
  public SessionOptions {
    Objects.requireNonNull(model, "model must not be null");
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    if (Strings.isBlank(sessionId)) {
      throw new IllegalArgumentException("sessionId must not be blank");
    }
    Objects.requireNonNull(limits, "limits must not be null");
    requirePositiveQueue(maxQueuedUserMessages);
    Objects.requireNonNull(clock, "clock must not be null");
    tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
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
    Objects.requireNonNull(secretRegistry, "secretRegistry must not be null");
  }

  private static int requirePositiveQueue(int maxQueuedUserMessages) {
    if (maxQueuedUserMessages <= 0) {
      throw new IllegalArgumentException(
          "maxQueuedUserMessages must be positive, got " + maxQueuedUserMessages);
    }
    return maxQueuedUserMessages;
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
    var builder = new Builder();
    executionProvider.ifPresent(builder::withExecutionProvider);
    if (askUserQuestion) {
      builder.withAskUserQuestion();
    }
    return builder
        .withModel(model)
        .withSessionId(sessionId)
        .withLimits(limits)
        .withMaxQueuedUserMessages(maxQueuedUserMessages)
        .withClock(clock)
        .withTools(tools)
        .withHooks(hooks)
        .withPermission(permission)
        .withMemoryBackend(memoryBackend.orElse(null))
        .withCostCalculator(costCalculator)
        .withOutputSchema(outputSchema.orElse(null))
        .withSystemPrompt(systemPrompt.orElse(null))
        .withTokenCounter(tokenCounter)
        .withContextCompactor(contextCompactor)
        .withSecretRegistry(secretRegistry);
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
    private int maxQueuedUserMessages = 256;
    private InstantSource clock = Clock.systemUTC();
    private final ArrayList<ToolBinding> tools = new ArrayList<>();
    private final ArrayList<Hook> hooks = new ArrayList<>();
    private Permission permission = Permission.empty(PermissionMode.DEFAULT);
    private MemoryBackend memoryBackend;
    private CostCalculator costCalculator = CostCalculator.ZERO;
    private ExecutionProvider executionProvider;
    private boolean askUserQuestion;
    private OutputSchema<?> outputSchema;
    private String systemPrompt;
    private TokenCounter tokenCounter = TokenCounter.charBased();
    private ContextCompactor contextCompactor;
    private SecretRegistry secretRegistry;

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
     * Set how many user messages {@link AgentSession#send} may queue before the loop consumes them;
     * a send beyond it throws {@link SteeringQueueFullException}. Defaults to 256.
     *
     * @param maxQueuedUserMessages positive capacity
     * @return this builder
     * @throws IllegalArgumentException if {@code maxQueuedUserMessages} is not positive
     */
    public Builder withMaxQueuedUserMessages(int maxQueuedUserMessages) {
      this.maxQueuedUserMessages = requirePositiveQueue(maxQueuedUserMessages);
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
    public Builder withClock(InstantSource clock) {
      this.clock = Objects.requireNonNull(clock, "clock must not be null");
      return this;
    }

    /**
     * Set the full tool list. Replaces any previously-added tools. Defaults to none, which leaves
     * the session with only the built-in tools its options switch on.
     *
     * @param tools non-null list of bindings
     * @return this builder
     * @throws NullPointerException if {@code tools} is null or contains null elements
     */
    public Builder withTools(List<ToolBinding> tools) {
      var copy = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
      this.tools.clear();
      this.tools.addAll(copy);
      return this;
    }

    /**
     * Append a single tool. A preset's tools stay; two tools with one name, a built-in included,
     * fail {@link AgentSession#open}.
     *
     * @param tool non-null binding
     * @return this builder
     * @throws NullPointerException if {@code tool} is null
     */
    public Builder withTool(ToolBinding tool) {
      tools.add(Objects.requireNonNull(tool, "tool must not be null"));
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
     * Set the permission policy. An internal {@code DefaultPermissionEvaluator} authorizes every
     * tool call as a fixed stage after every {@code PreToolUseHook}, on the exact call that runs;
     * no hook priority moves a hook past it. Defaults to {@link Permission#empty} in {@link
     * PermissionMode#DEFAULT}; a host where no one answers questions sets {@link
     * PermissionMode#BYPASS_PERMISSIONS} or allow rules for the tools its runs need.
     *
     * @param permission non-null policy
     * @return this builder
     * @throws NullPointerException if {@code permission} is null
     */
    public Builder withPermission(Permission permission) {
      this.permission = Objects.requireNonNull(permission, "permission must not be null");
      return this;
    }

    /**
     * Set (or clear) the memory backend. Pass {@code null} to clear. When non-null, the session
     * registers {@code MemoryRead} and {@code MemoryWrite} over this backend; for read-only memory,
     * deny {@code MemoryWrite} with a permission rule.
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
     * Set the execution provider, such as {@code LocalProcessExecutionProvider.defaultPosix()}. The
     * session registers {@code Execute} over it and runs its {@code onSessionStart} and {@code
     * onSessionEnd} with the session; registering {@code ExecuteTool.binding} by hand as well fails
     * {@link AgentSession#open} with a duplicate tool name. Unset by default: the session has no
     * {@code Execute} tool, and a tool named {@code Execute} fails {@link AgentSession#open}.
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
     * Register the {@code AskUserQuestion} tool, through which the model asks the user a structured
     * question and waits for {@link AgentSession#answer}. Off by default: a chat agent asks by
     * ending its turn with text. Permission approvals ask the user with or without it.
     *
     * @return this builder
     */
    public Builder withAskUserQuestion() {
      this.askUserQuestion = true;
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
     * on and off across turns — every model dispatch carries it, and {@link
     * AgentSession#runBlocking(UserMessage, OutputSchema)} accepts only this schema.
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
     * <p>Presets supply their strategy text here. Stacking presets that both set a system prompt is
     * "later wins" — for layered behaviour, build the combined prompt externally and set it once.
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
     * SummarizingCompactor} bound to the session's {@code model}. Pass {@link
     * ContextCompactor#disabled()} to opt out of automatic compaction wholesale; pass a custom
     * implementation, or a {@code SummarizingCompactor} built with another model or prompt, to
     * override it.
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
     * Set the registry of secrets the session redacts from every tool result and from the error
     * text it reports. Pass the same registry to every component that registers or needs secrets,
     * such as {@code CommandGrant.Builder.withSecretRegistry}. Defaults to a new empty registry.
     *
     * @param secretRegistry non-null registry
     * @return this builder
     * @throws NullPointerException if {@code secretRegistry} is null
     */
    public Builder withSecretRegistry(SecretRegistry secretRegistry) {
      this.secretRegistry =
          Objects.requireNonNull(secretRegistry, "secretRegistry must not be null");
      return this;
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
              : SummarizingCompactor.newBuilder(model).build();
      return new SessionOptions(
          model,
          id,
          limits,
          maxQueuedUserMessages,
          clock,
          tools,
          hooks,
          permission,
          Optional.ofNullable(memoryBackend),
          costCalculator,
          Optional.ofNullable(executionProvider),
          askUserQuestion,
          Optional.ofNullable(outputSchema),
          Optional.ofNullable(systemPrompt),
          tokenCounter,
          compactor,
          secretRegistry != null ? secretRegistry : new SecretRegistry());
    }
  }
}
