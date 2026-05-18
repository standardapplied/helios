# Helios

Helios is an agentic runtime for the JVM. It provides a provider-agnostic model interface, multi-agent delegation, sandboxed code execution, structured output, and end-to-end tracing — primitives for building agents that decompose problems and run reliably in production.

Simple, explicit, no magic. No annotation-driven DI, no reflective surprises.

Published to [Maven Central](https://central.sonatype.com/namespace/ai.singlr) under the `ai.singlr` namespace. MIT licensed.

## Requirements

- Java 25+
- Maven 3.9+

## Modules

Pick what you need — each jar is published independently:

| Artifact | What it gives you | External deps |
|----------|-------------------|---------------|
| `helios-core` | Agent, Teams, Memory, Tools, Workflows, Tracing, Structured Output, Fault Tolerance | None |
| `helios-session` | v2 SDK — streamable, steerable sessions (`AgentSession`, `SessionPresets`) | Jackson 3.x |
| `helios-runtime` | Helidon HTTP/SSE surface for `helios-session` sessions | Helidon SE, Jackson 3.x |
| `helios-gemini` | Google Gemini provider (Interactions API) | Jackson 3.x |
| `helios-anthropic` | Anthropic Claude provider (Messages API) | Jackson 3.x |
| `helios-openai` | OpenAI GPT provider (Responses API) | Jackson 3.x |
| `helios-repl` | Sandboxed code execution (RLM pattern) | Jackson 3.x |
| `helios-onnx` | Local embeddings via ONNX Runtime | ONNX Runtime, DJL Tokenizers |
| `helios-persistence` | PostgreSQL-backed Memory, PromptRegistry, TraceStore | Helidon DbClient |

Most one-shot applications need `helios-core` + one provider. For long-running agentic
applications (streaming UIs, mid-run interrupts, permission gates), use `helios-session` + one
provider; expose it over HTTP with `helios-runtime`.

## Installation

```xml
<dependency>
  <groupId>ai.singlr</groupId>
  <artifactId>helios-core</artifactId>
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
requires ai.singlr.core;
requires ai.singlr.anthropic;
```

## Quick Start

```java
var model = new AnthropicModel(
    AnthropicModelId.CLAUDE_SONNET_4_6,
    ModelConfig.of(System.getenv("ANTHROPIC_API_KEY")));

var agent = new Agent(AgentConfig.newBuilder()
    .withName("assistant")
    .withModel(model)
    .withSystemPrompt("You are a helpful assistant.")
    .build());

var response = agent.run("What is the capital of France?").getOrThrow();
System.out.println(response.content());
```

All providers implement the same `Model` interface — swap providers without touching the rest of your code.

## Sessions — the v2 SDK (`helios-session`)

`Agent` is single-shot: you call `run(...)`, you get back one result. A **session** is a
long-lived object that runs an agent loop on a virtual thread — you `send` messages into it,
`subscribe` to its event stream, and optionally `interrupt` mid-turn. Same `Model` plugs in;
the session owns everything else (loop, hooks, permissions, tools, memory).

```java
try (var session = AgentSession.create(
    SessionPresets.workspace(model, Path.of("/path/to/repo"))
        .build())) {
  var terminal = session.runBlocking(UserMessage.text(
      "Summarise the public API of the session module."));
  System.out.println(((ResultMessage.Success) terminal).result());
}
```

`SessionPresets` ships three curated configurations:

- `minimal(model)` — just the model, defaults for everything else.
- `readOnly(model, root)` — `Read` / `Glob` / `Grep` / `LS` rooted at the workspace, gated by
  `Permission.planMode()`.
- `workspace(model, root)` — same read tools plus `MemoryWrite` plus `Permission
  .defaultInWorkspace()` (reads + memory allowed, writes / edit / execute asked).

Stream events for a live UI:

```java
session.events().subscribe(new Flow.Subscriber<QueryEvent>() {
  public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
  public void onNext(QueryEvent event) {
    if (event instanceof QueryEvent.AssistantText t) System.out.print(t.text());
  }
  public void onError(Throwable t) {}
  public void onComplete() {}
});
session.send("Refactor the loop module's package-info comments.");
```

Cost tracking, budget caps, custom hooks, custom permission policies, the long-poll HTTP +
SSE surface in `helios-runtime` — see [`session/README.md`](session/README.md) for the full
v2 quickstart.

## Resource Lifecycle

`Model` is `AutoCloseable` and holds long-lived resources (HTTP connection pool, file descriptors). It is designed to be **built once at app startup, shared across many Agents, and closed once at app shutdown**. `Agent` itself is per-request and stateless — closing an Agent would break other Agents that share the same Model, so `Agent` is intentionally not `AutoCloseable`. The creator of the `Model` owns its lifecycle.

```java
try (var model = new AnthropicModel(CLAUDE_SONNET_4_6, ModelConfig.of(apiKey))) {
  var config = AgentConfig.newBuilder().withModel(model).build();
  // build many Agents, run many requests
  for (var input : inputs) {
    new Agent(config).run(input);
  }
} // HttpClient and connection pool released here
```

`ReplSession` is also `AutoCloseable` — it owns its sandbox subprocess and must be closed by the caller.

## Tools

```java
var weatherTool = Tool.newBuilder()
    .withName("get_weather")
    .withDescription("Current weather for a city")
    .withParameter(ToolParameter.newBuilder()
        .withName("city").withType(ParameterType.STRING).withRequired(true).build())
    .withExecutor(args -> ToolResult.success("72°F in " + args.get("city")))
    .build();
```

`AgentConfig.withParallelToolExecution(true)` runs multiple tool calls concurrently on virtual threads; results are preserved in call order and each tool catches its own fault-tolerance exceptions so one timeout doesn't abort the others.

### CommandGrant — give the agent a CLI without giving it the secrets

`CommandGrant` produces a `Tool` that lets the model invoke a single CLI binary under tight controls. Secrets registered via `withEnv(...)` are auto-redacted from any model-visible output:

```java
var registry = new SecretRegistry();
var gh = CommandGrant.builder("gh")
    .withSecretRegistry(registry)
    .withEnv("GH_TOKEN", System.getenv("GH_TOKEN"))   // value never reaches the model
    .withTimeout(Duration.ofSeconds(30))
    .withMaxOutputBytes(50_000)
    .withArgValidator(args ->
        args.isEmpty() || !"auth".equals(args.get(0))
            ? Optional.empty()
            : Optional.of("'gh auth' is not allowed via this grant"))
    .build();

agent.tools().add(gh.toTool());
```

Hardening is on by default: binary path pinned at build, argv-only (no shell), `ProcessBuilder` env cleared then injected so the JVM's environment never leaks into the child, argv pre-scan refuses any registered secret value (forces env-only secret transport), per-call temp working directory, output capped, descendants killed on timeout, stderr hidden from the model unless `withStderrToModel(true)`. The same `SecretRegistry` can be shared across grants and any other tools that produce model-visible output, so cross-tool redaction is automatic. `InvocationResult.redactionCounts()` exposes per-secret hit counts for telemetry. See `core.common.SecretRegistry` and `core.common.Redactor` for the underlying byte-level Aho-Corasick scrubber.

### FilesystemKnowledge — curated corpus, no vector DB

Mount a directory and let the model search it natively. Three read-only tools: `kb_grep` (Java regex over lines), `kb_glob` (paths), `kb_read` (line-range reads). Pure JDK — no ripgrep dependency. Operator-curated bounded corpora; intended as an alternative to embedding+vector-DB recall for support knowledge bases, doc sets, and similar.

```java
var kb = FilesystemKnowledge.builder(Path.of("/var/kb/support"))
    .withSecretRegistry(registry)         // shared across CommandGrants too
    .withMaxFileSize(1_000_000)
    .withMaxBytesPerRead(50_000)
    .withMaxGrepResults(100)
    .withGrepTimeout(Duration.ofSeconds(10))
    .build();

agentConfig.tools().addAll(kb.tools());
```

Security hardening, all on by default: lexical path-jail (`..` and absolute paths refused), then `toRealPath` symlink check (refuses escapes via symlinks); symlinks encountered during traversal are skipped entirely; hidden directories pruned (`.git`, `.ssh`); binary files (NUL byte in first 8 KB) skipped by grep; every cap enforced (file size, read bytes, grep wall-clock, result counts). Output flows through the shared `SecretRegistry`, so a token a `CommandGrant("gh")` wrote to a file is still redacted when `kb_read` returns it.

## Structured Output

```java
record Sentiment(String label, double confidence) {}

Response<Sentiment> response = model.chat(messages, OutputSchema.of(Sentiment.class));
Sentiment s = response.parsed();
```

When the model's response doesn't conform to the schema, providers throw a
`StructuredOutputParseException` carrying a per-field diff (e.g. `field
'provenance[3].sources[0].title' is required but missing`). When the same
output schema is used through `agent.run(session, schema)`, the agent loop
converts that into a corrective USER turn carrying the diff and re-iterates
within `maxIterations` instead of failing — the model self-corrects on the
next attempt rather than re-rolling the dice. Disable the loop with
`AgentConfig.withStructuredOutputRetry(false)` for hard-fail semantics.

## Memory

Letta-inspired three-surface memory: `identity` (agent persona), `user_profile`
(stable facts about the user), `working_memory` (current task state). `Memory.block(name)`
returns `Optional<MemoryBlock>`. Two tools (`memory_update`, `memory_read`) let
agents self-edit during conversations; `maxSize` is enforced at tool-write time.

```java
var memory = InMemoryMemory.withDefaults(); // installs the three canonical blocks
memory.updateBlock(MemoryBlocks.USER_PROFILE, "name", "Alice");

var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model).withMemory(memory).build());
```

### Lifecycle hooks

`MemoryListener` receives sealed `MemoryEvent`s at five fire points — before
each API call, after each turn, before compaction, on session end, and on every
memory write. This is what behavior extractors and consolidators plug into.

```java
var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model)
    .withMemory(memory)
    .withMemoryListener(new TopicThreadingExtractor(memory))
    .withMemoryListener(myAuditMirror)
    .build());
```

### Context compaction

Session-level compaction lives in `helios-session`. The agent loop estimates context fill
through a pluggable `TokenCounter` (default char-based, ~4 chars/token) and reacts at two
watermarks:

- **0.85** of `SessionLimits.maxContextTokens()` → emits `QueryEvent.ContextWarning(usagePct)`
  once, so UIs and audit consumers can log or pause the session before overflow.
- **0.95** → invokes the configured `ContextCompactor`, swaps the returned history in, and
  emits `QueryEvent.ContextEdited(removedBlocks, tokensBefore, tokensAfter)`.

The default `DropMiddleToolResultsCompactor` preserves the system prompt + opening turn and the
most recent trajectory, replacing the middle with a one-call summary against the session's
model. Override head/tail policy and summary prompt via its Builder, swap the whole compactor
through `SessionOptions.Builder.withContextCompactor(...)`, opt out with
`ContextCompactor.disabled()`, or do ad-hoc rewrites by returning
`HookOutcome.mutate(Map.of("history", rewritten))` from a `PreModelTurnHook`. See
[`session/README.md`](session/README.md#context-compaction) for the full surface.

### Dreaming — offline memory consolidation

`MemoryConsolidator` reads recent history + current blocks and proposes
compressed updates. Schedule via `io.helidon.scheduling` between sessions or
fire on `SessionEnd`. Three apply modes — `AUTO_APPLY`, `SUGGEST_ONLY`
(default), `QUARANTINE` (parks suggestions for review).

```java
var consolidator = new LlmMemoryConsolidator(model);
var report = consolidator.consolidate(
    new ConsolidationContext(agentId, userId, memory,
        memory.history(userId, sessionId)));
report.apply(memory, MemoryConsolidator.ApplyMode.QUARANTINE);
```

### Personalization from behavior

Reference extractors in `core.memory.behavior`: `ToolAcceptanceExtractor`
(captures "don't use X" / "prefer X" patterns) and `TopicThreadingExtractor`
(tracks recurring keywords). Both are `MemoryListener` implementations that
write to `user_profile` via the same audit channel the model uses.

## Streaming

```java
var events = model.chatStream(messages, tools);
while (events.hasNext()) {
  switch (events.next()) {
    case StreamEvent.TextDelta d -> System.out.print(d.text());
    case StreamEvent.ToolCallComplete tc -> System.out.println("Called: " + tc.toolCall().name());
    case StreamEvent.Done d -> System.out.println("\n" + d.response().content());
    default -> {}
  }
}
```

The iterator is `Closeable` — cast and close to bail out early and release the underlying connection.

## Fault Tolerance

Composable retry, circuit breaker, and timeout, zero dependencies:

```java
var ft = FaultTolerance.newBuilder()
    .withRetry(RetryPolicy.newBuilder()
        .withMaxAttempts(3)
        .withBackoff(Backoff.exponential(Duration.ofMillis(500), 2.0))
        .build())
    .withCircuitBreaker(CircuitBreaker.newBuilder()
        .withFailureThreshold(5)
        .withHalfOpenAfter(Duration.ofSeconds(30))
        .build())
    .withOperationTimeout(Duration.ofMinutes(5))
    .build();

var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model).withFaultTolerance(ft).build());
```

`Tool` exposes an `idempotent` flag (defaults to `false`). The agent loop dispatches non-idempotent tools through `FaultTolerance.withoutRetry()` so a side-effecting tool never replays even when a retry policy is configured. Mark read-only tools idempotent at build time:

```java
Tool.newBuilder().withName("get_weather").withIdempotent(true)...
```

## Durable Runs

One-line opt-in to crash-safe execution. The `Durability` bundle wraps a `RunStore` + `ToolCallJournal` + `UnsafeResumePolicy` + idempotency overrides; pick a factory and pass it to `withDurability(...)`.

```java
// Tests / single-process:
var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model)
    .withMemory(InMemoryMemory.withDefaults())
    .withTool(weatherTool)                       // mark idempotent tools at the source
    .withDurability(Durability.inMemory())
    .build());

// Production (Postgres):
var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model)
    .withMemory(new PgMemory(pgConfig))
    .withTool(weatherTool)
    .withDurability(PgDurability.of(pgConfig))   // helios-persistence
    .build());
```

### Run, resume, and runOrResume

```java
var runId = Ids.newId();
agent.run(SessionContext.of(sessionId, "research X"), runId);
// ... JVM crashes ...
agent.resume(runId);                              // sessionId derived from the RunStore
```

For idempotent restart-on-failure scenarios:

```java
// Starts fresh if no run with this id exists; resumes it if in-progress; rejects if already terminal.
agent.runOrResume(runId, SessionContext.of(sessionId, "research X"));
```

If a non-idempotent tool was in-flight at the crash, `resume` returns `Result.failure(UnsafeResumeException)` by default and leaves the run `SUSPENDED` — the deployer decides whether the side effect actually occurred. Switch policies via `Durability.newBuilder().withUnsafeResumePolicy(AUTO_FAIL_AND_CONTINUE)` to synthesize a failure ToolResult and let the model self-correct.

### Custom durability config

```java
var durability = Durability.newBuilder()
    .withRunStore(new PgRunStore(pgConfig))
    .withToolCallJournal(new PgToolCallJournal(pgConfig))
    .withUnsafeResumePolicy(UnsafeResumePolicy.AUTO_FAIL_AND_CONTINUE)
    .withIdempotentToolOverride("send_email", true)  // deployer override
    .build();
```

### Durable workflows

The same `Durability` bundle drives `Workflow`. Each step boundary is checkpointed; on resume, the original input and all completed step outputs are reconstituted from the journal — callers don't re-supply anything.

```java
var workflow = Workflow.newBuilder("ingest")
    .withStep(Step.agent("classify", classifierAgent))
    .withStep(Step.agent("enrich", enrichmentAgent))
    .withDurability(Durability.inMemory())     // or PgDurability.of(pgConfig)
    .build();

var runId = Ids.newId();
workflow.run("raw-data", runId);
// ... JVM crashes after 'classify' completes ...
workflow.resume(runId);                          // continues from 'enrich'
```

### Scheduled auto-resume

`DurableResumeScanner` periodically sweeps the `RunStore` for stale runs and resumes them. Wire it into `io.helidon.scheduling` (or any scheduler):

```java
var scanner = DurableResumeScanner.builder(durability)
    .registerAgent("research-bot", researchAgent::resume)
    .registerWorkflow("ingest", ingestWorkflow::resume)
    .withStaleAfter(Duration.ofMinutes(5))         // skip recently-checkpointed runs
    .withMaxConcurrent(4)
    .build();

scheduler.scheduleAtFixedRate(scanner::scan, 0, 1, TimeUnit.MINUTES);
```

### Retention

```java
durability.runStore().purgeOlderThan(Duration.ofDays(30));   // cascade-deletes journal entries
```

Distributed multi-JVM workers are out of scope today; the schema is shaped so v2 lease columns can be added additively.

## Tracing

```java
var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model)
    .withTraceListener(traceStore)              // fires once at trace close
    .withSpanListener(liveDashboard)            // fires per-span as work happens
    .build());
```

`TraceListener.onTrace(Trace)` fires once when the whole trace closes — best for persistence and post-run analysis. `SpanListener` is the live counterpart: `onSpanStart(SpanStart)` and `onSpanEnd(Span)` fire as each span opens and closes, so a live UI or kill-switch can react mid-trajectory. In nested mode (worker inside a `Team` leader), the worker's span listeners are silenced — the leader's listener observes everything.

`model.chat` spans record token usage, finish reason, and grounding citations (deduplicated, `www.`-stripped domains) when the model returns any. Attach the same listener to a `Workflow` for end-to-end traces across multi-step pipelines.

## Multi-Agent Delegation

Teams let a leader agent orchestrate workers exposed as tools. The leader sees workers as regular tools with a `task` parameter — no custom orchestration code:

```java
var team = Team.newBuilder()
    .withName("content-team")
    .withModel(model)
    .withSystemPrompt("Delegate to the right specialist.")
    .withWorker("researcher", "Find and synthesize information", researcher)
    .withWorker("writer", "Write polished content from research notes", writer)
    .withParallelToolExecution(true)   // concurrent dispatch
    .withMinIterations(3)              // force research depth
    .withRequiredTools("researcher")   // must consult at least once
    .build();

Result<Response> result = team.run("Write a post about Java virtual threads");
```

`Agent.asTool(name, desc, config)` wraps any agent as a standalone tool — a fresh agent is instantiated per call, so it's thread-safe.

**Guardrails.** `withMinIterations(n)` and `withRequiredTools(...)` inject `USER` guidance messages (tagged `helios.injected`) when the model tries to stop early. `withIterationHook(ctx -> ...)` gives programmatic control — the hook returns `IterationAction.allow() / stop() / inject(msg)`. `maxIterations` is always the ceiling.

### Team vs RLM — choosing the right paradigm

`Team` and `RlmHarness` (see [Sandboxed Code Execution](#sandboxed-code-execution)) are both first-class orchestration primitives. They solve different problem shapes; pick by the data flow:

| Use **Team** when... | Use **RLM** when... |
|---|---|
| Workers do bounded sub-tasks and the leader synthesizes their findings | The model needs many sequential decisions over a large or growing intermediate state |
| Worker outputs are small (\< 2 KB) and benefit from re-entering the leader's context | Intermediate results are large; printing them into transcript would cause context blowup |
| You want parallel fan-out (multiple workers concurrently) | Work is naturally serial — each step depends on the previous |
| 2–10 workers, 1–3 iterations of leader synthesis | Tens to hundreds of `predict()` calls slicing context held in REPL variables |

**Multi-stage RLM is just chained calls.** No framework abstraction needed — call `rlm.run(input)` then feed its output to a second harness. The composition lives in your code.

A common smell: a Team worker that returns a 50KB string blows up the leader's context. That worker should be an `RlmHarness` invoked directly, not a Team worker.

## Workflows

Composable orchestration primitives for multi-step pipelines:

| Step | Description |
|------|-------------|
| `Step.agent(name, agent)` | Runs an Agent |
| `Step.function(name, fn)` | Runs an arbitrary function |
| `Step.sequential(name, steps...)` | In order, fail-fast |
| `Step.parallel(name, steps...)` | Concurrent on virtual threads |
| `Step.condition(name, predicate, then, else)` | If/else |
| `Step.loop(name, predicate, body, max)` | While-loop with guard |
| `Step.fallback(name, steps...)` | Try alternatives until one succeeds |

```java
var workflow = Workflow.newBuilder("support")
    .withStep(Step.agent("classify", classifier))
    .withStep(Step.condition("route",
        ctx -> ctx.lastResult().content().contains("urgent"),
        Step.agent("urgent", responder),
        Step.agent("standard", responder)))
    .build();
```

## Sandboxed Code Execution

The `helios-repl` module runs Java code in a JVM subprocess sandbox, brokering access to the host via a small set of host functions. This enables the **RLM (Recursive Language Model) pattern**: code owns loops, math, and aggregation; the LLM owns judgment via `predict()` calls with fresh context.

### One-line entrypoint: `RlmHarness`

For most RLM tasks you don't need to assemble the substrate by hand. `RlmHarness` bundles `ReplSession`, `CodeExecutionTool`, `PredictFunction`, typed `submit`, the canonical system prompt template, and the `ExtractFallback` recovery path:

```java
record Input(String query, List<String> documents) {}
record Output(String answer, List<String> sources, int totalCount) {}

var rlm = RlmHarness.builder(Input.class, Output.class)
    .model(rootModel)
    .subModel(subModel)               // backs predict(); defaults to root model
    .sandboxFactory(JvmSandbox.factory())
    .strategy("Answer the query using the provided documents. Cite sources.")
    .maxIterations(30)                // outer agent budget
    .maxLlmCalls(50)                  // cumulative predict() budget per session
    .build();

RlmResult<Output> result = rlm.run(new Input("what is helios?", docs));
switch (result.status()) {
  case SUBMITTED -> /* model called submit() cleanly */;
  case EXTRACTED -> /* loop hit max iterations; output reconstituted from trajectory */;
  case FAILED    -> /* see result.error() */;
}
```

The harness is a thin assembly over the primitives below — drop down any time it's too narrow.

### Narrower entrypoint: `CodeActHarness` (REPL without sub-LM)

Some tasks want the REPL but not the sub-LM recursion — the model writes Java in the sandbox to inspect the input, then returns its structured answer directly. SRLM Table 1 attributes the bulk of accuracy gains to REPL-over-externalized-context (~34 pts) rather than the sub-LM recursion (~6 pts); when you don't need `predict()`, `CodeActHarness` ships only the REPL teaching:

```java
record ColumnMappingInput(ColumnDescriptor column, CdiscIndex cdiscIndex) {}
record ColumnMappingOutput(String targetDomain, String targetVariable, double confidence) {}

var harness = CodeActHarness.builder(ColumnMappingInput.class, ColumnMappingOutput.class)
    .model(opus47)
    .sandboxFactory(JvmSandbox.factory())
    .strategy("Inspect the column metadata. Search the CDISC index. Return the mapping.")
    .build();

CodeActResult<ColumnMappingOutput> result = harness.run(input);
switch (result.status()) {
  case SUCCEEDED -> /* result.output().orElseThrow() is the parsed answer */;
  case FAILED    -> /* see result.error().orElseThrow() */;
}
```

Differences vs `RlmHarness`: no `predict()`, no `submit()`, no extract-fallback, no LLM-call budget. The model returns its structured answer as the final assistant message; the Agent's `OutputSchema` path parses and validates it. Both harnesses share the substrate (`ReplSession`, `CodeExecutionTool`, `InputBindings`, prompt rendering) via composition — `ReplConfig.withAutoRegisterSubmit(false)` is the new seam.

### Building blocks

```java
var session = ReplSession.create(
    ReplConfig.newBuilder()
        .withSandboxFactory(JvmSandbox.factory())
        .withHostFunction(PredictFunction.create(model))
        .withHostFunction(QueryFunction.create(dataSource))
        .withHostFunction(FetchFunction.create(httpClient, Set.of("api.example.com")))
        .withSubmitSchema(OutputSchema.of(Output.class))   // typed submit + validation
        .withMaxOutputCharsToModel(5000)                   // truncate stdout shown to model
        .withMaxLlmCalls(50)                               // predict() budget
        .build(),
    new Semaphore(50));

var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model)
    .withTool(CodeExecutionTool.create(session))
    .build());
```

Sandbox API — four host bridge functions:

| Function | Purpose | Security |
|----------|---------|----------|
| `predict(instructions, input)` | Call model with fresh context | Host controls which model; per-session call budget |
| `submit(output)` | Return structured final result | Single-call enforced; validates against `OutputSchema` if configured |
| `query(sql, ...params)` | Read-only database query | Host holds credentials; SELECT/WITH/EXPLAIN only |
| `fetch(url)` | HTTP GET via host | HTTPS-only, domain allowlist, no redirects, IP literals rejected, response size-capped |

Credentials never enter the sandbox. Each `predict()` gets a fresh context (system + user only) — no context rot across iterations. Variables persist across `execute_code` calls; printed output is truncated when shown to the model so long predict results stay in sandbox variables instead of bloating the transcript.

### Input bindings — record fields as JShell variables

When `RlmHarness` runs with a record input, every top-level field is pre-bound as a typed JShell `var` before the model writes any code. Given `record Stats(List<Integer> numbers, String operation)` and `rlm.run(new Stats(List.of(1, 5, 3), "sum"))`, the model can write `numbers.size()` or `operation.equals("sum")` directly — no JSON parsing, no Map navigation. Fields whose types live in the `java.*` package hierarchy bind with full static typing; complex (user-defined) field types fall back to `Object` so the model can still navigate them via the underlying `Map`. The user's input class never needs to be JShell-accessible (no public/top-level requirement) — the harness reads the JSON into a `Map<String, Object>` and casts each field independently using the declared generic type rendered as Java source.

### Scripting prelude — script-grade Java in JShell

Every sandbox boots with a JShell preamble that adds standard imports (`java.util.*`, `java.util.stream.*`, `Collectors`, `java.io.*`, `java.math.*`, `java.time.*`), free `print`/`println`/`printf` (no `System.out`), and a curated set of helpers for the operations the model writes most often:

```java
sum(numbers)             // Collection<? extends Number> -> double
sumInts(numbers)         // -> long, when you want exact integer arithmetic
mean(xs), max(xs), min(xs)
join(", ", strings)
filter(xs, x -> x > 0)   // typed List
map(xs, x -> x * 2)
sorted(xs)               // ascending; xs must be Comparable
countBy(xs, keyFn)       // Map<K, Long>
```

So `println(sum(numbers))` replaces `System.out.println(numbers.stream().mapToInt(Integer::intValue).sum())`. The full preamble is `SandboxPrelude.SNIPPET`; it's installed by `JvmSandboxBootstrap` so direct `CodeExecutionTool` users and the harness both get the same surface.

### Skills — composable capability bundles

```java
var pdfSkill = new Skill("pdf",
    "Use parsePdf(bytes) to extract text from PDF byte arrays.",
    List.of(parsePdfFunction));

var rlm = RlmHarness.builder(Input.class, Output.class)
    .model(model)
    .sandboxFactory(JvmSandbox.factory())
    .skill(pdfSkill)
    .build();
```

`Skill.merge(List)` raises if two skills register the same tool name, so accidental shadowing surfaces at composition time.

## Evaluation & Autoresearch

Helios ships domain-agnostic primitives for batch evaluation and iterative optimization in `ai.singlr.core.eval`.

| Primitive | Purpose |
|---|---|
| `Metric<E, A>` | `score(expected, actual, trace) → double` — functional interface; expected and actual types are independent so criteria-shape scoring (expected = descriptor, actual = produced output) doesn't need to fake a single type |
| `Example<I, O>` | Labeled input/expected pair |
| `Evaluator` | Run an `AgentConfig` over a `List<Example>` on virtual threads, collect traces + scores |
| `Objective<C>` | Score a candidate from a user-defined search space |
| `Checkpoint<C>` | `snapshot()` / `restore(snapshot)` — keep/discard mechanics |
| `ExperimentLog` | Append-only JSONL log (`InMemoryExperimentLog`, `FileExperimentLog`) |
| `ConfidenceScorer` | MAD-based noise-floor score; returns `null` before 3 entries |
| `FeedbackMetric<E, A>` | Sibling to `Metric` returning `{score, feedback}` — feedback flows downstream to `ReflectiveMutator`. `.asScalar()` adapts to `Metric` when only a number is needed |
| `ParetoFrontier<C>` | Tracks candidates by per-instance scores, maintains the Pareto-non-dominated set, supports coverage-weighted sampling. Thread-safe. NaN scores rejected. `snapshot()` / `restore()` for durability |
| `ReflectiveMutator<C>` | Functional interface: `propose(parent, traces) → new candidate`. `LlmReflectiveMutator` is the reference impl for `C = String` prompts (with schema-constrained retry on malformed responses) |

```java
var evaluator = Evaluator.<String, String>newBuilder()
    .withAgentConfig(config)
    .withDataset(examples)
    .withMetric(Metric.exactMatch())
    .withParallelism(8)
    .build();

EvalResult<String, String> result = evaluator.run();
```

**Autoresearch** — the pattern of "LLM proposes a candidate, objective scores it, keep improvements, discard regressions, persist the decision" — is the minimum set of primitives above plus an agent + a few user-written tools. The framework does not ship the loop as a class; it ships the pieces, and two reference modules show how to compose them:

- **`examples/autoresearch-prompt`** — optimize a system prompt against a labeled dataset. `Checkpoint<String>` = `InMemoryCheckpoint`, `Objective<String>` = `Evaluator` over the dataset.
- **`examples/autoresearch-code`** — pi-autoresearch-style source-code optimization with a git-backed `Checkpoint<String>`, a shell benchmark `Objective`, and five coach tools (`read_file`, `write_file`, `run_experiment`, `log_experiment`, `show_log`).

Both examples sit on the same five primitives — proof that the abstraction is domain-agnostic. Neither module is published; they're reference implementations.

### GEPA-style reflective optimization

For optimizers that should keep a *set* of complementary candidates rather than collapse to the global aggregate winner — the GEPA pattern — Helios ships `ParetoFrontier`, `ReflectiveMutator`, and `FeedbackMetric` as composition primitives. A typical loop:

```java
var frontier = new ParetoFrontier<String>(validationSet.size());
var mutator = LlmReflectiveMutator.builder(reflectionModel)
    .traceSampler(TraceSampler.failuresFirst(1.0, 2, rng))
    .build();
var evaluator = Evaluator.<I, O>newBuilder()
    .withAgentConfig(baseConfig.withSystemPrompt(seedPrompt))
    .withDataset(validationSet)
    .withFeedbackMetric(myFeedbackMetric)   // returns {score, feedback}
    .build();

var seedEval = evaluator.run();
frontier.add(seedPrompt, perInstance(seedEval));

for (var i = 0; i < budget; i++) {
  var parent = frontier.sampleByCoverage(rng);
  var parentEval = ...; // cached or re-run
  var candidate = mutator.propose(parent, parentEval.feedback());
  var childEval = evaluator.evaluate(candidate);
  frontier.add(candidate, perInstance(childEval));
  // Optionally emit OptimizerCandidateProposed / OptimizerCandidateScored
  // events through your EventSink — primitives stay pure, composition emits.
}

var best = frontier.bestSingle().orElseThrow();
```

The framework does not bundle the driver loop. The pieces above are all you need; the worked composition lives in `examples/gepa-prompt` (planned).

## Embeddings

Local vector embeddings via ONNX Runtime. Models download from HuggingFace on first use and are cached locally.

```java
try (var model = EmbeddingProvider.resolve(
    OnnxModelId.NOMIC_EMBED_V1_5.id(), EmbeddingConfig.defaults())) {
  float[] vector = model.embed("A man is eating food.").getOrThrow();
}
```

Supported: `NOMIC_EMBED_V1_5` (768-dim encoder, 8192 tokens), `EMBEDDING_GEMMA_300M` (768-dim decoder, 2048 tokens), `HARRIER_OSS_V1_270M` (640-dim multilingual decoder, 32768 tokens), `HARRIER_OSS_V1_0_6B` (1024-dim multilingual decoder, 32768 tokens).

Each model ships with a sensible default query/document prefix (Gemma's `task: search result | query: `, Harrier's `Instruct: Given a web search query, …\nQuery: `, Nomic's empty). Pass a custom prefix per call when a different task shape needs different instructions:

```java
model.embedQuery(query, "Instruct: Given a brief professional summary, find the matching profile\nQuery: ");
model.embedDocument(doc, null);  // null = use the spec default
```

## Persistence

PostgreSQL-backed `Memory`, `PromptRegistry`, and `TraceListener`. All three share a `PgConfig` carrying the `DbClient`, schema name, and optional agent ID.

```java
var pgConfig = PgConfig.newBuilder()
    .withDbClient(dbClient)
    .withSchema("lg")
    .withAgentId("my-agent")
    .build();

var agent = new Agent(AgentConfig.newBuilder()
    .withModel(model)
    .withMemory(new PgMemory(pgConfig))
    .withTraceListener(new PgTraceStore(pgConfig))
    .build());
```

Schema lives on the classpath at `ai/singlr/persistence/schema.sql` — run it against your database to create the `helios_*` tables. Optional custom schema prefix is applied to all generated SQL.

## Design Principles

- **No magic** — explicit wiring, no annotation-driven DI.
- **Records everywhere** — immutable data, pattern matching, sealed types (`Result<T>`, `Step`, `StreamEvent`).
- **Builder pattern** — `with` prefix, static `newBuilder()` factory.
- **JPMS modules** — proper encapsulation, ServiceLoader SPI for providers.
- **Production from day 1** — fault tolerance, tracing, and ~2300 tests across modules with 95%+ instruction coverage on shipped code.

## Building

```bash
mvn package
mvn spotless:apply   # auto-format (Google Java Format, 2-space indent)
```

## License

[MIT](LICENSE)
