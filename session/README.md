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
  <groupId>com.standardapplied</groupId>
  <artifactId>helios-session</artifactId>
  <version>${helios.version}</version>
</dependency>
<dependency>
  <groupId>com.standardapplied</groupId>
  <artifactId>helios-anthropic</artifactId>  <!-- or -gemini, -openai -->
  <version>${helios.version}</version>
</dependency>
```

JPMS:

```java
requires com.standardapplied.helios.session;
requires com.standardapplied.helios.anthropic;
```

## Quickstart — minimal session

<!-- snippet: QuickStarts#minimalSession -->
```java
var model =
    new AnthropicProvider()
        .create(
            AnthropicModelId.CLAUDE_SONNET_4_6.id(),
            ModelConfig.of(System.getenv("ANTHROPIC_API_KEY")));

try (var session = AgentSession.create(SessionPresets.minimal(model).build())) {
  var terminal = session.runBlocking(UserMessage.text("What is the capital of France?"));
  switch (terminal) {
    case ResultMessage.Success s -> System.out.println(s.result());
    case ResultMessage other -> System.err.println("non-Success: " + other);
  }
}
```

`SessionPresets.minimal(model)` returns a `SessionOptions.Builder` already wired with the model
and Helios defaults; chain any other `.with*` calls before `.build()`. `try-with-resources`
guarantees the session's runtime (publisher, executor, in-flight tools) is shut down.

## Workspace agent — file tools + permissions + memory

<!-- snippet: QuickStarts#workspaceSession -->
```java
try (var session =
    AgentSession.create(SessionPresets.workspace(model, Path.of("/path/to/repo")).build())) {
  var terminal =
      session.runBlocking(UserMessage.text("Summarise the public API of the session module."));
  System.out.println(((ResultMessage.Success) terminal).result());
}
```

The `workspace` preset wires:
- `Read`, `Glob`, `Grep`, `LS` file tools rooted at the workspace,
- a `FileSystemMemoryBackend` under the workspace, for which the session registers `MemoryRead`
  and `MemoryWrite`,
- `Permission.defaultInWorkspace()` — reads + memory allowed, writes / edit / execute asked.

`SessionPresets.readOnly(model, root)` is the same shape without memory, gated by
`Permission.planMode()` for purely read-only inspection. `SessionPresets.openEnded(model, root,
provider)` adds `Execute` through the given `ExecutionProvider`, asked before each run.

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

The sealed `QueryEvent` interface — `AssistantText`, `AssistantThinking`, `AssistantCitations`, `UserMessageReceived`, `MessageBlocked`, `ContextWarning`, `ContextEdited`, `ToolUse`, `ToolResult`, `ToolBlocked`, `ToolMutated`, `HookFired`, `QuestionAsked`, `TurnEnded`, `TurnRetried`, `LoopEnded`, `Error`; see the [package
Javadoc](src/main/java/com/standardapplied/helios/session/QueryEvent.java) — covers everything the loop emits.

## Cost tracking + budget cap

<!-- snippet: QuickStarts#budgetCap -->
```java
var pricing = Map.of("claude-sonnet-4-6", CostCalculator.Pricing.ofUsdPerMillion(3.0, 15.0));
var limits =
    SessionLimits.newBuilder()
        .withMaxBudgetMicroUsd(5 * CostEstimate.MICRO_USD_PER_USD) // $5
        .build();

