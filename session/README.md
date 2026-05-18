# helios-session

The v2 agentic SDK for the JVM. Open-ended, streamable, steerable sessions modelled on the
Claude Code agent loop.

Compared to `helios-core`'s `Agent` (single shot, you call `run(...)`, you get back a result), a
session is a long-lived object that runs an agent loop on a virtual thread. You **send** messages
into it, **subscribe** to its event stream, and optionally **interrupt** mid-turn. It hits its own
terminal `ResultMessage` when the model is done — you can long-poll for it via
`session.result()`, listen for the `LoopEnded` event, or just call `session.runBlocking(...)` and
get the terminal synchronously.

## Why sessions

The v1 `Agent` API works when you call the model once and want one answer. Sessions exist for the
shape that actually shows up in production agentic systems:

- the model may call tools across multiple turns,
- the user may interject mid-run with new instructions,
- a UI / HTTP client needs to observe the run as it happens (token deltas, tool calls, hook
  decisions),
- and the deployer wants permission gates around tool calls without rewriting the loop.

A `Model` from `helios-core` plugs in; the session owns everything else.

## Installation

```xml
<dependency>
  <groupId>ai.singlr</groupId>
  <artifactId>helios-session</artifactId>
  <version>${helios.version}</version>
</dependency>
<dependency>
  <groupId>ai.singlr</groupId>
  <artifactId>helios-anthropic</artifactId>  <!-- or -gemini, -openai -->
  <version>${helios.version}</version>
</dependency>
```

JPMS:

```java
requires ai.singlr.session;
requires ai.singlr.anthropic;
```

## Quickstart — minimal session

```java
var model = new AnthropicModel(
    AnthropicModelId.CLAUDE_SONNET_4_6,
    ModelConfig.of(System.getenv("ANTHROPIC_API_KEY")));

try (var session = AgentSession.create(
    SessionPresets.minimal(model).build())) {
  var terminal = session.runBlocking(UserMessage.text("What is the capital of France?"));
  switch (terminal) {
    case ResultMessage.Success s -> System.out.println(s.result());
    case ResultMessage other     -> System.err.println("non-Success: " + other);
  }
}
```

`SessionPresets.minimal(model)` returns a `SessionOptions.Builder` already wired with the model
and Helios defaults; chain any other `.with*` calls before `.build()`. `try-with-resources`
guarantees the session's runtime (publisher, executor, in-flight tools) is shut down.

## Workspace agent — file tools + permissions + memory

```java
try (var session = AgentSession.create(
    SessionPresets.workspace(model, Path.of("/path/to/repo"))
        .build())) {
  var terminal = session.runBlocking(UserMessage.text(
      "Summarise the public API of the session module."));
  System.out.println(((ResultMessage.Success) terminal).result());
}
```

The `workspace` preset wires:
- `Read`, `Glob`, `Grep`, `LS` file tools rooted at the workspace,
- `MemoryWrite` + auto-registered `MemoryRead` against `FileSystemMemoryBackend` under the
  workspace,
- `Permission.defaultInWorkspace()` — reads + memory allowed, writes / edit / execute asked.

`SessionPresets.readOnly(model, root)` is the same shape minus memory-write and gated by
`Permission.planMode()` for purely read-only inspection.

## Streaming events

Subscribers see every lifecycle event as the loop produces it — perfect for a UI that wants to
render tokens, tool calls, and hook decisions live:

```java
session.events().subscribe(new Flow.Subscriber<QueryEvent>() {
  public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
  public void onNext(QueryEvent event) {
    switch (event) {
      case QueryEvent.AssistantText t       -> System.out.print(t.text());
      case QueryEvent.ToolUse u             -> System.out.println("\n[tool: " + u.call().name() + "]");
      case QueryEvent.ToolResult r          -> System.out.println("[result: " + r.result().output() + "]");
      case QueryEvent.MessageBlocked b      -> System.err.println("[blocked: " + b.reason() + "]");
      case QueryEvent.LoopEnded e           -> System.out.println("\n— done —");
      default -> {}
    }
  }
  public void onError(Throwable t)    { t.printStackTrace(); }
  public void onComplete()            { /* stream terminated */ }
});
session.send("Refactor the loop module's package-info comments.");
var terminal = session.result().join();
```

The 15-subtype `QueryEvent` sealed interface — see the [package
Javadoc](src/main/java/ai/singlr/session/QueryEvent.java) — covers everything the loop emits.

## Cost tracking + budget cap

```java
var pricing = Map.of(
    "claude-sonnet-4-6", CostCalculator.Pricing.ofUsdPerMillion(3.0, 15.0));
var limits = SessionLimits.newBuilder()
    .withMaxBudgetMicroUsd(5 * CostEstimate.MICRO_USD_PER_USD)  // $5
    .build();

try (var session = AgentSession.create(
    SessionPresets.workspace(model, root)
        .withCostCalculator(CostCalculator.staticTable(pricing))
        .withLimits(limits)
        .build())) {
  var terminal = session.runBlocking(UserMessage.text("..."));
  if (terminal instanceof ResultMessage.ErrorMaxBudgetUsd b) {
    System.out.println("Budget exceeded at " + (b.microUsdSpent() / 1_000_000.0) + " USD");
  }
}
```

Currency is integer micro-USD (Stripe-style fixed-precision) — no `BigDecimal` allocation per
turn, no FP drift. `CostCalculator.ZERO` is the default; cost tracking is opt-in.

## Permissions

