# Helios

The Helios agentic framework for the JVM. A provider-agnostic `Model` interface, a streamable steerable session SDK, sandboxed code execution, structured output, durability primitives, and end-to-end tracing — for tool-using agents that run reliably in production.

Simple, explicit, no magic. No annotation-driven DI, no reflective surprises.

Published to [Maven Central](https://central.sonatype.com/namespace/com.standardapplied) under the `com.standardapplied` namespace. MIT licensed.

## Requirements

- Java 25+
- Maven 3.9+

Strict workspace file tools and filesystem memory additionally require Linux x86-64/AArch64,
the default filesystem, `/proc/self/fd`, and an explicit JVM native-access grant. See
[workspace confinement](#path-traversal-jails) for launch flags and compatibility details.

## Modules

Pick what you need — each jar is published independently:

| Artifact | What it gives you | External deps |
|----------|-------------------|---------------|
| `helios-core` | `Model` interface + value types, `Tool` / `CommandGrant`, `OutputSchema` + `Provenanced<T>`, `TokenCounter`, `FaultTolerance`, `TraceListener` / `SpanListener`, durability primitives (`RunStore`, `ToolCallJournal`), `SecretRegistry` + `Redactor`, `CostEstimate` / `CostCalculator` | None |
| `helios-session` | v2 SDK — long-lived `AgentSession` running an agent loop on a virtual thread. `SessionPresets`, hooks, declarative `Permission`, file tools, `MemoryBackend`, `ExecutionProvider`, `ContextCompactor` | Jackson 3.x |
| `helios-runtime` | Helidon HTTP/SSE surface for `helios-session` — `POST /sessions`, SSE `/events`, long-poll `/result` | Helidon SE, Jackson 3.x |
| `helios-gemini` | Google Gemini provider (Interactions API) | Jackson 3.x |
| `helios-anthropic` | Anthropic Claude provider (Messages API) | Jackson 3.x |
| `helios-openai` | OpenAI GPT provider (Responses API) | Jackson 3.x |
| `helios-repl` | Sandboxed JShell execution for the `Execute` tool (`JShellExecutionProvider`, `JvmSandbox`, `ReplSession`, custom host functions) | Jackson 3.x |
| `helios-onnx` | Local embeddings via ONNX Runtime | ONNX Runtime, DJL Tokenizers |
| `helios-persistence` | PostgreSQL-backed `PromptRegistry`, `TraceStore`, and durability (`PgRunStore` + `PgToolCallJournal`) | Helidon DbClient |

Most agentic apps want `helios-session` + one provider. Drop down to `helios-core`'s `Model` directly for one-shot calls; expose a session over HTTP with `helios-runtime`.

## Production Readiness

Helios is used in production but has documented limitations that you should understand before adopting it. This section is intentionally explicit so you can decide whether the current state fits your deployment risk profile.

### What's hardened

- **Fail-secure defaults.** `RuntimeServer.Builder` binds `127.0.0.1` (loopback) by default — external traffic is explicit opt-in via `withHost("0.0.0.0")`. Every HTTP route authenticates the request through the configured `Authenticator` and acts only on the caller's own sessions; an unauthenticated server (`withoutAuthentication()`) is accepted only on a loopback bind.
- **Cooperative cancellation, no `Error` swallowing.** `RetryPolicy` and the session loop catch `Exception`, not `Throwable`. An `Error` (OOM, StackOverflow, LinkageError, an `AssertionError`) caught on any thread the session runs work on (the loop, the model stream, a tool, a hook, a cancellation callback, the execution provider's start and end) is never retried or turned into a tool result or terminal: it ends the session, no further model or tool call is made, and `result()` completes exceptionally with the first such `Error`, as the same instance (later ones suppressed on it; one arriving after the result settled is logged at `SEVERE`). The loop's virtual thread then ends by throwing it to its uncaught-exception handler. That ends the thread, not the JVM; what an `Error` means for the process is the host's decision. A failure inside the sandbox child process stays contained and comes back as tool output. `Tool.execute` preserves the calling thread's interrupt status when the executor's exception chain carries `InterruptedException`.
- **Sandboxed subprocess execution with dedicated RPC channel and descendant reaping.** The `JvmSandbox` ↔ host RPC runs on a per-session Unix domain socket bound in a private temp directory (mode 0700); the host accepts exactly one connection then closes the listener, so no other process — including a JShell snippet inside the subprocess — can forge frames by writing to subprocess stdout. (Earlier versions used a `\0RPC:`-prefixed channel multiplexed on stdout, which a snippet could forge via `new PrintStream(new FileOutputStream(FileDescriptor.out))`. C1 in the original review.) Subprocess stdout stays for captured output only and is never parsed as RPC. `JvmSandbox.close()` and its JVM-shutdown-hook path snapshot descendants before killing the parent, then forcibly destroy the snapshot — snippets that call `Runtime.exec(...)` cannot orphan grandchildren past sandbox lifetime. A snippet that outlives its timeout is stopped through `jshell.stop()`, repeated until it and every thread it started have ended; one still running after `JvmSandboxConfig.stopGrace` (5 s by default) cannot be reached inside the JVM, so the sandbox answers the execute and halts with exit code 3.
- **Path-traversal jails on every filesystem boundary.** `WorkspaceRoot` backing the session's `Read` / `Grep` / `Glob` tools and the filesystem memory backend (lexical `..`/absolute refusal + canonicalisation of the deepest existing prefix + no-follow opens), `OnnxModelDownloader` (refuses absolute paths and traversal in HuggingFace-supplied filenames), and `CommandGrant` (per-call temp working directory, argv pre-scan refusing registered secrets).
- **SQL identifier validation on the persistence schema name.** `PgConfig` rejects schema names that don't match Postgres' unquoted-identifier shape, so configuration-driven schema names cannot inject SQL via the qualifier substitution.
- **One secret registry per session, applied where tool output enters it.** `SessionOptions.withSecretRegistry(registry)` gives the session its `SecretRegistry`. The session redacts every tool result's text once, before its `ToolResult` event, a `PostToolUse` hook or the history sees it, and the messages of every error it reports. Output cut at a size cap first loses any trailing start of a registered secret. `ModelConfig.toString()` redacts the API key and header values so accidental `log.info("config={}", cfg)` callsites don't leak credentials. See [Secret redaction](#secret-redaction-secretregistry--redactor) for the promise and what it does not cover.
- **Bounded HTTP/SSE backpressure.** `AgentSession`'s event publisher uses `SubmissionPublisher.offer(...)` with bounded timeouts (1s for routine events, 30s for control events like `LoopEnded` / `QuestionAsked` / `Error`). A slow or paused SSE client cannot pin the agent loop. Routine events may be dropped on a sustained slow consumer with a FINE log; control events use the longer timeout to maximise delivery.
- **Test coverage.** JaCoCo gates at 95% instruction / 90% branch on `helios-core`, `helios-session`, `helios-runtime`. Tests across the project: ~3,200 unit tests. Provider modules (`helios-gemini`, `helios-anthropic`, `helios-openai`) are gated by environment variables for live-API integration tests; CI runs unit tests without those keys.

### Known limitations

These are documented intentionally — they're real and you should plan around them. We are working through them in priority order; this README will move them from "limitation" to "hardened" as each lands.

- **Persistence layer stores trace/journal payloads verbatim unless you set a redactor.** `helios_tool_calls.{args, output, error}`, `helios_traces.{input_text, output_text, attributes}`, and `helios_spans.attributes` capture exactly what they are given. That faithful capture is what makes the audit trail useful for evals, debugging, and post-hoc analysis. The session does not feed persistence today, so its redaction pass does not reach these tables; to redact at the persistence boundary, set `PgConfig.Builder.withRedactor(registry.redactor())`, which runs every text field and JSON-serialised attribute map through the redactor before it reaches the database.
- **No backwards compatibility guarantee.** Helios is pre-1.0 in spirit — the public API may change between minor versions. Tag a known good version and pin it.
- **`FinishReason.LENGTH` is terminal.** When the model truncates output at its `max_output_tokens` cap, the loop terminates with `ResultMessage.ErrorDuringExecution(kind="max-tokens")` rather than re-issuing. Deployers that previously relied on the loop continuing past LENGTH should adjust `max_output_tokens` in `ModelConfig`. This is a deliberate change from earlier behaviour — silent re-issue burned `maxTurns` of budget without making progress.
- **Live integration coverage is environment-gated.** Provider integration tests run in CI under deployer-supplied API keys; you should add equivalent live tests for the workloads that matter to you before relying on the SDK in production-critical paths.

If any of these is a deal-breaker for your use case, open an issue — prioritisation tracks demand.

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
      Message.user("What is the capital of France?")), List.of());
  System.out.println(response.content());
}
```

All providers implement the same `Model` interface — swap providers without touching the rest of your code.

#### Reasoning

`ModelConfig.reasoning` says what a reasoning model should do; leave it unset and Helios sends no
thinking, reasoning or effort field, so the provider's defaults apply. `Reasoning.Off` stops
reasoning; `Reasoning.Effort(level, display)` reasons at `MINIMAL`..`MAX` and returns the reasoning
`HIDDEN`, as a `SUMMARY`, or only as the `PROGRESS` notes the model writes between tool calls.

```java
var config = ModelConfig.newBuilder()
    .withApiKey(System.getenv("ANTHROPIC_API_KEY"))
    .withReasoning(new Reasoning.Effort(Reasoning.Level.MEDIUM, Reasoning.Display.PROGRESS))
    .build();
var model = new AnthropicProvider().create(AnthropicModelId.CLAUDE_OPUS_5_5.id(), config);
```

Each provider's model catalogue declares what a model accepts, and the request is exactly what
was asked or `create` throws, naming the model, the rejected value and the accepted ones: `Off` on
Opus 5.5 (which always thinks), `MAX` on gpt-5.5, `PROGRESS` on any model without progress notes,
`temperature` on Claude 4.7 and later. Nothing is clamped or silently dropped. An Anthropic or
OpenAI model id the catalogue does not know is sent whatever the provider can spell, and the API
judges it.

Gemini video understanding uses the Files API for large or reusable videos. Google currently
accepts Files API video references through the beta Interactions endpoint; the stable endpoint can
reject the same `video` content block. Select `v1beta` before constructing the model. Helios never
replays a rejected stable request against beta automatically.

```java
var config = ModelConfig.newBuilder()
    .withApiKey(System.getenv("GEMINI_API_KEY"))
    .withApiVersion("v1beta")
    .build();

try (var files = new GeminiFilesClient(config);
    var video = files.uploadManaged(Path.of("meeting.mp4"), "video/mp4");
    var model = new GeminiProvider().create(GeminiModelId.GEMINI_3_8_FLASH.id(), config)) {
  var message = Message.newBuilder()
      .withRole(Role.USER)
      .withContent("Summarize this video and list the decisions.")
      .withFileReferences(List.of(video.reference()))
      .build();

  System.out.println(model.chat(List.of(message), List.of()).content());
}
```

For an `AgentSession`, attach the same reference with
`UserMessage.newBuilder().withFileReference(video.reference())`. `ManagedFile.close()` issues an
idempotent deletion against its validated `files/{id}` resource, including on exceptional exits
from try-with-resources. Existing `upload(...)` methods remain available when the caller owns file
lifecycle separately. Inline media remains available for small inputs; `GeminiFilesClient` streams
uploads without loading video bytes into heap.

### Gemini privacy controls

For privacy-sensitive calls, combine managed deletion, stateless interactions, metadata-only event
persistence, and disabled raw-output capture:

```java
var config = ModelConfig.newBuilder()
    .withApiKey(System.getenv("GEMINI_API_KEY"))
    .withApiVersion("v1beta")
    .withProviderContinuation(false)
    .withRawOutputCapture(RawOutputCapturePolicy.DISABLED)
    .build();

try (var events = JsonlEventSink.openMetadataOnly(Path.of("events.jsonl"));
    var files = new GeminiFilesClient(config);
    var media = files.uploadManaged(Path.of("private-video.mp4"), "video/mp4");
    var model = new GeminiProvider().create(GeminiModelId.GEMINI_3_8_FLASH.id(), config)) {
  var input = Message.newBuilder()
      .withRole(Role.USER)
      .withContent("Analyze the video.")
      .withFileReferences(List.of(media.reference()))
      .build();
  var response = model.chat(List.of(input), List.of());
}
```

`withProviderContinuation(false)` resends local history and neither sends nor returns Gemini
interaction IDs. `RawOutputCapturePolicy.DISABLED` preserves actionable schema and submit-validator
corrections while keeping raw model text out of structured-output exceptions. The effective
Interactions version is reported in response metadata under `gemini.apiVersion`; base URLs and
credentials are excluded from that diagnostic.
When `withBaseUrl(...)` is also set, that compatibility override supplies the complete endpoint
prefix and therefore takes precedence over canonical version-path construction.

The Gemini provider has no logging calls and does not log request bodies, uploaded bytes, file URIs,
API keys, prompts, responses, or thinking text. Application code must apply the same discipline to
exceptions and model responses. Managed deletion requests early removal, but provider-side
processing and physical retention remain controlled by Google. Google currently documents Files
API uploads as temporary with automatic deletion after 48 hours; consult Google's current data and
retention terms for the deployed account and region.

Keep video traffic on `v1beta` until the credential-gated `GeminiVideoIntegrationTest` also passes
when configured for `v1` with the same model, Files API upload, and structured-output schema. Only
then change the configured version; endpoint selection is immutable for each model instance.

Anthropic models use managed five-minute prompt caching by default. Select the one-hour
cache for conversations whose reusable prefixes must survive longer gaps, or disable caching
for one-shot workloads:

```java
var provider = new AnthropicProvider();
var config = ModelConfig.of(System.getenv("ANTHROPIC_API_KEY"));

Model longLived = provider.create(
    AnthropicModelId.CLAUDE_OPUS_5.id(),
    config,
    CachePolicy.longLived());

Model uncached = provider.create(
    AnthropicModelId.CLAUDE_OPUS_5.id(),
    config,
    CachePolicy.disabled());
```

For cost tracking, `AnthropicPricing.calculator(cachePolicy)` prices every catalogued Claude
model at Anthropic's list price, including each model's cache-read rate and the cache-write rate
that matches the policy's TTL:

```java
var options = SessionOptions.newBuilder()
    .withModel(longLived)
    .withCostCalculator(AnthropicPricing.calculator(CachePolicy.longLived()))
    .build();
```

`AnthropicPricing.AS_OF` records when the rates were last checked against the pricing page. For
negotiated or partner-platform rates, build a `CostCalculator.staticTable(...)` with
`Pricing.anthropicCaching5m(input, output)` or `Pricing.anthropicCaching1h(input, output)`; both
assume the standard 0.10× cache-read multiplier, which Fable 5.1 (0.025×) and Opus 5.5 (0.05×) do
not use.

### 2) Streamable session

`AgentSession` is a long-lived object that runs an agent loop on a virtual thread. `send` messages, `subscribe` to events, `interrupt` mid-turn, `runBlocking` for synchronous results. This is the shape that shows up in production: multi-turn tool use, mid-run user steering, permission gates, observability.

<!-- snippet: QuickStarts#workspaceSession -->
```java
try (var session =
    AgentSession.create(SessionPresets.workspace(model, Path.of("/path/to/repo")).build())) {
  var terminal =
      session.runBlocking(UserMessage.text("Summarise the public API of the session module."));
  System.out.println(((ResultMessage.Success) terminal).result());
}
```

See [Sessions](#sessions-helios-session) below.

### 3) HTTP surface

`helios-runtime` exposes a session over REST + SSE so non-JVM clients can drive it. `POST /sessions`, `POST /sessions/{id}/messages`, `POST /sessions/{id}/interrupt`, SSE `GET /sessions/{id}/events`, long-poll `GET /sessions/{id}/result?timeout=<s>`, `GET /sessions/{id}/questions`, `POST /sessions/{id}/questions/{questionId}`, `DELETE /sessions/{id}`, each authenticated and scoped to the caller's own sessions (see [HTTP surface defaults](#http-surface-defaults)). See `runtime/` for routes.

A session that asks the user, through a permission rule that says "ask" or the model's `AskUserQuestion`, waits until the question is answered. A client that missed the `QuestionAsked` event lists the questions still pending:

```http
GET /v1/sessions/sess-1/questions

200 OK
[{"questionId": "perm-0193…", "header": "Permission",
  "question": "Allow Write(./notes.md)?\n\nArguments: {\"path\":\"./notes.md\"}",
  "options": [{"label": "Allow", "description": "ask rule Write(**) matched Write(./notes.md)"},
              {"label": "Deny", "description": "Refuse this tool call"}],
  "multiSelect": false}]
```

and answers one by its id; `customText` is optional:

```http
POST /v1/sessions/sess-1/questions/perm-0193…
{"selectedLabels": ["Allow"], "customText": ""}

202 Accepted
```

An id starting `perm-` asks to approve a tool call; one starting `q-` is the model's own question. Tell them apart by the prefix, never the header: the model chooses its own question's header and labels. The answer names no tool or arguments, so it approves exactly the call the question was asked about. A body that is not a valid response is `400`, a question not pending (unknown or already answered) `404`, a closed session `409`; `401`, `413`, `429` and `503` as for `POST .../messages`.

## Sessions (`helios-session`)

`AgentSession.create(SessionOptions)` returns a session that drives an agent loop on a virtual thread the first time you `send`. Key surfaces:

- `send(UserMessage)` — queue a message (steering queue, drained per-iteration).
- `events()` — `Flow.Publisher<QueryEvent>` for streaming subscribers (text, thinking, citations, tool use and results, hooks, context warning/edited, questions, retries, turn and loop ends, errors).
- `result()` — `CompletableFuture<ResultMessage>` for blocking await.
- `runBlocking(msg)` and `runBlocking(msg, OutputSchema)` — synchronous convenience for one-shot use.
- `interrupt(reason)` — queue a synthetic mid-run user message.
- `answer(qid, response)` — resolve a pending `AskUserQuestion` or permission approval; `pendingQuestions()` lists the ones still open.
- `close()` — `AutoCloseable`; cancels the loop and never throws, whatever a cancellation callback does. A started session's loop runs the teardown and `close()` returns at once; for a session whose loop never started, `close()` runs the teardown on the caller's thread, so it can block for up to the teardown graces below. Every end of a session runs one bounded teardown before the result settles: cancel the session's token (its callbacks on a thread of their own) and wait for live tool calls, `onSessionEnd`, scheduler shutdown, publisher drain, each wait bounded by `SessionLimits.stopGrace` (5 s). A tool that ignores both its token and interruption may still run, and its effects land, after the result settles; it is logged as still running.

`SessionPresets` ships four curated factories, each returning a `SessionOptions.Builder` that already holds the model:

- `minimal(model)` — model only, defaults for everything else.
- `readOnly(model, root)` — `Read` / `Glob` / `Grep` / `LS` rooted at the workspace, gated by `Permission.planMode()`.
- `workspace(model, root)` — same read tools plus a `FileSystemMemoryBackend` (the session registers `MemoryRead` and `MemoryWrite` for it) plus `Permission.defaultInWorkspace()` (reads + memory allowed, writes / edit / execute asked).
- `openEnded(model, root, provider)` — `workspace` plus `Execute` through the given `ExecutionProvider`; `defaultInWorkspace()` asks before each execution.

Every session has a permission policy. Without `withPermission` it is `Permission.empty(DEFAULT)`, which runs reads and asks before every write, execution and network call; a headless host, where no one answers, opts out with `withPermission(Permission.empty(PermissionMode.BYPASS_PERMISSIONS))` or allow rules for the tools its runs need.

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

### Terminal classification

The loop terminates when the model's `FinishReason` indicates the session is done — `STOP` (natural completion), `CONTENT_FILTER` (refusal), `ERROR` (provider error), or `LENGTH` (response truncated at `max_output_tokens`). `LENGTH` produces `ResultMessage.ErrorDuringExecution(kind="max-tokens")` rather than re-issuing — deployers that need higher per-turn ceilings should raise `ModelConfig.maxOutputTokens` instead of relying on the loop to retry. See [Known limitations](#known-limitations) for the rationale (the previous behaviour silently burned `maxTurns` of budget without making progress).

### Event delivery semantics

`AgentSession.events()` is a `SubmissionPublisher<QueryEvent>` with a 256-item per-subscriber buffer. The agent loop emits via `offer(...)` with a bounded timeout, not blocking `submit`: routine events (text, tool use, hook fired) wait up to 1 s before dropping on a slow consumer; control events (`LoopEnded`, `QuestionAsked`, `Error`) wait up to 30 s. A subscriber that pauses indefinitely will see a gap in its event stream, but cannot pin the agent loop. Drops are logged (FINE for routine, WARNING for control).

### Context compaction

The agent loop estimates context fill through a pluggable `TokenCounter` (default char-based, ~4 chars/token) and reacts at two watermarks:

- **0.85** of `SessionLimits.maxContextTokens()` → emits `QueryEvent.ContextWarning(usagePct)` once.
- **0.95** → invokes the configured `ContextCompactor`, swaps the returned history in, and emits `QueryEvent.ContextEdited(removedBlocks, tokensBefore, tokensAfter)`.

The default `SummarizingCompactor` keeps the system prompt and the pending user turn and replaces the rest of the conversation with one summary from one model call. No earlier assistant turn survives, so no reasoning a provider signed against the old conversation (Claude Fable 5.1, Opus 5.5, Sonnet 5.5) is replayed and no `tool_call`/`tool_result` pair is left to split; it never compacts in the middle of a tool round. The summary call runs on a virtual thread under a configurable timeout (default 60s) and reports its `Usage` so spend gates through `SessionLimits.maxBudgetMicroUsd`.

Pick the summary model, prompt and timeout via `SummarizingCompactor.newBuilder(...)`, swap the whole compactor through `SessionOptions.Builder.withContextCompactor(...)`, turn compaction off with `ContextCompactor.disabled()` (it leaves the history unchanged, so a long session overflows and the provider's error ends it), or do ad-hoc rewrites by returning `HookOutcome.mutateHistory(rewritten)` from a `PreModelTurnHook`. See [`session/README.md`](session/README.md#context-compaction) for the full surface.

## Resource Lifecycle

`Model` is `AutoCloseable` and holds long-lived resources (HTTP connection pool, file descriptors). **Build it once at app startup, share across many sessions, close once at app shutdown.** The component that constructs a `Model` owns its lifecycle — a session does NOT close its `Model` because closing one model would break sibling sessions sharing it.

<!-- snippet: QuickStarts#sharedModel -->
```java
try (var model = new AnthropicProvider().create(modelId, config)) {
  for (var request : requests) {
    try (var session = AgentSession.create(SessionPresets.workspace(model, repo).build())) {
      session.runBlocking(UserMessage.text(request));
    }
  }
} // HttpClient and connection pool released here
```

`AgentSession` is `AutoCloseable` and owns its loop, per-session publisher executor, and any pending tool/question state. Always use try-with-resources; `close()` cancels the loop, and the result future settles once the teardown has run, each of its waits bounded by `SessionLimits.stopGrace` (5 s).

`ReplSession` is `AutoCloseable` and owns its sandbox subprocess. `JvmSandbox` also installs a JVM shutdown hook so a leaked sandbox is force-killed on host JVM exit. Both the explicit close and the shutdown hook snapshot the subprocess's descendants and forcibly destroy them before the parent — snippets that call `Runtime.exec(...)` cannot leave orphaned grandchildren past the sandbox lifetime.

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

`Tool.newBuilder().withIdempotent(true)` records that a tool is safe to repeat; the session loop does not read it, because it never retries a tool: `ToolDispatch` runs the tool once on a virtual thread bounded by the per-call timeout, and turns an unknown tool, a timeout or a thrown exception into a failed result the model sees.

### CommandGrant — give the agent a CLI without giving it the secrets

`CommandGrant` produces a `Tool` that lets the model invoke a single CLI binary under tight controls. It must be built with the registry of the sessions that run it; `build()` registers every `withEnv(...)` value there, and the session redacts the tool's output against it:

```java
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolRegistry;

var registry = new SecretRegistry();
var gh = CommandGrant.builder("gh")
    .withSecretRegistry(registry)                     // required: the sessions' registry
    .withEnv("GH_TOKEN", System.getenv("GH_TOKEN"))   // value never reaches the model
    .withTimeout(Duration.ofSeconds(30))
    .withMaxOutputBytes(50_000)
    .withArgValidator(args ->
        args.isEmpty() || !"auth".equals(args.get(0))
            ? Optional.empty()
            : Optional.of("'gh auth' is not allowed via this grant"))
    .build();

var options = SessionOptions.newBuilder()
    .withModel(model)
    .withSecretRegistry(registry)                     // the same instance
    .withTools(new ToolRegistry(List.of(
        ToolBinding.newBuilder(gh.toTool()).withCategory(ToolCategory.EXECUTION).build())))
    .build();
```

`build()` without `withSecretRegistry` throws, and the tool refuses to start a process in a session whose registry is a different instance. Hardening is on by default: binary path pinned at build, argv-only (no shell), `ProcessBuilder` env cleared then injected so the JVM's environment never leaks into the child, argv pre-scan refuses any registered secret value (forces env-only secret transport), per-call temp working directory, output capped (a stream cut at the cap loses any trailing start of a registered secret), descendants killed on timeout, stderr hidden from the model unless `withStderrToModel(true)`.

### Curated corpus — point the workspace tools at any root

The session's `Read` / `Grep` / `Glob` tools accept any `WorkspaceRoot`, not just the agent's working directory — point them at a curated reference tree to give the model native lexical search over operator-supplied content (the "no vector DB needed for a bounded corpus" pattern). Their text output is redacted by the session like every tool's, so with one `SecretRegistry` set on the session and on your `CommandGrant`s, a token written to a file by `gh auth login` stays scrubbed when the agent later reads it back.

```java
var corpus  = WorkspaceRoot.of(Path.of("/var/kb/support"));
var tools   = new ToolRegistry(List.of(
    ReadTool.binding(corpus),
    GrepTool.binding(corpus),
    GlobTool.binding(corpus)));
var options = SessionOptions.newBuilder()
    .withModel(model)
    .withSecretRegistry(registry)
    .withTools(tools)
    .withPermission(Permission.planMode())
    .build();
```

Path-jail (`..` / absolute refusal + canonicalisation + no-follow opens) is built into `WorkspaceRoot`; per-file size caps, per-output byte caps, hidden-directory pruning, symlink/special-file skipping during walks, and binary skip are built into the tools. `Permission.planMode()` or a curated `Permission` policy enforces read-only at the session level — there is no `Write` tool to disable. To track what the agent read, register a `PostToolUseHook` on `Read`.

## Structured Output

`OutputSchema.of(MyRecord.class)` generates a JSON Schema from a Java record. The model returns a typed value, validated against the schema; shape mismatches surface as `StructuredOutputParseException` with a per-field diff.

```java
record Sentiment(String label, double confidence) {}

var response = model.chat(messages, List.of(), OutputSchema.of(Sentiment.class));
Sentiment s = response.parsed();
```

For provenance-tagged output (Basis-style — every field carries source citations + confidence), wrap with `OutputSchema.provenancedOf(MyOutput.class)`:

```java
var schema = OutputSchema.provenancedOf(MappingProposal.class);
var response = model.chat(messages, List.of(), schema);
Provenanced<MappingProposal> result = response.parsed();
// result.output() = the typed output; result.provenance() = per-field sources + confidence
```

`ProvenanceValidator.DEFAULT` rejects `MEDIUM`/`HIGH` confidence entries that have no sources — the calibration mechanism that prevents the model from rubber-stamping HIGH on every field. Custom validators via `OutputSchema.provenancedOf(MyOutput.class, validator)`.

Through `session.runBlocking(message, schema)`, the loop intercepts `StructuredOutputParseException`, injects a corrective USER turn carrying the diff, and re-iterates.

## Streaming

The session's `events()` publisher is the primary streaming surface — see [Sessions](#sessions-helios-session). For direct model use without a session:

```java
try (var stream = model.chatStream(messages, tools)) {
  while (stream.hasNext()) {
    switch (stream.next()) {
      case ModelChunk.TextDelta d -> System.out.print(d.text());
      case ModelChunk.ToolUseStop stop -> System.out.println("Called: " + stop.toolCall().name());
      case ModelChunk.MessageStop stop -> System.out.println("\n" + stop.finishReason());
      default -> {}
    }
  }
}
```

Nothing is sent before the first `hasNext()`; each chunk arrives as the provider streams it.
`close()`, from any thread, aborts the request and closes its connection without closing the model.
A failed turn throws its exception from `hasNext()`.
`model.chat(messages, tools)` drains the same stream (`ChunkDrain.drain`).
A model you write that can only answer blockingly implements `BlockingModel`.

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

var result = ft.execute(() -> model.chat(messages, List.of()));   // checked exceptions: see Javadoc
```

`FaultTolerance.withoutRetry()` returns a sibling envelope with retry stripped (circuit breaker + timeout retained), for wrapping an operation that must not replay.

## Observability

The session's `events()` publisher is the primary observability surface. Subscribe a `Flow.Subscriber<QueryEvent>` (or a per-phase `OnStreamEventHook` for in-loop interception) and pick the events you care about — see the sealed hierarchy in `com.standardapplied.helios.session.QueryEvent` (`AssistantText`, `ToolUse`, `ToolResult`, `ContextWarning`, `ContextEdited`, `HookFired`, `TurnEnded`, `LoopEnded`, etc.).

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

`helios-core` also ships generic event-sink primitives (`EventSink`, `JsonlEventSink`,
`CollectingEventSink` in `com.standardapplied.helios.core.events`) plus a `Trace` / `Span` value-type hierarchy in
`com.standardapplied.helios.core.trace` for custom collectors. These are currently inert for `AgentSession` runs —
wire them via your own hook or stream subscriber.

`JsonlEventSink.openMetadataOnly(path)` persists run/span IDs, event types, timing, model and token
counts, API version, tool names, outcomes, and redacted error categories. It omits message history,
assistant text, thinking and signatures, tool arguments and results, file URIs, arbitrary
attributes, and custom-data values. `JsonlEventSink.openFull(path)` is the explicit replay/debugging
mode and persists sensitive content verbatim; it is unsuitable for customer-media workflows.

## Sandboxed Code Execution (`helios-repl`)

The `helios-repl` module runs Java snippets in a JVM subprocess sandbox. A session reaches it through the `Execute` tool with `runtime: JSHELL`: wire `JShellExecutionProvider` as the session's execution provider and bind `ExecuteTool`. Each session gets its own sandbox, so variables, imports and classes persist across its `Execute` calls. The sandbox reaches the host only through the custom host functions you register on `ReplConfig`:

```java
var quote = new HostFunction(
    "marketQuote",
    "Current price of a ticker",
    List.of(HostParameter.required("ticker", ParameterType.STRING, "Ticker symbol")),
    params -> prices.latest((String) params.get("ticker")));

try (var executionProvider = JShellExecutionProvider.create(
         ReplConfig.newBuilder().withHostFunction(quote).build());
     var session = AgentSession.create(
         SessionOptions.newBuilder()
             .withModel(model)
             .withTools(new ToolRegistry(List.of(ExecuteTool.binding(executionProvider))))
             .withExecutionProvider(executionProvider)
             .withPermission(Permission.lockedDown())
             .build())) {
  session.runBlocking(UserMessage.text("What is AAPL trading at?"));
}
```

The model calls `Execute` with `{"runtime": "JSHELL", "script": "println(marketQuote(\"AAPL\"))"}` and gets back the snippet's stdout, stderr and exit code. Tell the model what the sandbox offers in your system prompt: `SandboxPrelude.modelFacingSummary()` describes the built-in helpers, and `SandboxPrelude.formatSignature(quote)` renders a host function's signature as the model calls it. Credentials never enter the sandbox: a host function runs in the host process and returns only what it chooses to.

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
    .withResolver(...)
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
var annotationStore = new PgAnnotationStore(pgConfig);
var promptRegistry = new PgPromptRegistry(pgConfig);
var durability = PgDurability.of(pgConfig);   // RunStore + ToolCallJournal
```

Schema lives on the classpath at `com/standardapplied/helios/persistence/schema.sql` — run it against your database to create the `helios_*` tables. Optional custom schema prefix is applied to all generated SQL; the schema name is validated against Postgres' unquoted-identifier shape (`[A-Za-z_][A-Za-z0-9_]{0,62}`) at `PgConfig` construction.

**Annotations.** `core.trace.Annotation` is a structured note attached to a real trace or span (`subjectId`). An optional `facet` addresses a named sub-coordinate within the subject, so one author can hold several judgments about one subject — e.g. one rating per evaluation dimension — without minting synthetic ids; `authorKind` (`HUMAN | MODEL | SYSTEM`) types the author and `metadata` (a `jsonb` map) carries opaque consumer context. `PgAnnotationStore.upsertAnnotation` persists the full record and is idempotent on `(subjectId, facet, label, authorId)`. Read through the same store via `findAnnotationsBySubject(id)`, `findAnnotationsBySubjects(ids)` (batch), or `listAnnotations(paginate, scimFilter)` (SCIM over the first-class columns). `facet`, `label`, and `metadata` are opaque to Helios — it stores and returns them without interpretation.

**Trace fidelity by design.** Trace and journal payloads (`helios_tool_calls.{args, output, error}`, `helios_traces.{input_text, output_text, attributes}`, `helios_spans.attributes`) are persisted verbatim unless `PgConfig.Builder.withRedactor(registry.redactor())` is set, in which case every trace/journal text field and JSON-serialised attribute map runs through the supplied `Redactor` before reaching the DB. The session's redaction pass does not reach persistence: the session does not feed these stores today, so whatever you record into them yourself is stored as you give it.

## Security

Helios is designed for production but has known limitations — see [Production Readiness](#production-readiness) above for the full hardening surface and the deferred items. This section documents the security primitives library users build on.

### Secret redaction (`SecretRegistry` + `Redactor`)

`core.common.SecretRegistry` is a thread-safe registry of secret values; `Redactor` (built via `registry.redactor()`) is an immutable Aho-Corasick byte-level scrubber that replaces every contiguous occurrence of a registered secret with `<redacted:NAME>`. It operates on raw bytes before UTF-8 decode so encoding mangling cannot bypass it. Overlap policy: leftmost-longest.

A secret is registered under a non-blank name (re-registering a name replaces its value) and its value must be at least 8 characters of JSON-safe printable ASCII: every character in `0x20`–`0x7E` except `"` and `\`. `register` throws `IllegalArgumentException` otherwise: on a null value, a value shorter than 8 characters, a control character (`0x00`–`0x1F`, `0x7F`), `"` or `\` (Jackson escapes these, so the serialised bytes would no longer match), or any non-ASCII character (the redactor matches ASCII bytes).

```java
var registry = new SecretRegistry();
registry.register("STRIPE_KEY", System.getenv("STRIPE_KEY"));
registry.register("GH_TOKEN", System.getenv("GH_TOKEN"));

var options = SessionOptions.newBuilder()
    .withModel(model)
    .withSecretRegistry(registry)
    .build();
```

**The promise: no registered value reaches the model, and none appears in anything the session emits.** Each session has one registry, set with `SessionOptions.withSecretRegistry` (default: a new empty registry, which redacts nothing; sessions built from the same options share it). The session redacts each tool result's text once, right after the tool returns, before its `QueryEvent.ToolResult` (in-process subscribers, `OnStreamEvent` hooks, the runtime's SSE stream), `PostToolUse` hooks and the conversation history see it. That covers every tool — built-in, custom, `MemoryRead`, `CommandGrant`, `Execute` — successes and failures, the results the loop substitutes (blocked, skipped, timed out, threw, not found, dispatch failed), and the text a `PostToolUse` `MutateResult` supplies. `QueryEvent.ToolResult.redactions()` reports how many occurrences of each secret that pass replaced. The messages of every error the session reports — `TurnRetried`, error terminals, `ErrorProviderUnavailable`'s reason and cause — are redacted the same way. Each redaction takes a fresh snapshot of the registry, so a secret registered mid-session (a tool can reach the registry through `ToolContext.sessionContext().secrets()`) is honoured from the next result on. Where a tool cuts its output at a size cap (`CommandGrant`, `LocalProcessExecutionProvider`, `Read`'s per-line cap), the cut first drops any trailing start of a registered secret, so half a secret never survives the cap. The arguments that `ToolUse`, `ToolMutated`, `ToolBlocked` and `ToolResult` carry are redacted the same way, in keys and string values, while the tool, its `PreToolUse` and `PostToolUse` hooks and the history see them as written; a `ToolBlocked` reason is redacted too, and a hook's or tool's failure the session logs attaches a redacted copy of the exception.

Not covered:

- Text that does not come from tools: user messages, the system prompt, hook `Inject` messages (deployer code), and the model's own output.
- `Read` attachments: images and PDFs pass through unredacted.
- Runtime HTTP error bodies.
- Persistence: verbatim unless `PgConfig.Builder.withRedactor(...)` is set; the session does not feed persistence.
- Telemetry content capture.
- Secrets the model rebuilds from pieces (encoded, or split across calls): redaction matches exact bytes.

`ModelConfig.toString()` elides the `apiKey` and every header value regardless of registry contents, so a `ModelConfig` is safe to log directly.

### Sandbox security model

Defense in depth, not a single boundary:

1. **OS-level sandbox** (deployer's responsibility) — a container, VM, or namespace boundary around the Helios process. This is the authoritative escape boundary and the only layer that isolates the filesystem and the network.
2. **JVM subprocess sandbox** (`JvmSandbox`) — the model's code runs in a separate JVM with cleared environment, system-properties stripped from parent JVM args, classpath/modulepath inherited but agents (`-javaagent`, `-Dauth.token=…`) filtered, descendants reaped on close / shutdown hook, single-execute serialised via `Semaphore(1)`.
3. **Bytecode policy** (`SandboxPolicy` + `PolicyBytecodeVerifier`) — every snippet class is scanned before it links. Direct calls, field access, class literals, and every reference reachable through `invokedynamic` bootstrap arguments, method-handle constants and dynamic constants are checked against the same owner/member rules, so `FileReader::new` is denied exactly like `new FileReader(...)`. Malformed or cyclic constant pools fail closed.
4. **JShell prelude controls** — typed wrappers around `HostFunction`s, reserved-name skip on synthesiser, frozen `HostFunctionRegistry` after sandbox boot.

**What the sandbox subprocess is not.** The child JVM runs as the same user as the Helios process, in the same mount and network namespaces. Its private working directory scopes relative paths, nothing more: absolute paths, `/proc`, the host's sockets and every file the Helios user can read are reachable from a snippet unless a bytecode policy denies the API or the OS boundary hides the resource. The bytecode verifier is defense in depth against honest mistakes and probing snippets; it is a static scan of JShell-compiled classes, not a JVM security manager, and it inherits the JVM's own limits (a static receiver type it cannot widen, JDK internals reached through `--add-opens`, native code with `--enable-native-access`). Treat layer 1 as mandatory for untrusted snippets.

The sandbox subprocess RPC runs on a per-session Unix domain socket bound in a private temp directory (mode 0700) — the host accepts exactly one connection from the subprocess on startup and then closes the listener and deletes the socket file. A JShell snippet that obtains a raw `PrintStream` to `FileDescriptor.out` can write whatever it wants to subprocess stdout; the host reads stdout into the execution result's captured-output buffer but never parses it as RPC, so forged frames are inert text.

#### Least-privilege deployment

`JvmSandbox` launches its child with `ProcessBuilder`, so the child shares whatever confinement the Helios process itself runs under. The supported least-privilege contract is therefore to run the process that owns the sandbox — the whole application, or a dedicated executor service that exposes only the `Sandbox` seam — inside a container that grants nothing it does not need. Rootless Podman, no image beyond the JDK and your jars:

```bash
podman run --rm \
  --network none \
  --read-only --tmpfs /tmp \
  --cap-drop ALL --security-opt no-new-privileges \
  --pids-limit 256 --memory 1g \
  -v /opt/helios/app:/opt/helios/app:ro \
  -v /srv/tenant-42/workspace:/workspace:ro \
  docker.io/library/eclipse-temurin:25-jdk \
  java --enable-native-access=ALL-UNNAMED -cp '/opt/helios/app/*' your.Executor
```

- Use a full JDK 25 image: the executor and sandbox require `jdk.jshell` and `jdk.compiler`, which are absent from the Temurin JRE image.
- `--network none` removes every interface but loopback inside the container; the host's listeners and the wider network are unreachable even with a permissive `SandboxPolicy`. Replace it with an egress-filtered network only for workloads that need one.
- `--read-only --tmpfs /tmp` gives the sandbox its scratch directory and RPC socket directory on a private tmpfs and nothing else writable. Mount the workspace read-only and let host-owned tools (`Read` / `Grep` / `Glob` on a `WorkspaceRoot`) mediate access.
- `--cap-drop ALL --security-opt no-new-privileges` blocks privilege escalation inside the container; `--pids-limit` and `--memory` bound fork bombs and heap abuse beyond `JvmSandboxConfig.maxHeapMb`.

`IsolatedDeploymentTest` in `helios-repl` exercises this recipe with the real `JvmSandbox` under a permissive policy: a snippet inside the container cannot read a host sentinel file or connect to a host TCP listener. It runs wherever rootless Podman can start a container and skips with a stated reason elsewhere, so a green build on an unsupported host is not a claim of enforcement.

### Path-traversal jails

Every filesystem boundary in the framework refuses traversal. Lexical normalise + `startsWith(root)` first (to reject `..` and absolute paths without dereferencing), then `toRealPath()` to refuse symlink escapes:

- `WorkspaceRoot` (backing the session's `Read` / `Grep` / `Glob` tools at the workspace or any curated-corpus root, and `FileSystemMemoryBackend`)
- `CommandGrant` (per-call temp cwd unless explicit `withCwd`)
- `OnnxModelDownloader` (HF-supplied file paths against the local model cache)
- `PgConfig` (Postgres schema name validated against unquoted-identifier shape)

`WorkspaceRoot` is the confinement contract for `Read`, `Grep`, `Glob`, `Ls` and filesystem memory. `root()` is canonical. `resolveSafe` canonicalises the deepest *existing* prefix, rejecting outside, dangling and looping links even for a not-yet-existing leaf. Explicitly configured root aliases and requests through legitimate in-workspace aliases remain supported; returned paths use their canonical spelling.

Strict I/O does not trust that earlier path check. It descends from the filesystem root using directory descriptors and single-component no-follow `openat` calls, including every ancestor of the workspace root. An `O_PATH` descriptor pins the leaf for metadata inspection without opening a FIFO or device for I/O. Only a regular file is reopened through the kernel's `/proc/self/fd` namespace, while its descriptor remains owned by the stream. Size-limited reads validate that pinned target and enforce the byte budget during reading, including concurrent growth. `Grep` sniffs and searches the same bounded content. MIME detection does not invoke platform detectors that might reopen an unconfined pathname.

Directory creation (`mkdirat`), deletion (`unlinkat`), and directory enumeration also use pinned directory descriptors. Walkers never follow directory symlinks, and content tools skip non-regular entries. `Ls` can display symlink metadata without following it. A missing workspace root is not recreated. New files and directories are owner-only (0600/0700, further restricted by the process umask); existing permissions are preserved. Default output creates or truncates, `CREATE_NEW` remains exclusive, and `DELETE_ON_CLOSE` is explicitly unsupported in strict mode.

`FileSystemMemoryBackend` is strictly narrower: a `/memories/...` path must canonicalise to exactly its lexical location under `<root>/.agent/memory`, so any symlink in that path — even one pointing at another file inside the workspace — is refused for `view`, `create`, `strReplace`, `insert`, `delete` and `list`. Memory always uses strict I/O, even if supplied a trusted-mode workspace. Memory content is strict UTF-8; invalid text is rejected before creating directories or opening a write target.

Strict mode requires Linux x86-64 or AArch64, the default filesystem and mounted `/proc/self/fd`. It uses JDK 25's Foreign Function & Memory API to call libc; no downloaded native library, compiler or private JDK API is needed. Enable native access for the session module:

```text
java --enable-native-access=com.standardapplied.helios.session --module-path ... --module your.application/your.Main
```

For a classpath application, use `java --enable-native-access=ALL-UNNAMED -cp ... your.Main`. Maven tests configure these grants automatically. The host grant is not propagated to `JvmSandbox` child JVMs. Construction fails closed when native access, the filesystem or the platform is unsupported, including macOS and Windows; there is no automatic weaker fallback.

`new WorkspaceRoot(root, false)` is the explicit, portable **trusted-workspace** opt-out, never a preset default. It permits symlinks outside the root and does not provide race-resistant ancestor confinement: a concurrent writer that swaps in a symlink after a path was authorised, or after a walked entry was checked against the permission policy, can redirect the operation. Use it only when nothing else writes to the workspace while the agent runs, or when every process that can write it may also read every target a symlink in it may reach. It does not relax filesystem memory's requirements.

Descriptor confinement prevents symlink substitution from redirecting an operation. It is not a filesystem snapshot: an opened inode remains the operation's target after a rename, and another writer can still edit its contents. Hard links, bind mounts, a hostile kernel or a process that can modify the host's mount namespace require OS-level isolation. Permission keys assume a case-sensitive filesystem: on a case-insensitive one (an ext4 casefold directory, vfat, SMB) a path spelled in another case names the same file under a different key, so path rules there are out of scope like hard links. These file tools are not a sandbox for arbitrary code execution.

### HTTP surface defaults

`RuntimeServer.Builder` binds **`127.0.0.1` by default** (loopback only). Every route authenticates the request through one `Authenticator` before it reads the body or looks up a session, and a session is reachable only by the `Principal` (tenant and subject) that created it; another principal's session answers the same `404` as an unknown id. A missing, malformed, expired, revoked or forged credential is `401`, and every `401` carries the authenticator's `challenge()` in `WWW-Authenticate`. When an identity expires or is revoked, an open SSE stream closes, a pending result long poll, or one whose result is still serializing, answers `401`, and a message or interrupt still uploading its body, or a create whose options factory is still running, answers `401` and does nothing.

Behind an authenticating reverse proxy, trust the identity it asserts only on its connections:

```java
var server = RuntimeServer.builder()
    .withRegistry(SessionRegistry.inMemory())
    .withOptionsFactory(request -> SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(request.sessionId())
        .withSystemPrompt(promptFor(request.owner().tenant()))
        .build())
    .withHost("0.0.0.0")
    .withAuthenticator(TrustedProxyAuthenticator.newBuilder()
        .withTrustedProxy(InetAddress.getByName("10.0.0.5"))  // reads X-Authenticated-Tenant / -Subject
        .build())
    .build();
```

The proxy must strip client-supplied copies of both headers. Its `401` challenges with `TrustedProxy realm="helios"` unless `withChallenge(...)` sets another. Any other verifier (a JWT library, mutual TLS) plugs in as an `Authenticator` returning an `Authentication` with the principal, the credential's remaining lifetime and a stage that completes on revocation, and declaring its scheme's challenge, such as `Bearer realm="helios"`. The options factory receives a `SessionRequest`: the generated session id, for which it must return options, and the `Principal` that will own the session. `withoutAuthentication()` serves a loopback bind as one local principal; on any other host `build()` refuses it, as it refuses a non-loopback host with no authenticator. `RuntimeServer` installs no Helidon feature discovered on the classpath; mount `AgentHttpService` on your own `WebServer` to combine it with features, and set on it the two Helidon options below. Helios writes its own JSON with one fixed mapper, so that server needs no media support.

Every request is admitted against finite bounds before any work, and a refused one is answered at once with a fixed body. A body is at most `maxRequestBytes` (256 KiB, `413`). A principal has at most `maxConcurrentRequestsPerPrincipal` (32) requests in flight (`429` with `Retry-After: 5`). Open SSE streams and waiting result polls count, so size it above your peak open streams. A `RuntimeServer` serves at most 512 requests at once; beyond that Helidon answers its own `503`, without `Retry-After`, and closes the connection. A full steering queue is `429`.

At most 16 sessions per principal and 256 per registry, finished ones included. Your own oldest finished session is evicted to make room, never another principal's. Otherwise `429` (your cap) or `503` (the registry's), each with `Retry-After: 5`. A session not running, whether never started or finished, is closed after `withRetention` (15 min); `RuntimeServer` sweeps every 30 seconds. A deleted session still running counts until it stops.

A Helios refusal answers without reading the body and closes the connection: a `401`, `404`, `413`, `429` or `503` refused before the body, a fixed `500`, or a `201` for a create that sent one, is written whole with `Connection: close` (Helidon 4.5.4 or later), and nothing after it is read. `RuntimeServer` sets Helidon's `maxPayloadSize(maxRequestBytes)` and `Http1Config.continueImmediately(true)`, and so must your own `WebServer`:

```java
WebServer.builder()
    .maxPayloadSize(limits.maxRequestBytes())
    .addProtocol(Http1Config.builder().continueImmediately(true).build())
```

Then no body byte is ever parsed as a request, which a client sending `Expect: 100-continue` could otherwise use to smuggle a request, with identity headers of its choosing, past a proxy that checked only the first. A body declared over `maxRequestBytes` gets Helidon's `413` before routing, unread, and Helidon's own answers (an unmatched path, an escaped `Error`) read at most `maxRequestBytes` of a body. A request whose body was read to its end, or that has none, keeps its connection.

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
mvn spotless:apply                # auto-format (Google Java Format, 2-space indent)
mvn -B clean verify javadoc:jar   # what CI runs: tests, coverage and the quality gate
```

### Contributing

`mvn verify` enforces a quality gate on main and test code: PMD complexity, size and coupling
limits, CPD duplication detection across modules, and ArchUnit architecture rules. A change that
breaks it is fixed in the code — exclusions are never added and rules are never suppressed in
source. The limits, the commands and the policy are in the
["Quality gate" section of `CLAUDE.md`](CLAUDE.md#quality-gate).

## License

[MIT](LICENSE)