try (var session =
    AgentSession.create(
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

<!-- snippet: QuickStarts#permissions -->
```java
var policy =
    new Permission(
        PermissionMode.DEFAULT,
        List.of(PermissionRule.of("Read", "./src/**")),
        List.of(PermissionRule.of("MemoryWrite", "/memories/**")),
        List.of(PermissionRule.of("Read", "**/secrets.*")));

try (var session =
    AgentSession.create(SessionPresets.workspace(model, root).withPermission(policy).build())) {
  session.runBlocking(UserMessage.text("Summarise the public API under src."));
}
```

Rule order, in every mode: **deny > ask > allow > the mode's default for the tool's category**.
A rule has no effect of its own; the list it sits in is its effect. ASK decisions route through
the session's `QuestionGateway` and surface as an `AskUserQuestion` prompt the host answers via
`session.answer(questionId, response)`. A session built without `withPermission` gets
`Permission.empty(PermissionMode.DEFAULT)`: reads run, writes, execution and network ask. A
headless host sets `Permission.empty(PermissionMode.BYPASS_PERMISSIONS)`, or allow rules for the
tools its runs need. `SessionPresets.workspace(...)` ships `Permission.defaultInWorkspace()`;
`SessionPresets.readOnly(...)` ships `Permission.planMode()`.

An allow never carves an exception out of a matching ask: "ask broadly, allow a narrow exception"
(`ASK Read(./secrets/**)` with `ALLOW Read(./secrets/README.md)`) asks for the exception too.
Where the mode already asks for the tool's category (`WRITE`, `EXECUTION`, `NETWORK` under
`DEFAULT`), drop the broad ask and keep the allow. For a `READ` or `CONTROL` tool, or under
`LOCKED_DOWN`, there is no equivalent: list the exception's siblings as asks instead. This is the
order Claude Code uses.

A path tool's key is the canonical path it touches, however the model spelled it: `.` or `./a/b`
in the workspace (`secrets/key`, `docs/../secrets/key`, an absolute path and a symlink to it are
all `./secrets/key`), `/memories/` or `/memories/a/b` in memory. A path that names no target is
refused before any rule is evaluated, and the tool runs on the path that was authorised. Globs
match that spelling: a trailing `/**` matches the path before it and everything below it (`./**`
matches `.`), `/**/` and a leading `**/` match zero or more segments, `*` and `?` stay within a
segment. Rules name real paths: a rule written through a symlink, such as `./link/**`, never
matches, since a key never goes through one, and creation cannot detect that. A rule for a path tool
that no canonical path can match, such as `Read("secrets/**")`, fails `AgentSession.create`. A call
whose key carries a registered secret is refused. `Glob`, `Grep`, `LS`
and a `MemoryRead` listing withhold every entry below their path that one of their own DENY rules
matches (for `Grep`, which returns contents, also every entry a `Read` DENY or ASK rule decides,
without asking, in every mode), never descend into a withheld directory, and end with
`[withheld by permission policy: <n>]`. The decision that admitted a walk's start covers what it finds
except what those rules withhold; a mode or category default, such as `LOCKED_DOWN`'s deny, is not
a rule and withholds nothing. Memory is its own namespace: a memory call is authorised by
`MemoryRead`/`MemoryWrite` rules on its `/memories/...` key, and a `Read` rule over the directory
backing a filesystem memory store does not apply to it.

## Hooks

Seven phases: `OnUserMessage`, `PreModelTurn`, `PostModelTurn`, `PreToolUse`, `PostToolUse`,
`PreStop` (each method returns its phase's outcome type, so the compiler lists what is legal) and
the observe-only `OnStreamEvent`, the lossless tap. Hooks run by priority; the first
non-`Continue` outcome wins. `PreToolUse` hooks chain `MutateArgs` and fail closed (throw or
`null` = `Block`); other phases treat throw or `null` as `Continue`; an `Error` always escapes.
Example: deny a tool call from a hook regardless of policy.

<!-- snippet: QuickStarts#hooks -->
```java
PreToolUseHook denyShell =
    (call, ctx) -> {
      if (call.name().equals("Execute")) {
        return HookOutcome.block("Execute disabled in this deployment");
      }
      return HookOutcome.cont();
    };

var session =
    AgentSession.create(SessionPresets.workspace(model, root).withHook(denyShell).build());
```

Hook outcomes that drop a message (`OnUserMessageHook.Block`) emit
`QueryEvent.MessageBlocked` so UIs can render the drop.

## Session limits

`SessionLimits.newBuilder()` exposes every cap:

- `withMaxTurns(int)` — hard loop-iteration ceiling (default 100)
- `withMaxBudgetMicroUsd(long)` — USD spend cap (default unset; opt-in)
- `withMaxWallClock(Duration)` — wall-clock ceiling (default 1h)
- `withToolTimeoutDefault(Duration)` — per-tool execution timeout (default 2 min)
- `withMaxContextTokens(long)` — soft trigger for compaction (default 0 = auto: the model's window minus the output reserve)

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

The default compactor — `SummarizingCompactor` — keeps every leading system message and the
pending user turn (the user messages after the last assistant turn), and replaces the rest of the
conversation with one user-role summary, prefixed `SummarizingCompactor.SUMMARY_PREFIX`, produced
by one call against the session's `Model`. No earlier assistant turn survives, so no reasoning a
provider signed against the old conversation (Claude Fable 5.1, Opus 5.5 and Sonnet 5.5 bind each
thinking block to its exact request prefix) is replayed, and no `tool_call` or `TOOL` message is
left to pair: the result is valid for every provider. The agent continues from the summary alone,
so the default prompt asks for the user's goal, the facts found, the decisions made, the open
questions and the work left. It never compacts in the middle of a tool round. When the summary
call throws, times out (default 60 s) or returns blank, the compactor returns the history
unchanged — compaction failure never crashes the loop.

Three library-user tiers of control:

<!-- snippet: QuickStarts#compaction -->
```java
// 1) Default — SummarizingCompactor bound to the session's model
var session = AgentSession.create(SessionPresets.workspace(model, repo).build());

// 2) BYO compactor — a cheaper summary model or prompt, or any ContextCompactor of your own
var custom =
    SummarizingCompactor.newBuilder(cheapHaikuModel)
        .withSummaryPrompt("Summarise focusing on file edits and unresolved questions.")
        .withSummaryTimeout(Duration.ofSeconds(30)) // bound the summary call
        .build();
var session2 =
    AgentSession.create(
        SessionPresets.workspace(model, repo).withContextCompactor(custom).build());

// 3) Opt out wholesale — long sessions overflow naturally with an ErrorDuringExecution
var session3 =
    AgentSession.create(
        SessionPresets.workspace(model, repo)
            .withContextCompactor(ContextCompactor.disabled())
            .build());
```

For ad-hoc, hook-driven rewrites (no `ContextCompactor` subclass), register a
`PreModelTurnHook` that returns `HookOutcome.mutateHistory(rewrittenHistory)`.
The loop swaps the history and emits `HookFired` with `outcomeKind=MutateHistory`.
To see the history on both sides of a compaction, wrap the default compactor and delegate to it,
as the [root README](../README.md#context-compaction) shows.

`SessionOptions.Builder.withTokenCounter(...)` swaps the estimator — wire a provider-specific
tokenizer (Anthropic's, OpenAI's tiktoken, etc.) when the char-based default is too rough.

The compaction step's `Usage` (input/output tokens consumed by the summary call) flows through
`SessionState.usage()` and the session's `CostCalculator` so `SessionLimits.maxBudgetMicroUsd`
gates compaction spend along with normal turn spend. Custom compactors report this via
`CompactionResult.usage()`; pure-trim compactors return `CompactionResult.noOp(history)`.

## Going further

- The HTTP surface — `helios-runtime` — exposes a session over REST + SSE for any
  language client. POST a message, GET an SSE stream of events, GET the result, DELETE to clean
  up. See `runtime/` for the routes.
- `examples/session-demo` shows a full end-to-end run against Gemini against a real workspace.
- The package-level Javadocs cover each subsystem: `com.standardapplied.helios.session.hooks`,
  `com.standardapplied.helios.session.permissions`, `com.standardapplied.helios.session.tools`, `com.standardapplied.helios.session.memory`,
  `com.standardapplied.helios.session.files`, `com.standardapplied.helios.session.ask`, `com.standardapplied.helios.session.loop`.