```java
var policy = new Permission(
    PermissionMode.DEFAULT,
    List.of(PermissionRule.withGlob(PermissionEffect.ALLOW, "Read", "./src/**")),  // allow
    List.of(PermissionRule.any(PermissionEffect.ASK, "Edit")),                     // ask
    List.of(PermissionRule.withGlob(PermissionEffect.DENY, "Read", "**/secrets.*")));  // deny

try (var session = AgentSession.create(
    SessionPresets.workspace(model, root)
        .withPermission(policy)
        .build())) {
  // ...
}
```

Rule order: **deny → bypass-shortcut → allow → ask → default-by-category**. ASK decisions route
through the session's `QuestionGateway` and surface as an `AskUserQuestion` prompt the host
answers via `session.answer(questionId, response)`. `SessionPresets.workspace(...)` ships
`Permission.defaultInWorkspace()`; `SessionPresets.readOnly(...)` ships `Permission.planMode()`.

## Hooks

Seven phase-specific hook surfaces — `OnUserMessageHook`, `PreModelTurnHook`,
`PostModelTurnHook`, `PreToolUseHook`, `PostToolUseHook`, `PreStopHook`, `OnStreamEventHook` —
each returning a `HookOutcome` (`Continue` / `MutateInput` / `Block` / `Inject` / `Stop`).
Example: deny a tool call from a hook regardless of policy.

```java
PreToolUseHook denyShell = (call, ctx) -> {
  if (call.name().equals("Execute")) {
    return HookOutcome.block("Execute disabled in this deployment");
  }
  return HookOutcome.cont();
};

var session = AgentSession.create(
    SessionPresets.workspace(model, root)
        .withHook(denyShell)
        .build());
```

Hook outcomes that drop a message (`OnUserMessageHook.Block`) emit
`QueryEvent.MessageBlocked` so UIs can render the drop.

## Session limits

`SessionLimits.newBuilder()` exposes every cap:

- `withMaxTurns(int)` — hard loop-iteration ceiling (default 100)
- `withMaxBudgetMicroUsd(long)` — USD spend cap (default unset; opt-in)
- `withMaxWallClock(Duration)` — wall-clock ceiling (default 1h)
- `withToolTimeoutDefault(Duration)` — per-tool execution timeout (default 2 min)
- `withMaxContextTokens(long)` — soft trigger for compaction (default 180_000)

Each cap maps to its own terminal `ResultMessage` subtype (`ErrorMaxTurns`,
`ErrorMaxBudgetUsd`, `ErrorMaxWallClock`, etc.).

## Context compaction

Long sessions outgrow the model's context window. The session loop estimates running history
fill via a pluggable `TokenCounter` (default: `TokenCounter.charBased()`, a cheap
~4-chars-per-token heuristic) and reacts at two watermarks:

- **0.85** — emits `QueryEvent.ContextWarning(usagePct)` once, so UIs and audit consumers can
  log or pause the session for SME review before the model hits a hard overflow.
- **0.95** — invokes the configured `ContextCompactor`, swaps the returned history in, and
  emits `QueryEvent.ContextEdited(removedBlocks, tokensBefore, tokensAfter)`. The warning flag
  resets so a future re-climb fires the watermark again.

The default compactor — `DropMiddleToolResultsCompactor` — preserves the system prompt + opening
turn (`headPreserved`, default 3), preserves the recent trajectory (`tailPreserved`, default 20),
and replaces everything in between with a single user-role summary produced by one call against
the session's `Model`. If the summary call throws or returns blank, the compactor returns the
history unchanged — compaction failure never crashes the loop.

Three library-user tiers of control:

```java
// 1) Default — DropMiddleToolResultsCompactor bound to the session's model
var session = AgentSession.create(SessionPresets.workspace(model, repo).build());

// 2) BYO compactor — Letta-style, vector-recall, custom head/tail policy, etc.
var custom = DropMiddleToolResultsCompactor.newBuilder(cheapHaikuModel)
    .withHeadPreserved(5)
    .withTailPreserved(30)
    .withSummaryPrompt("Summarise focusing on file edits and unresolved questions.")
    .build();
var session2 = AgentSession.create(
    SessionPresets.workspace(model, repo).withContextCompactor(custom).build());

// 3) Opt out wholesale — long sessions overflow naturally with an ErrorDuringExecution
var session3 = AgentSession.create(
    SessionPresets.workspace(model, repo)
        .withContextCompactor(ContextCompactor.disabled())
        .build());
```

For ad-hoc, hook-driven rewrites (no `ContextCompactor` subclass), register a
`PreModelTurnHook` that returns `HookOutcome.mutate(Map.of("history", rewrittenHistory))`.
The loop swaps the history and emits `HookFired` with `outcomeKind=MutateInput`.

`SessionOptions.Builder.withTokenCounter(...)` swaps the estimator — wire a provider-specific
tokenizer (Anthropic's, OpenAI's tiktoken, etc.) when the char-based default is too rough.

## Going further

- The HTTP surface — `helios-runtime` — exposes a session over REST + SSE for any
  language client. POST a message, GET an SSE stream of events, GET the result, DELETE to clean
  up. See `runtime/` for the routes.
- `examples/session-demo` shows a full end-to-end run against Gemini against a real workspace.
- The package-level Javadocs cover each subsystem: `ai.singlr.session.hooks`,
  `ai.singlr.session.permissions`, `ai.singlr.session.tools`, `ai.singlr.session.memory`,
  `ai.singlr.session.files`, `ai.singlr.session.ask`, `ai.singlr.session.loop`.
