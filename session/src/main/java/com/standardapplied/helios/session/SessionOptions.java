/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.CostCalculator;
import com.standardapplied.helios.core.common.Ids;
import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.common.Validate;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.session.execution.ExecutionProvider;
import com.standardapplied.helios.session.hooks.Hook;
import com.standardapplied.helios.session.memory.MemoryBackend;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.store.SessionStore;
import com.standardapplied.helios.session.tools.ToolBinding;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * @param sessionId stable session id, one to 128 characters of {@code [A-Za-z0-9._:-]} not starting
 *     with {@code .} ({@link SessionStore#requireValidId}); {@code "sess-"} and a fresh id if not
 *     set on the builder
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
 *     is opt-in — which a session with a budget refuses; non-null
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
 * @param tokenCounter estimates what the provider has not reported yet: the messages added since
 *     its last report, or the whole history with no report. When the count crosses {@code 0.85} of
 *     {@link SessionLimits#maxContextTokens()}, the loop emits a {@link QueryEvent.ContextWarning
 *     ContextWarning}; the same count is used to detect the 0.95 compaction watermark. Defaults to
 *     {@link TokenCounter#charBased()} — wire a provider-specific tokenizer when accuracy matters
 *     more than the per-turn cost. Non-null
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
 * @param store optional store that makes the session durable: {@link AgentSession#open} loads the
 *     conversation stored under {@code sessionId}, or starts it when there is none, and every turn
 *     appends what it added. Absent by default: the conversation lives only in the session
 * @param openTelemetry the OpenTelemetry the session's spans are emitted through: an {@code
 *     invoke_agent} span per run, with a {@code chat} span per model attempt, an {@code
 *     execute_tool} span per tool call and a {@code hook} span per hook decision under it. Absent
 *     by default: the session uses {@link GlobalOpenTelemetry#get()} when it opens, a no-op unless
 *     the application installed an SDK
 * @param agentName the name of the session's runs, the {@code gen_ai.agent.name} of their spans;
 *     non-blank, defaults to {@code "helios"}
 * @param traceDetail how much content the session's spans carry; {@link TraceDetail#STANDARD}
 *     carries none, {@link TraceDetail#VERBOSE} the redacted input, output, tool arguments and
 *     results and hook texts. Non-null, defaults to {@code STANDARD}
 * @param traceAttributes the host's labels every run span of the session carries, each key starting
 *     with {@code app.}; copied, non-null with no null key or value, defaults to none
 * @param subagents the child agents the model may delegate to; with at least one, the session
 *     registers the built-in {@code Agent} tool, which runs one of them per call (see {@link
 *     Builder#withSubagent}). Copied, non-null with no null element, unique by name, defaults to
 *     none
 * @param skills the skills the model may load; with at least one, the system prompt ends with their
 *     catalog and the session registers the built-in {@code Skill} tool (see {@link
 *     Builder#withSkills}). Copied, non-null with no null element, unique by name, defaults to none
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
    SecretRegistry secretRegistry,
    Optional<SessionStore> store,
    Optional<OpenTelemetry> openTelemetry,
    String agentName,
    TraceDetail traceDetail,
    Map<String, String> traceAttributes,
    List<Subagent> subagents,
    List<Skill> skills) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any argument is null or {@code tools}, {@code hooks}, {@code
   *     subagents} or {@code skills} holds a null element
   * @throws IllegalArgumentException if {@code sessionId} breaks the session-id rule, {@code
   *     maxQueuedUserMessages} is not positive, {@code limits} sets a budget while {@code
   *     costCalculator} is {@link CostCalculator#ZERO}, {@code agentName} is blank, a key of {@code
   *     traceAttributes} does not start with {@code app.}, {@code subagents} break a rule of {@link
   *     Builder#withSubagent}, or two skills share a name
   */
  public SessionOptions {
    Objects.requireNonNull(model, "model must not be null");
    SessionStore.requireValidId(sessionId);
    Objects.requireNonNull(limits, "limits must not be null");
    requirePositiveQueue(maxQueuedUserMessages);
    Objects.requireNonNull(clock, "clock must not be null");
    tools = List.copyOf(Objects.requireNonNull(tools, "tools must not be null"));
    Objects.requireNonNull(hooks, "hooks must not be null");
    hooks = List.copyOf(hooks);
    Objects.requireNonNull(permission, "permission must not be null");
    Objects.requireNonNull(memoryBackend, "memoryBackend must not be null");
    Objects.requireNonNull(costCalculator, "costCalculator must not be null");
    requireCalculatorForBudget(limits, costCalculator);
    Objects.requireNonNull(executionProvider, "executionProvider must not be null");
    Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
    Objects.requireNonNull(tokenCounter, "tokenCounter must not be null");
    Objects.requireNonNull(contextCompactor, "contextCompactor must not be null");
    Objects.requireNonNull(secretRegistry, "secretRegistry must not be null");
    Objects.requireNonNull(store, "store must not be null");
    Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
    Validate.notBlank("agentName", agentName);
    Objects.requireNonNull(traceDetail, "traceDetail must not be null");
    traceAttributes = appAttributes(traceAttributes);
    subagents = List.copyOf(Objects.requireNonNull(subagents, "subagents must not be null"));
    requireValidSubagents(subagents, tools, limits);
    skills = List.copyOf(Objects.requireNonNull(skills, "skills must not be null"));
    requireUniqueSkillNames(skills);
  }

  private static void requireUniqueSkillNames(List<Skill> skills) {
    var names = new HashSet<String>();
    for (var skill : skills) {
      if (!names.add(skill.name())) {
        throw new IllegalArgumentException("duplicate skill name: " + skill.name());
      }
    }
  }

  /**
   * Refuse a subagent without a cost calculator, and one of its own subagents at any depth: each
   * runs under what the budget has left, which could not count its spend.
   */
  private static void requireCostCalculators(List<Subagent> subagents) {
    for (var subagent : subagents) {
      if (subagent.options().costCalculator() == CostCalculator.ZERO) {
        throw new IllegalArgumentException(
            "subagent \""
                + subagent.name()
                + "\" has no cost calculator, so the session's maxBudgetMicroUsd could not count"
                + " its spend; set its SessionOptions.Builder.withCostCalculator");
      }
      requireCostCalculators(subagent.options().subagents());
    }
  }

  /**
   * Refuse subagents with one name, a tool of the options' own that the {@code Agent} tool would
   * collide with, and, under a budget, a subagent whose spend the budget could not count.
   */
  private static void requireValidSubagents(
      List<Subagent> subagents, List<ToolBinding> tools, SessionLimits limits) {
    if (subagents.isEmpty()) {
      return;
    }
    var names = new HashSet<String>();
    for (var subagent : subagents) {
      if (!names.add(subagent.name())) {
        throw new IllegalArgumentException("duplicate subagent name: " + subagent.name());
      }
    }
    if (limits.maxBudgetMicroUsd().isPresent()) {
      requireCostCalculators(subagents);
    }
    if (tools.stream().anyMatch(tool -> AgentTool.NAME.equals(tool.name()))) {
      throw new IllegalArgumentException(
          "a tool named " + AgentTool.NAME + " collides with the built-in tool subagents add");
    }
  }

  /**
   * A copy of {@code attributes}, each key {@code app.} and a name, so a host's label never
   * replaces one of Helios's {@code helios.*} or {@code gen_ai.*} attributes.
   */
  private static Map<String, String> appAttributes(Map<String, String> attributes) {
    var copy = Map.copyOf(Objects.requireNonNull(attributes, "traceAttributes must not be null"));
    for (var key : copy.keySet()) {
      if (!key.startsWith("app.") || key.length() == "app.".length()) {
        throw new IllegalArgumentException(
            "trace attribute key \"" + key + "\" must start with \"app.\" and name an attribute");
      }
    }
    return copy;
  }

  private static void requireCalculatorForBudget(
      SessionLimits limits, CostCalculator costCalculator) {
    if (limits.maxBudgetMicroUsd().isPresent() && costCalculator == CostCalculator.ZERO) {
      throw new IllegalArgumentException(
          "maxBudgetMicroUsd is set but no cost calculator is: every model call would cost zero"
              + " and the budget would never stop the session; set"
              + " SessionOptions.Builder.withCostCalculator");
    }
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
    store.ifPresent(builder::withStore);
    openTelemetry.ifPresent(builder::withOpenTelemetry);
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
        .withSecretRegistry(secretRegistry)
        .withAgentName(agentName)
        .withTraceDetail(traceDetail)
        .withTraceAttributes(traceAttributes)
        .withSubagents(subagents)
        .withSkills(skills);
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
    private final List<ToolBinding> tools = new ArrayList<>();
    private final List<Hook> hooks = new ArrayList<>();
    private Permission permission = Permission.empty(PermissionMode.DEFAULT);
    private CostCalculator costCalculator = CostCalculator.ZERO;
    private final Wiring wiring = new Wiring();
    private OutputSchema<?> outputSchema;
    private String systemPrompt;
    private TokenCounter tokenCounter = TokenCounter.charBased();
    private ContextCompactor contextCompactor;
    private SecretRegistry secretRegistry;
    private SessionStore store;

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
     * Set the session id, the key of the conversation in the session's store. An id is one to 128
     * characters of {@code [A-Za-z0-9._:-]} and does not start with {@code .}, so it works with
     * every store and never names a path outside a file store's directory. Defaults to {@code
     * "sess-"} and a fresh id.
     *
     * @param sessionId the id
     * @return this builder
     * @throws NullPointerException if {@code sessionId} is null
     * @throws IllegalArgumentException if {@code sessionId} breaks the rule
     */
    public Builder withSessionId(String sessionId) {
      this.sessionId = SessionStore.requireValidId(sessionId);
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
      wiring.memoryBackend = memoryBackend;
      return this;
    }

    /**
     * Set the cost calculator. Defaults to {@link CostCalculator#ZERO}, which means cost tracking
     * is disabled; a session with {@link SessionLimits#maxBudgetMicroUsd()} set needs a calculator,
     * and {@link #build()} refuses {@code ZERO} with a budget. Wire a {@link
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
      wiring.executionProvider =
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
      wiring.askUserQuestion = true;
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
     * <p>The session redacts each tool result's text once, before its {@link
     * QueryEvent.ToolResult}, {@code PostToolUse} hooks and the history see it: every tool's,
     * success or failure, the results the loop substitutes and a {@code MutateResult}'s text. It
     * also redacts the arguments its tool events carry, a {@code ToolBlocked} reason and the
     * message of every error it reports or logs. Each pass reads the registry afresh, so a secret
     * registered mid-session counts from the next result on. Not covered: text that does not come
     * from tools (user messages, the system prompt, a hook's {@code Inject} text, the model's own
     * output), {@code Read}'s image and PDF attachments, the runtime's HTTP error bodies, span
     * attributes other than the content {@link TraceDetail#VERBOSE} adds, which {@code
     * PgSpanExporter} stores verbatim unless its {@code PgConfig} has a redactor, what your own
     * instrumentation records under a Helios span, and a secret the model rebuilds from pieces:
     * redaction matches exact text.
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
     * Store the session in {@code store}: {@link AgentSession#open} loads the conversation stored
     * under the session id, or starts it when there is none, and every turn appends what it added.
     * One writer per session id at a time is the caller's job; a second writer's append fails its
     * run with {@code ErrorDuringExecution} of kind {@code SessionConflict} and closes its session.
     *
     * @param store non-null store
     * @return this builder
     * @throws NullPointerException if {@code store} is null
     */
    public Builder withStore(SessionStore store) {
      this.store = Objects.requireNonNull(store, "store must not be null");
      return this;
    }

    /**
     * Emit the session's spans through {@code openTelemetry}, whose tracer is fetched once per
     * session under the scope {@code com.standardapplied.helios}. A run's span is a child of the
     * span current where {@code send} was called, and a tool's span is current while the tool runs,
     * so what the tool's own instrumentation records nests under it. A {@link RuntimeException} a
     * span call throws, such as a span processor's, is logged and changes no run's result; an
     * {@link Error} fails the run as any host {@code Error} does. Unset by default: the session
     * uses {@link GlobalOpenTelemetry#get()} when it opens, which, with no SDK installed, installs
     * the no-op as the global, so install a global SDK before opening such a session.
     *
     * @param openTelemetry non-null OpenTelemetry
     * @return this builder
     * @throws NullPointerException if {@code openTelemetry} is null
     */
    public Builder withOpenTelemetry(OpenTelemetry openTelemetry) {
      wiring.openTelemetry =
          Objects.requireNonNull(openTelemetry, "openTelemetry must not be null");
      return this;
    }

    /**
     * Name the session's runs: each run's span is {@code invoke_agent <agentName>}. Defaults to
     * {@code "helios"}.
     *
     * @param agentName non-blank name
     * @return this builder
     * @throws NullPointerException if {@code agentName} is null
     * @throws IllegalArgumentException if {@code agentName} is blank
     */
    public Builder withAgentName(String agentName) {
      wiring.agentName = Validate.notBlank("agentName", agentName);
      return this;
    }

    /**
     * Set how much content the session's spans carry. Defaults to {@link TraceDetail#STANDARD},
     * which carries none.
     *
     * @param traceDetail non-null detail
     * @return this builder
     * @throws NullPointerException if {@code traceDetail} is null
     */
    public Builder withTraceDetail(TraceDetail traceDetail) {
      wiring.traceDetail = Objects.requireNonNull(traceDetail, "traceDetail must not be null");
      return this;
    }

    /**
     * Label the session's runs: every run span carries {@code traceAttributes}, which {@code
     * PgSpanExporter} stores, through its redactor, in the run row's {@code attributes}. Each key
     * starts with {@code app.}, so a label never replaces one of Helios's {@code helios.*} or
     * {@code gen_ai.*} attributes. Replaces any set before; the map is copied. Defaults to none.
     *
     * @param traceAttributes non-null labels, with no null key or value
     * @return this builder
     * @throws NullPointerException if {@code traceAttributes}, a key or a value is null
     * @throws IllegalArgumentException if a key does not start with {@code app.} or is {@code app.}
     *     alone
     */
    public Builder withTraceAttributes(Map<String, String> traceAttributes) {
      wiring.traceAttributes = appAttributes(traceAttributes);
      return this;
    }

    /**
     * Set the full subagent list. Replaces any previously-added subagents. Defaults to none.
     *
     * @param subagents non-null list of subagents
     * @return this builder
     * @throws NullPointerException if {@code subagents} is null or contains null elements
     */
    public Builder withSubagents(List<Subagent> subagents) {
      var copy = List.copyOf(Objects.requireNonNull(subagents, "subagents must not be null"));
      wiring.subagents.clear();
      wiring.subagents.addAll(copy);
      return this;
    }

    /**
     * Append a subagent the model may delegate to. With at least one, the session registers the
     * built-in {@code Agent} tool, whose input is {@code {"agent": <name>, "prompt": <text>}}, both
     * required, and whose description lists each {@code name: description}, its {@code agent}
     * parameter the names; a call naming no subagent is a failure listing the names. It is an
     * {@link com.standardapplied.helios.session.tools.ToolCategory#EXECUTION EXECUTION} tool keyed
     * {@code Agent(<name>)}, so a permission rule allows, asks or denies one subagent, and hooks
     * apply to it as to any tool.
     *
     * <p>Each call runs one fresh child session from the subagent's options (see {@link Subagent}),
     * sends it the prompt, waits for its run to end and closes it. A child's {@code Success} is the
     * call's result: its text, or its JSON under an output schema; any other terminal is a failure
     * naming the subagent, the terminal and why it ended, and so is a child that cannot open. The
     * child's secret registry redacts the answer before this session's does.
     *
     * <ul>
     *   <li><b>Spend</b>: the child's usage and cost, whatever its terminal, are added to the
     *       parent run's as the child spends, so the parent's {@link ResultMessage} and its budget
     *       check after the turn count them. Under {@link SessionLimits#maxBudgetMicroUsd()}, the
     *       child runs with the lower of its own budget and what the parent run has left, and a
     *       call with nothing left fails without starting one. A child still stopping when this
     *       session's run stops waiting for it, {@code stopGrace} after the run was cancelled, is
     *       left running as any tool call is; what it accounts after that is in its own result
     *       only.
     *   <li><b>Time</b>: the call's deadline is the subagent's {@link SessionLimits#maxWallClock()}
     *       plus its {@link SessionLimits#stopGrace()}, not this session's {@link
     *       SessionLimits#toolTimeoutDefault()}; this session's own {@code maxWallClock} still
     *       bounds its run. The call's cancellation, by an interrupt, a close, the call's deadline
     *       or the run's wall clock, interrupts and closes the child, which ends {@link
     *       ResultMessage.Cancelled}; at the deadline the call waits the subagent's {@code
     *       stopGrace} for the child to close and answers with that failure.
     *   <li><b>Linkage</b>: the child's run span is a child of the call's {@code execute_tool} span
     *       and carries {@code helios.parent.session_id}, {@code helios.parent.run_id} and {@code
     *       helios.parent.tool_call_id}, which {@code PgSpanExporter} stores in the child's run
     *       row.
     *   <li><b>Store</b>: a child whose options have a store is stored under its own id.
     * </ul>
     *
     * <p>A child runs in the foreground of the call; background work, delivering its result into
     * this session and caps on concurrent children are the host's, linked with its own {@link
     * #withTraceAttributes labels}.
     *
     * @param subagent non-null subagent
     * @return this builder
     * @throws NullPointerException if {@code subagent} is null
     */
    public Builder withSubagent(Subagent subagent) {
      wiring.subagents.add(Objects.requireNonNull(subagent, "subagent must not be null"));
      return this;
    }

    /**
     * Set the full skill list. Replaces any previously-added skills. Defaults to none.
     *
     * <p>With at least one skill, the session's system prompt is the host's prompt, a blank line
     * and the catalog of every skill's name and description in this order (the catalog alone
     * without a host prompt), and the session registers the built-in {@code Skill} tool. A call
     * {@code {"skill": <name>}} loads the skill: it answers with the skill's instructions, its
     * directory when it has one, and the list of its files; a call that also names a {@code file}
     * listed there answers with that file's text. A skill already loaded earlier in the
     * conversation answers that it is loaded and reads nothing. It is a {@link
     * com.standardapplied.helios.session.tools.ToolCategory#READ READ} tool keyed {@code
     * Skill(<name>)} or {@code Skill(<name>/<file>)}, so it runs without asking in every mode but
     * {@code LOCKED_DOWN}, a permission rule allows or denies one skill or its files, and hooks
     * apply to it as to any tool. A host tool named {@code Skill} fails {@link AgentSession#open}.
     *
     * <p>A compaction that removes what the model loaded gets it put back: right after the
     * compacted history's leading system messages, one user message per skill, tagged {@link
     * com.standardapplied.helios.session.store.MessageOrigin#SKILL}, with the text the model was
     * given. A host that sends a skill's instructions in its own message, for a user-typed {@code
     * /skill-name}, sends no activation, so a compaction does not put that message back.
     *
     * <p>Skills are the host's trust decision: what a skill says reaches the model as instructions,
     * and no preset adds one. Helios runs no skill code; a script runs through {@code Execute}
     * under the session's permission policy, at the path its activation names.
     *
     * @param skills non-null list of skills
     * @return this builder
     * @throws NullPointerException if {@code skills} is null or contains null elements
     */
    public Builder withSkills(List<Skill> skills) {
      var copy = List.copyOf(Objects.requireNonNull(skills, "skills must not be null"));
      wiring.skills.clear();
      wiring.skills.addAll(copy);
      return this;
    }

    /**
     * Append a skill the model may load, as {@link #withSkills} describes.
     *
     * @param skill non-null skill
     * @return this builder
     * @throws NullPointerException if {@code skill} is null
     */
    public Builder withSkill(Skill skill) {
      wiring.skills.add(Objects.requireNonNull(skill, "skill must not be null"));
      return this;
    }

    /**
     * Build the immutable record.
     *
     * @return the options
     * @throws IllegalStateException if {@code model} was never set
     * @throws IllegalArgumentException if the limits set a budget and no cost calculator is set;
     *     with subagents, if two share a name, a tool of the options is named {@code Agent}, or the
     *     limits set a budget and a subagent's cost calculator is {@link CostCalculator#ZERO}; if
     *     two skills share a name
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
          Optional.ofNullable(wiring.memoryBackend),
          costCalculator,
          Optional.ofNullable(wiring.executionProvider),
          wiring.askUserQuestion,
          Optional.ofNullable(outputSchema),
          Optional.ofNullable(systemPrompt),
          tokenCounter,
          compactor,
          secretRegistry != null ? secretRegistry : new SecretRegistry(),
          Optional.ofNullable(store),
          Optional.ofNullable(wiring.openTelemetry),
          wiring.agentName,
          wiring.traceDetail,
          wiring.traceAttributes,
          wiring.subagents,
          wiring.skills);
    }

    /**
     * What {@code SessionWiring} turns into the session's collaborators when it opens: the built-in
     * tools the session registers when switched on (the memory tools over a backend, {@code
     * Execute} over a provider, {@code AskUserQuestion}, {@code Agent} over subagents and {@code
     * Skill} over skills), and its spans: where they go, what its runs are named and labelled and
     * how much content they carry.
     */
    private static final class Wiring {

      private MemoryBackend memoryBackend;
      private ExecutionProvider executionProvider;
      private boolean askUserQuestion;
      private OpenTelemetry openTelemetry;
      private String agentName = "helios";
      private TraceDetail traceDetail = TraceDetail.STANDARD;
      private Map<String, String> traceAttributes = Map.of();
      private final List<Subagent> subagents = new ArrayList<>();
      private final List<Skill> skills = new ArrayList<>();
    }
  }
}
