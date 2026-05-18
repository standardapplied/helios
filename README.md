# Helios

The Singular agentic framework for the JVM. A provider-agnostic `Model` interface, a streamable steerable session SDK, sandboxed code execution, structured output, durability primitives, and end-to-end tracing — for tool-using agents that run reliably in production.

Simple, explicit, no magic. No annotation-driven DI, no reflective surprises.

Published to [Maven Central](https://central.sonatype.com/namespace/ai.singlr) under the `ai.singlr` namespace. MIT licensed.

## Requirements

- Java 25+
- Maven 3.9+

## Modules

Pick what you need — each jar is published independently:

| Artifact | What it gives you | External deps |
|----------|-------------------|---------------|
| `helios-core` | `Model` interface + value types, `Tool` / `CommandGrant` / `FilesystemKnowledge`, `OutputSchema` + `Provenanced<T>`, `TokenCounter`, `FaultTolerance`, `TraceListener` / `SpanListener`, durability primitives (`RunStore`, `ToolCallJournal`), `SecretRegistry` + `Redactor`, `CostEstimate` / `CostCalculator` | None |
| `helios-session` | v2 SDK — long-lived `AgentSession` running an agent loop on a virtual thread. `SessionPresets`, hooks, declarative `Permission`, file tools, `MemoryBackend`, `ExecutionProvider`, `ContextCompactor` | Jackson 3.x |
| `helios-runtime` | Helidon HTTP/SSE surface for `helios-session` — `POST /sessions`, SSE `/events`, long-poll `/result` | Helidon SE, Jackson 3.x |
| `helios-gemini` | Google Gemini provider (Interactions API) | Jackson 3.x |
| `helios-anthropic` | Anthropic Claude provider (Messages API) | Jackson 3.x |
| `helios-openai` | OpenAI GPT provider (Responses API) | Jackson 3.x |
| `helios-repl` | Sandboxed JShell substrate (`JvmSandbox`, `ReplSession`, `CodeExecutionTool`) + `CodeActPreset` for session-level RLM/CodeAct shapes | Jackson 3.x |
| `helios-onnx` | Local embeddings via ONNX Runtime | ONNX Runtime, DJL Tokenizers |
| `helios-persistence` | PostgreSQL-backed `PromptRegistry`, `TraceStore`, and durability (`PgRunStore` + `PgToolCallJournal`) | Helidon DbClient |

Most agentic apps want `helios-session` + one provider. Drop down to `helios-core`'s `Model` directly for one-shot calls; expose a session over HTTP with `helios-runtime`.

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

## Quick Start

Three ways in, depending on what you're building.

### 1) One-shot model call

Direct `Model` API for the "send messages, get a response" shape. No session, no loop, no tools — just the model.

```java
try (var model = new AnthropicProvider().create(
    AnthropicModelId.CLAUDE_SONNET_4_6.id(),
    ModelConfig.newBuilder().withApiKey(System.getenv("ANTHROPIC_API_KEY")).build())) {
  var response = model.chat(List.of(
      Message.system("You are a helpful assistant."),
      Message.user("What is the capital of France?")));
  System.out.println(response.content());
}
```

All providers implement the same `Model` interface — swap providers without touching the rest of your code.

### 2) Streamable session

`AgentSession` is a long-lived object that runs an agent loop on a virtual thread. `send` messages, `subscribe` to events, `interrupt` mid-turn, `runBlocking` for synchronous results. This is the shape that shows up in production: multi-turn tool use, mid-run user steering, permission gates, observability.

```java
try (var session = AgentSession.create(
    SessionPresets.workspace(model, Path.of("/path/to/repo")).build())) {
  var terminal = session.runBlocking(UserMessage.text(
      "Summarise the public API of the session module."));
  System.out.println(((ResultMessage.Success) terminal).result());
}
```

See [Sessions](#sessions-helios-session) below.

### 3) HTTP surface

`helios-runtime` exposes a session over REST + SSE so non-JVM clients can drive it. `POST /sessions`, `POST /sessions/{id}/messages`, SSE `GET /sessions/{id}/events`, long-poll `GET /sessions/{id}/result?timeout=<s>`, `DELETE /sessions/{id}`. See `runtime/` for routes.

## Sessions (`helios-session`)

`AgentSession.create(SessionOptions)` returns a session that drives an agent loop on a virtual thread the first time you `send`. Key surfaces:

- `send(UserMessage)` — queue a message (steering queue, drained per-iteration).
- `events()` — `Flow.Publisher<QueryEvent>` for streaming subscribers (15 event subtypes: text, thinking, tool use, hook fired, context warning/edited, etc.).
- `result()` — `CompletableFuture<ResultMessage>` for blocking await.
- `runBlocking(msg)` and `runBlocking(msg, OutputSchema)` — synchronous convenience for one-shot use.
- `interrupt(reason)` — queue a synthetic mid-run user message.
- `answer(qid, response)` — resolve a pending `AskUserQuestion`.
- `close()` — `AutoCloseable`; cancels the loop, settles the result future, drains the publisher.

`SessionPresets` ships three curated `SessionOptions.Builder` factories:

- `minimal(model)` — model only, defaults for everything else.
- `readOnly(model, root)` — `Read` / `Glob` / `Grep` / `LS` rooted at the workspace, gated by `Permission.planMode()`.
- `workspace(model, root)` — same read tools plus `MemoryWrite` plus `Permission.defaultInWorkspace()` (reads + memory allowed, writes / edit / execute asked).

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
session.send(UserMessage.text("Refactor the loop module's package-info comments."));
```

Hooks, declarative permissions, cost tracking, budget caps, session limits, context compaction, the HTTP + SSE surface — see [`session/README.md`](session/README.md) for the full quickstart.

### Context compaction

The agent loop estimates context fill through a pluggable `TokenCounter` (default char-based, ~4 chars/token) and reacts at two watermarks:

- **0.85** of `SessionLimits.maxContextTokens()` → emits `QueryEvent.ContextWarning(usagePct)` once.
- **0.95** → invokes the configured `ContextCompactor`, swaps the returned history in, and emits `QueryEvent.ContextEdited(removedBlocks, tokensBefore, tokensAfter)`.

The default `DropMiddleToolResultsCompactor` preserves the system prompt + opening turn and the most recent trajectory, summarising the middle via one model call. It walks slice boundaries to keep `tool_call`/`tool_result` pairs together (providers reject histories where these split). The summary call runs on a virtual thread under a configurable timeout (default 60s) and reports its `Usage` so spend gates through `SessionLimits.maxBudgetMicroUsd`.

Override head/tail policy via the Builder, swap the whole compactor through `SessionOptions.Builder.withContextCompactor(...)`, opt out with `ContextCompactor.disabled()`, or do ad-hoc rewrites by returning `HookOutcome.mutate(Map.of("history", rewritten))` from a `PreModelTurnHook`. See [`session/README.md`](session/README.md#context-compaction) for the full surface.

## Resource Lifecycle

`Model` is `AutoCloseable` and holds long-lived resources (HTTP connection pool, file descriptors). **Build it once at app startup, share across many sessions, close once at app shutdown.** The component that constructs a `Model` owns its lifecycle — a session does NOT close its `Model` because closing one model would break sibling sessions sharing it.

```java
try (var model = new AnthropicProvider().create(modelId, config)) {
  for (var request : requests) {
    try (var session = AgentSession.create(SessionPresets.workspace(model, repo).build())) {
      session.runBlocking(UserMessage.text(request));
    }
  }
} // HttpClient and connection pool released here
```

`AgentSession` is `AutoCloseable` and owns its loop, per-session publisher executor, and any pending tool/question state. Always use try-with-resources; `close()` cancels the loop, settles the result future, and waits up to 5s for the publisher executor to drain.

`ReplSession` is `AutoCloseable` and owns its sandbox subprocess. `JvmSandbox` also installs a JVM shutdown hook so a leaked sandbox is force-killed on host JVM exit.

## Tools

```java
var weatherTool = Tool.newBuilder()
    .withName("get_weather")
    .withDescription("Current weather for a city")
    .withParameter(ToolParameter.newBuilder()
        .withName("city").withType(ParameterType.STRING).withRequired(true).build())
    .withExecutor((args, ctx) -> ToolResult.success("72°F in " + args.get("city")))
    .build();
```

Mark read-only tools as idempotent at build time — `Tool.newBuilder().withIdempotent(true)`. The session loop dispatches non-idempotent tools through a no-retry fault-tolerance envelope so side-effecting calls never replay.

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
```

Hardening is on by default: binary path pinned at build, argv-only (no shell), `ProcessBuilder` env cleared then injected so the JVM's environment never leaks into the child, argv pre-scan refuses any registered secret value (forces env-only secret transport), per-call temp working directory, output capped, descendants killed on timeout, stderr hidden from the model unless `withStderrToModel(true)`. The same `SecretRegistry` can be shared across grants and any tool that produces model-visible output — cross-tool redaction is automatic.

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
```

Security hardening, all on by default: lexical path-jail (`..` and absolute paths refused) plus `toRealPath` symlink check (refuses escapes via symlinks); symlinks encountered during traversal are skipped; hidden directories pruned (`.git`, `.ssh`); binary files (NUL byte in first 8 KB) skipped by grep; every cap enforced. Output flows through the shared `SecretRegistry`, so a token a `CommandGrant("gh")` wrote to a file is still redacted when `kb_read` returns it.

## Structured Output

`OutputSchema.of(MyRecord.class)` generates a JSON Schema from a Java record. The model returns a typed value, validated against the schema; shape mismatches surface as `StructuredOutputParseException` with a per-field diff.

```java
record Sentiment(String label, double confidence) {}

var response = model.chat(messages, OutputSchema.of(Sentiment.class));
Sentiment s = response.parsed();
```

For provenance-tagged output (Basis-style — every field carries source citations + confidence), wrap with `OutputSchema.provenancedOf(MyOutput.class)`:

```java
var schema = OutputSchema.provenancedOf(MappingProposal.class);
var response = model.chat(messages, schema);
Provenanced<MappingProposal> result = response.parsed();
// result.output() = the typed output; result.provenance() = per-field sources + confidence
```

`ProvenanceValidator.DEFAULT` rejects `MEDIUM`/`HIGH` confidence entries that have no sources — the calibration mechanism that prevents the model from rubber-stamping HIGH on every field. Custom validators via `OutputSchema.provenancedOf(MyOutput.class, validator)`.

Through `session.runBlocking(message, schema)`, the loop intercepts `StructuredOutputParseException`, injects a corrective USER turn carrying the diff, and re-iterates — same self-correction shape as the v1 RLM `submit()` had.

## Streaming

The session's `events()` publisher is the primary streaming surface — see [Sessions](#sessions-helios-session). For direct model use without a session:

```java
try (var events = model.chatStream(messages, tools)) {
  while (events.hasNext()) {
    switch (events.next()) {
      case StreamEvent.TextDelta d -> System.out.print(d.text());
      case StreamEvent.ToolCallComplete tc -> System.out.println("Called: " + tc.toolCall().name());
      case StreamEvent.Done d -> System.out.println("\n" + d.response().content());
      default -> {}
    }
  }
}
```

The iterator is `Closeable` — use try-with-resources to release the underlying connection promptly. For the session loop's `Flow.Publisher<ModelChunk>` overload (with cancellation), see `Model.chatStream(messages, tools, cancellation)`.

## Fault Tolerance

Composable retry, circuit breaker, and timeout — zero dependencies:

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

var result = ft.execute(() -> model.chat(messages));   // checked exceptions: see Javadoc
```

`FaultTolerance.withoutRetry()` returns a sibling envelope with retry stripped (circuit breaker + timeout retained) — the session loop's tool dispatch uses this for non-idempotent tools so side-effecting calls never replay.

## Observability

The session's `events()` publisher is the primary observability surface. Subscribe a `Flow.Subscriber<QueryEvent>` (or a per-phase `OnStreamEventHook` for in-loop interception) and pick the events you care about — see the 15-subtype sealed hierarchy in `ai.singlr.session.QueryEvent` (`AssistantText`, `ToolUse`, `ToolResult`, `ContextWarning`, `ContextEdited`, `HookFired`, `TurnEnded`, `LoopEnded`, etc.).

```java
session.events().subscribe(new Flow.Subscriber<QueryEvent>() {
  public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
  public void onNext(QueryEvent ev) {
    switch (ev) {
      case QueryEvent.ToolUse t -> log.info("tool: {} args={}", t.call().name(), t.call().arguments());
      case QueryEvent.ContextWarning w -> metrics.recordContextFill(w.usagePct());
      case QueryEvent.LoopEnded e -> auditSink.flush(e.result());
      default -> { /* skip */ }
    }
  }
  public void onError(Throwable t) {}
  public void onComplete() {}
});
```

For HTTP clients, `helios-runtime` exposes the same events as Server-Sent Events under `GET /sessions/{id}/events`.

`helios-core` also ships generic event-sink primitives (`EventSink`, `JsonlEventSink`, `CollectingEventSink` in `ai.singlr.core.events`) plus a `Trace` / `Span` value-type hierarchy in `ai.singlr.core.trace` for custom collectors. These are currently inert for `AgentSession` runs — wire them via your own hook or stream subscriber.

## Sandboxed Code Execution (`helios-repl`)

The `helios-repl` module runs Java code in a JVM subprocess sandbox brokering access to the host via a small set of host functions. The substrate is `JvmSandbox` + `ReplSession` + `CodeExecutionTool` + the `HostFunction` registry. `CodeActPreset` is the v2 way to wire that substrate into an `AgentSession` for tool-using sessions where the agent needs general computation:

```java
record Input(String query, List<String> documents) {}
record Output(String answer, List<String> sources, int totalCount) {}

try (var executionProvider = JShellExecutionProvider.singleSandbox(ReplConfig.newBuilder().build(), null);
     var session = AgentSession.create(
         SessionOptions.newBuilder()
             .withModel(model)
             .withExecutionProvider(executionProvider)
             .apply(CodeActPreset.typed(Input.class, Output.class,
                 new Input("what is helios?", docs)))
             .build())) {
  Output answer = session.runBlocking(
      UserMessage.text("Answer the query."),
      OutputSchema.of(Output.class));
}
```

`CodeActPreset.withSubLm(I, O, input, subModel)` adds in-sandbox `predict()` / `submit()` host functions for RLM-style fan-out — code owns loops and aggregation, the sub-LM owns judgment with fresh context per call.

Sandbox API — the host functions you register:

| Function | Purpose | Security |
|----------|---------|----------|
| Custom host functions you register | Whatever your app needs | Argv-validated, registered before sandbox boot, frozen at startup |
| `predict(instructions, input)` | Call model with fresh context (via `CodeActPreset.withSubLm`) | Host controls which model; per-session call budget |
| `submit(output)` | Return structured final result (via `CodeActPreset.typed`) | Single-call enforced; validates against `OutputSchema` |

Credentials never enter the sandbox. Variables persist across `execute_code` calls; printed output is truncated when shown to the model (default 5000 chars) so long results stay in sandbox variables instead of bloating the transcript.

### Input bindings

When a `CodeActPreset.typed` session runs with a record input, every top-level field is pre-bound as a typed JShell `var` before the model writes any code. Given `record Stats(List<Integer> numbers, String operation)`, the model can write `numbers.size()` or `operation.equals("sum")` directly — no JSON parsing.

### Scripting prelude

Every sandbox boots with a JShell preamble that adds standard imports (`java.util.*`, `java.util.stream.*`, `Collectors`, `java.io.*`, `java.math.*`, `java.time.*`), free `print` / `println` / `printf` (no `System.out`), and ten script-style helpers: `sum`, `sumInts`, `mean`, `max`, `min`, `join`, `filter`, `map`, `sorted`, `countBy`. So `println(sum(numbers))` replaces `System.out.println(numbers.stream().mapToInt(Integer::intValue).sum())`.

Custom host functions you register get typed static JShell wrappers synthesised into the prelude — `HostFunction("marketQuote", [HostParameter.required("ticker", STRING, ...)], handler)` becomes callable as `marketQuote("AAPL")` from emitted Java code.

## Embeddings (`helios-onnx`)

Local vector embeddings via ONNX Runtime. Models download from HuggingFace on first use and are cached locally.

```java
try (var model = EmbeddingProvider.resolve(
    OnnxModelId.NOMIC_EMBED_V1_5.id(), EmbeddingConfig.defaults())) {
  float[] vector = model.embed("A man is eating food.").getOrThrow();
}
```

Supported: `NOMIC_EMBED_V1_5` (768-dim encoder, 8192 tokens), `EMBEDDING_GEMMA_300M` (768-dim decoder, 2048 tokens), `HARRIER_OSS_V1_270M` (640-dim multilingual decoder, 32768 tokens), `HARRIER_OSS_V1_0_6B` (1024-dim multilingual decoder, 32768 tokens).

Each model ships with sensible default query/document prefixes; pass a custom prefix per call when a different task shape needs different instructions:

```java
model.embedQuery(query, "Instruct: Given a brief professional summary, find the matching profile\nQuery: ");
model.embedDocument(doc, null);  // null = use the spec default
```

## Durable Runs

`core.runtime` ships the durability primitives — `RunStore`, `ToolCallJournal`, `UnsafeResumePolicy`, `Durability` — that crash-safe execution composes on. Production-grade impls in `helios-persistence` (`PgRunStore` + `PgToolCallJournal` via Helidon DbClient); in-process impls (`InMemoryRunStore`, `InMemoryToolCallJournal`) for tests.

Operations:

```java
durability.runStore().purgeOlderThan(Duration.ofDays(30));   // cascade-deletes journal entries

DurableResumeScanner.builder(durability)
    .register(...)
    .build()
    .scan();  // sweep stale runs and resume; wire into io.helidon.scheduling
```

**Session integration is on the v2 roadmap.** The primitives are stable and the schema is shaped so v2 distributed-lease columns (`worker_id`, `lease_until`) can be added additively. The first durable `AgentSession` example will land alongside the wiring.

## Persistence (`helios-persistence`)

PostgreSQL-backed `PromptRegistry`, `TraceStore`, and durability impls. All share a `PgConfig` carrying the `DbClient`, schema name, and optional agent ID.

```java
var pgConfig = PgConfig.newBuilder()
    .withDbClient(dbClient)
    .withSchema("helios")
    .build();

var traceStore = new PgTraceStore(pgConfig);
var promptRegistry = new PgPromptRegistry(pgConfig);
var durability = PgDurability.of(pgConfig);   // RunStore + ToolCallJournal
```

Schema lives on the classpath at `ai/singlr/persistence/schema.sql` — run it against your database to create the `helios_*` tables. Optional custom schema prefix is applied to all generated SQL.

## Secret Redaction

`core.common.SecretRegistry` is a thread-safe registry of secret values; `Redactor` (built via `registry.redactor()`) is an immutable Aho-Corasick byte-level scrubber that replaces every contiguous occurrence of a registered secret with `<redacted:NAME>`. Operates on raw bytes BEFORE UTF-8 decode so encoding mangling cannot bypass it. Validation: secrets must be ≥8 chars and pure ASCII. Overlap policy: leftmost-longest.

`CommandGrant` and `FilesystemKnowledge` accept a shared `SecretRegistry` so a token a `CommandGrant("gh")` writes to a file stays redacted when the agent later reads it via `kb_read`.

## Design Principles

- **No magic** — explicit wiring, no annotation-driven DI.
- **Records everywhere** — immutable data, pattern matching, sealed types (`Result<T>`, `QueryEvent`, `ResultMessage`, `HookOutcome`).
- **Builder pattern** — `with` prefix, static `newBuilder()` factory.
- **JPMS modules** — proper encapsulation, ServiceLoader SPI for providers.
- **Production from day 1** — fault tolerance, tracing, JaCoCo coverage gates on `helios-core` and `helios-session` at 95% instruction / 90% branch.
- **Currency is integer micro-USD** — `long microUsd` (Stripe-style fixed-precision). `BigDecimal` only at the display boundary.

## Building

```bash
mvn package
mvn spotless:apply   # auto-format (Google Java Format, 2-space indent)
```

## License

[MIT](LICENSE)
