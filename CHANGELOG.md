# Changelog

All notable changes to Helios are documented here. Versions follow [SemVer](https://semver.org/).

## Unreleased

### Breaking

**Core cleanup: one Anthropic price rule, one JSON adapter type, no `core.runtime`.**
`AnthropicPricing` is the one Anthropic price rule; core's helpers assumed a 0.10× cache read that
Fable 5.1 and Opus 5.5 do not use. `core.runtime` shared its name with the `helios-runtime`
module, so its two types moved and the package is gone.

| 2.x | 3.0 |
|---|---|
| `CostCalculator.Pricing.anthropicCaching5m(in, out)`, `anthropicCaching1h(in, out)`, `ANTHROPIC_5M_CACHE_WRITE_MULTIPLIER`, `ANTHROPIC_1H_CACHE_WRITE_MULTIPLIER`, `ANTHROPIC_CACHE_READ_MULTIPLIER` | `AnthropicPricing.pricing(model, cachePolicy)`, or `Pricing.ofUsdPerMillion(in, out, cacheWrite, cacheRead)` for custom rates |
| `StructuredContentParser.JsonAdapter` (an interface) | a record `JsonAdapter(Reader reader, Converter converter)`; an implementing class becomes `new JsonAdapter(json -> …, (map, type) -> …)` |
| `com.standardapplied.helios.core.runtime.CancellationToken` | `com.standardapplied.helios.core.model.CancellationToken` |
| `com.standardapplied.helios.core.runtime.SessionContext` | `com.standardapplied.helios.core.tool.SessionContext` |

**`HookFired` carries the hook's phase and its outcome.** A reader of `events()` or a trace sees
which guardrail fired and why: the `Block` reason, the injected text, the rewritten arguments,
each with the session's secrets redacted; the loop still acts on the hook's own outcome.

| Before | 3.0 |
|---|---|
| `String HookFired.phase()` (`"PreToolUseHook"`) | `HookPhase HookFired.phase()`, the new `session.hooks.HookPhase` enum (`PRE_TOOL_USE`) |
| `String HookFired.outcomeKind()` (`"Block"`) | `HookOutcome HookFired.outcome()`, redacted, never `Continue`; `outcome().getClass().getSimpleName()` gives the old string |
| `new HookFired(..., hookName, String phase, String outcomeKind)` | `new HookFired(..., hookName, HookPhase phase, HookOutcome outcome)` |
| runtime SSE `HookFired`: `"phase":"PreToolUseHook","outcomeKind":"Block"` | `"phase":"PRE_TOOL_USE","outcome":{"kind":"Block","reason":"..."}`, each outcome its `kind` and components, `MutateHistory` as `{"kind":"MutateHistory","messages":<count>}` |
| trace `hook.<name>` span `hook.phase` = `PreToolUseHook` | `PRE_TOOL_USE`, so a trace query filtering on it changes; a `VERBOSE` trace adds `hook.text`, the outcome's redacted reason or text |

**Every run records a trace; a trace's session id is a string.** A session given a trace
listener hands it one trace per run, and each model attempt is an event.

| Before | 3.0 |
|---|---|
| an exhaustive `switch` over `QueryEvent` | handles the new subtype `QueryEvent.ModelCallEnded`, or has a `default` |
| `SessionOptions`' 18-component canonical constructor | 20 components: `Optional<TraceListener> traceListener` and `TraceDetail traceDetail` last; build through `SessionOptions.newBuilder()` |
| `UUID Trace.sessionId()`, `Trace.Builder.withSessionId(UUID)`, `TraceBuilder.withSessionId(UUID)` | `String`, so an agent session's id (`sess-<id>`, `member:42`) fits |
| `helios_traces.session_id UUID` | `VARCHAR(128)`; on an existing database run `ALTER TABLE helios_traces ALTER COLUMN session_id TYPE VARCHAR(128) USING session_id::text` |

**Session ids follow one rule.** `SessionOptions` accepts only ids of 1 to 128 characters of
`[A-Za-z0-9._:-]` that do not start with `.`, so an id works with every session store and can
never name a path outside a file store's directory.

| Before | 3.0 |
|---|---|
| any non-blank `withSessionId(id)` | `IllegalArgumentException` stating the rule for an id outside it; the generated `sess-<id>` matches |
| `SessionOptions`' 17-component canonical constructor | 18 components: `Optional<SessionStore> store` last |

**A session is a conversation that runs many times.** `send` steers the run in progress or starts
the next one, and returns that run's `ResultMessage` future; a session runs again after any
ending until it is closed. `maxTurns`, `maxBudgetMicroUsd` and `maxWallClock` count per run, as do
a result's usage, cost and duration; the history, hooks, permissions and their approvals, the
secret registry, the execution provider and the memory backend stay per session.

| Before | 3.0 |
|---|---|
| `AgentSession.create(options)` | `AgentSession.open(options)`; `AgentSessionImpl`'s constructor is no longer public |
| `void send(message)`, then `result()` | `CompletableFuture<ResultMessage> send(message)`: the future of the run the message is part of |
| `result()` | removed: the future `send` returns |
| `interrupt(reason)` queued `"[interrupted by user: …]"` | `interrupt()` cancels the run in progress (`Cancelled`, reason `"interrupted"`) and keeps the session |
| `isStarted()` | `isRunning()`: whether a run is in progress; `currentTurnIndex()` is that run's, `0` between runs |
| `QueryEvent.LoopEnded` | `QueryEvent.RunEnded`, the last event of each run; `QueryEvent.RunStarted` is the first |
| every `QueryEvent` and `ResultMessage` record | gains `String runId` after `sessionId`; `HookContext` gains `runId()` |
| `ResultMessage.ErrorProviderUnavailable` | `AgentSession.open` throws `SessionUnavailableException` (message: the provider and its redacted reason; cause: the provider's exception) |
| `ErrorDuringExecution` kind `EmptyHistory` | kind `NoUserMessage`: every message queued for a run's first turn was blocked |
| `send` on a terminal session threw `IllegalStateException("session is terminal")` | it starts the next run; `send` on a session a host `Error` failed throws `IllegalStateException("session failed: <Error class>")` |
| `maxTurns`, `maxBudgetMicroUsd`, `maxWallClock` per session | per run |
| `events()` ended at the session's terminal, and a late reader got `LoopEnded` once | a stream spans every run and ends when the session is closed; nothing is replayed |
| `onSessionEnd` ran at the end of the loop | at `close()`, or after a host `Error`; a sandbox lives across runs |
| `SessionState(...)`'s `elapsed()` | `state.totals().elapsed()`, the run's; `new SessionState(session, runToken)` builds a session's next run |
| `AgentLoop.run(state, limits)` | `run(state, limits, boundary)`, the session's `RunBoundary` |
| runtime: a message after the session's terminal was `409` | it starts the next run (`202`); `409` only for a session closed after it was looked up |
| runtime: `POST .../interrupt` with `{"reason": …}` queued a message | no body; cancels the run in progress and answers `202` |
| runtime: `GET .../result` answered the session's terminal | the latest run's: the run in progress, else the last, else the first; the body is `{type, runId, result}` |
| runtime: a provider refusal at create ended the session `ErrorProviderUnavailable` | `POST /sessions` answers `503 {"error":"session unavailable"}` |
| runtime: `SessionRegistry` held "terminal" sessions | it holds idle ones: a session is active only while a run the HTTP service started is in progress; eviction, the caps and retention count idleness, from a run's end or from registration |

**Root package, JPMS modules and Maven coordinates renamed to `com.standardapplied`.** The
project moved from the `singlr-ai` organisation to Standard Applied Intelligence Labs. Maven
`groupId` `ai.singlr` → `com.standardapplied`; artifact ids are unchanged (`helios-core`,
`helios-session`, ...). Packages and modules move as below; sub-packages keep their names and each
JPMS module is named after its package root.

| 2.x | 3.0 |
|---|---|
| `ai.singlr.core` | `com.standardapplied.helios.core` |
| `ai.singlr.session` | `com.standardapplied.helios.session` |
| `ai.singlr.runtime` | `com.standardapplied.helios.runtime` |
| `ai.singlr.gemini` | `com.standardapplied.helios.gemini` |
| `ai.singlr.anthropic` | `com.standardapplied.helios.anthropic` |
| `ai.singlr.openai` | `com.standardapplied.helios.openai` |
| `ai.singlr.onnx` | `com.standardapplied.helios.onnx` |
| `ai.singlr.persistence` | `com.standardapplied.helios.persistence` |
| `ai.singlr.repl` | `com.standardapplied.helios.repl` |
| `ai.singlr.testing` | `com.standardapplied.helios.testing` |
| `ai.singlr.examples.*` | `com.standardapplied.helios.examples.*` |

Migration is mechanical: replace the `groupId` in every Helios dependency, replace `ai.singlr.`
with `com.standardapplied.helios.` in imports, `requires` clauses and
`--enable-native-access=` / `--add-opens` flags, and load the persistence schema from the
classpath location `com/standardapplied/helios/persistence/schema.sql`. There is no relocation
pom and no bridging artifact; the 2.x line stays on `ai.singlr`.

One `ai.singlr` name remains: `helios-persistence` depends on the separately released
`ai.singlr:scim-sql:1.0.0` (JPMS module `ai.singlr.scimsql`), which is not part of this repository
and keeps its published coordinates until that library is migrated.

Persisted data is not migrated: class-name strings recorded by 2.x (for example
`SerializedError.kind`) are opaque and may carry `ai.singlr` names. Table names (`helios_*`) do
not change.

**Every time seam is a `java.time.InstantSource`, never a `java.time.Clock`.** A class that
needs the time takes an `InstantSource` through `withClock(...)` and defaults it to
`Clock.systemUTC()`; it only ever calls `instant()`.

| 2.x | 3.0 |
|---|---|
| `SessionOptions(..., Clock clock, ...)`, `SessionOptions.clock()` returns `Clock` | the component and accessor are `InstantSource` |
| `SessionOptions.Builder.withClock(Clock)` | `withClock(InstantSource)` |
| `SessionContext(String, CancellationToken, Clock)`, `SessionContext.clock()` returns `Clock` | the component and accessor are `InstantSource` |
| `SessionRegistry.Builder.withClock(Clock)` | removed from the public API; retention is measured on the system UTC clock |

A caller that passes a `Clock` compiles unchanged, because `Clock` implements `InstantSource`.
Code that read the returned value as a `Clock` (`options.clock().getZone()`,
`Instant.now(context.clock())`) calls `clock().instant()` instead. Three architecture rules keep
it so: no static wall-clock read outside `core.common.Ids`, `Clock.system*` only as a field's
initial value, and no `Clock` field, parameter or return type.

**Every 2.x compatibility shim, deprecated member and legacy alias is removed.** 3.0 keeps one
way to do each thing; an architecture rule now fails the build on any `@Deprecated` type or member
in main code.

| Removed | Replacement |
|---|---|
| `ModelConfig(...)` 17-argument constructor | `ModelConfig.newBuilder()`, or the canonical constructor with `providerContinuation`, `apiVersion` and `rawOutputCapturePolicy` |
| `ModelConfig.googleSearch()`, `ModelConfig.Builder.withGoogleSearch(boolean)` | `webSearch()`, `withWebSearch(boolean)` |
| `ModelConfig.urlContext()`, `ModelConfig.Builder.withUrlContext(boolean)` | `webFetch()`, `withWebFetch(boolean)` |
| `CostCalculator.Pricing.ANTHROPIC_CACHE_WRITE_MULTIPLIER` | `AnthropicPricing.pricing(model, cachePolicy)`, or `Pricing.ofUsdPerMillion(in, out, cacheWrite, cacheRead)` for custom rates |
| `CostCalculator.Pricing.anthropicCaching(input, output)` | `AnthropicPricing.pricing(model, cachePolicy)`, or `Pricing.ofUsdPerMillion(in, out, cacheWrite, cacheRead)` for custom rates |
| `JsonlEventSink.open(Path)` | `JsonlEventSink.openFull(Path)` (same behaviour) or `openMetadataOnly(Path)` |
| `Trace.totalTokens` summed from the `inputTokens` / `outputTokens` span attributes when a span had no typed usage | record `SpanBuilder.withUsage(Usage)`; a span without typed usage contributes 0 and its token attributes are plain attributes |
| `ModelChunk.MessageStop(stopReason, usage)` | `MessageStop(stopReason, usage, Map.of(), List.of())` |
| `AnthropicModelId.usesAdaptiveThinking()` | `thinkingShape() != ThinkingShape.LEGACY_BUDGET` |
| Anthropic metadata keys `anthropic.thinking` and `anthropic.thinkingSignature`, and the decode fallback that read them | `anthropic.thinkingBlocks`, a JSON array of `{"text", "signature"}` with one entry per thinking block |
| `anthropic.api.ToolDefinition(name, description, inputSchema)` | `ToolDefinition(null, name, description, inputSchema, null)` |
| `OpenAIModelId.supportsXhighEffort()` | `effortSupport() != EffortSupport.STANDARD` |
| `ResultMessage.Success(sessionId, result, usage, cost, duration)` | `Success(sessionId, result, usage, cost, duration, List.of())` |
| `ResultMessage.Refusal(sessionId, refusalText, usage, cost, duration)` | `Refusal(sessionId, refusalText, usage, cost, duration, null)` |
| `UserMessage(text, attachments, null)` read as "no file references" | pass `List.of()`, or use `UserMessage(text, attachments)`; a null `fileReferences` throws `NullPointerException` |
| `repl.sandbox.ExecutionResult` four-, five- and six-argument constructors | `ExecutionResult.newBuilder()`, or the canonical seven-component constructor |

Persisted data is not migrated: a conversation written by 2.x whose assistant messages carry only
the single-block keys `anthropic.thinking` / `anthropic.thinkingSignature` is not read by 3.0.
Such a message is replayed without its thinking block; re-run the conversation on 3.0, or rewrite
the stored metadata to `anthropic.thinkingBlocks`
(`[{"text": <anthropic.thinking>, "signature": <anthropic.thinkingSignature>}]`) before loading it.

**`JvmSandboxConfig` gains a `stopGrace` component**, second after `executionTimeout`: how long a
timed-out snippet has, once stopped, to end before the sandbox exits with code 3. Builder callers
are unaffected (`withStopGrace(Duration)` overrides the 5 s default); canonical-constructor callers
pass `JvmSandboxConfig.DEFAULT_STOP_GRACE` for the previous behaviour.

**Provider clients no longer follow redirects; a 3xx is a provider error.** Every client
`HttpClientFactory.create(...)` builds uses `HttpClient.Redirect.NEVER`. The JDK strips
`Authorization` on a redirect but not `x-api-key`, `x-goog-api-key` or a `ModelConfig.withHeader`
credential, so a 307 from the endpoint or a gateway forwarded the key and the prompt to whatever
origin `Location` named. Every provider's model now reports a 3xx from
`chat` and `chatStream` like any other non-200 status (`ProviderException.statusCode()` carries
it, not retryable); point `baseUrl` at the final endpoint.

| 2.x | 3.0 |
|---|---|
| `HttpClientFactory.create(ModelConfig, HttpClient.Redirect)` | `HttpClientFactory.create(ModelConfig)`, which never follows a redirect; there is no policy to choose |

**Builder steps start with `with`, and the core burn-down moves a few members.** Every public
method on a `*Builder` type that returns that builder is now named `withX(...)`; an architecture
rule fails the build on one that is not. `Tool.execute(Map)`, which substituted
`ToolContext.noop()`, is gone, so every call takes the production path. Binary pinning moves to the
new `core.process` package.
`CommandGrant` and `LocalProcessExecutionProvider` now share one runner, so the provider treats an
interrupt that lands after the wait as a cancellation, as it already did during the wait. Both
remove the per-call working directory before they release the concurrency permit.
`Tool.parametersAsJsonSchema()` returns its `required` list unmodifiable.

| 2.x | 3.0 |
|---|---|
| `tool.execute(arguments)` | `tool.execute(arguments, ToolContext.noop())` |
| `CommandGrant.resolveBinary(spec, pathEnv)` | `core.process.BinaryResolver.resolve(spec, pathEnv)` |
| `SpanBuilder.attribute(k, v)`, `usage(u)`, `cost(c)` | `withAttribute(k, v)`, `withUsage(u)`, `withCost(c)` |
| `TraceBuilder.attribute`, `inputText`, `outputText`, `userId`, `sessionId`, `modelId`, `promptName`, `promptVersion`, `groupId`, `labels` | `withAttribute`, `withInputText`, `withOutputText`, `withUserId`, `withSessionId`, `withModelId`, `withPromptName`, `withPromptVersion`, `withGroupId`, `withLabels` |
| `TraceBuilder.span(name, kind)`, `SpanBuilder.span(name, kind)`, `SpanContainer.span(name, kind)` | `withChildSpan(name, kind)` |
| `ScriptedModel.Builder.thenText(...)`, `thenToolCalls(...)`, `thenRefusal(text)` (each appends the next turn) | `withTextTurn(...)`, `withToolCallsTurn(...)`, `withRefusalTurn(text)` (each still appends the next turn) |
| `SessionOptions.Builder.apply(preset)` | removed with `SessionPreset`; see "A preset takes the model and a path" below |

**A model stream is a closeable pull iterator.** `Model`'s default stream used to call the
blocking `chat` and replay a chunk sequence made up from the finished response, so a session saw
no text until the turn was over and could not cancel a call already in flight. Now
`chatStream(messages, tools[, schema])` returns a `CloseableIterator<ModelChunk>`, the one entry
point: every provider yields each delta as its stream delivers it (each tool call's start and
argument fragments, and a Gemini thought's opening summary, included), nothing is sent before the
first `hasNext()`, a failed turn throws its exception from `hasNext()`, and `close()`, from any
thread, aborts that request (awaiting headers or mid-body) and closes its connection without
touching the shared model; a `hasNext()` blocked on another thread then throws
`CancellationException`, as an interrupt of the reading thread does. `chat` drains the same stream
(`ChunkDrain.drain`). A session reads each turn's stream on a virtual thread of its own, closes it
when the session is cancelled, and waits at most `stopGrace` for the reader. A session's
`QueryEvent.AssistantText` and `AssistantThinking` therefore arrive delta by delta instead of once
per turn, and after a `QueryEvent.TurnRetried` the next attempt streams its answer again from the
start. A stream that ends without its `ModelChunk.MessageStop` fails the turn, and `chat`, with
`IllegalStateException` instead of reading as a plain stop. Every frame of a provider's stream is
liveness: one without content, a block's end included, arrives as a `ModelChunk.KeepAlive`, so an
exhaustive `switch` over `ModelChunk` or `StreamEvent` needs a `KeepAlive` branch. The provider's
per-line `ModelConfig.streamIdleTimeout` (120 s, once the headers arrived) is the one idle clock,
for direct callers and sessions alike: its expiry ends the turn with the provider's exception, not
retried. A model that thinks before it sends its headers (Gemini does) is bounded by
`ModelConfig.responseTimeout`, a retryable failure, and in a session by `maxWallClock` and
cancellation.

| 2.x | 3.0 |
|---|---|
| `CloseableIterator<StreamEvent> chatStream(messages, tools)`, `chatStream(messages)` | `CloseableIterator<ModelChunk> chatStream(messages, tools)`, the one streaming form; `Model.chat(messages, tools)` drains it by default |
| A `Model` implementing only the blocking `chat`; the stream default ran it eagerly and synthesised chunks | implement `core.model.BlockingModel`: its `chat` runs at the stream's first `hasNext()` and is interrupted by `close()`; `BlockingModel.streamOf(call)` does the same for one call |
| `Model.chat(messages)`, `Model.chat(messages, schema)` | `chat(messages, List.of())`, `chat(messages, List.of(), schema)` |
| `Exchange.chat(request)`, `CloseableIterator<StreamEvent> Exchange.stream(request)` | `CloseableIterator<StreamEvent> Exchange.stream(request)`, sending nothing before its first `hasNext()`; `StreamingModel` drains it for `chat` |
| `new ChatExchange<>(providerName, apiName, opener, failure)` | a provider passes `streams::open`, which returns the lazy `SseReader(client, request, apiName, idleTimeout, parser, failure, providerName)`, plus `StreamingModel.Builder.withFailure(failure)`, which maps a failed turn's exception |

Against earlier 3.0 snapshots, which published the stream as a `Flow.Publisher`:

| 3.0 snapshot | 3.0 |
|---|---|
| `Model.chatStream(messages, tools[, schema], CancellationToken)` → `Flow.Publisher<ModelChunk>` | `Model.chatStream(messages, tools[, schema])` → `CloseableIterator<ModelChunk>`; cancel with `close()`; a failure is thrown from `hasNext()` |
| `core.model.IteratorPublisher` | removed: return a `CloseableIterator` |
| `BlockingModel.publisherOf(call, token)` | `BlockingModel.streamOf(call)` |
| `core.provider.Exchange.stream(request, token)` → `Flow.Publisher<StreamEvent>`; `ChatExchange`; `SseReader.open(...)` | `stream(request)` → `CloseableIterator<StreamEvent>`; `ChatExchange` removed, pass `streams::open`; the lazy `SseReader` constructor |
| `SessionLimits.streamIdleTimeout`, `SessionLimits.Builder.withStreamIdleTimeout` | removed: `ModelConfig.Builder.withStreamIdleTimeout` (default 120 s) is the one idle clock |
| `StreamEvent.ThinkingComplete` | removed: a turn's thinking text and signatures are in `MessageStop.metadata()` / `Response.metadata()` (`anthropic.thinkingBlocks`, `gemini.thoughtSignatures`) |
| `QueryEvent.AssistantThinking.signature` | removed (it was always empty); the SSE JSON of the event loses its `signature` field |
| `ModelStreams.of` / `failing` / `stalled` → `Flow.Publisher<ModelChunk>`; `ScriptedModel.Builder.withStreamTurn(Flow.Publisher)` | they return a one-pass `CloseableIterator<ModelChunk>`, and `withStreamTurn` takes one |
| `StreamingModel.chat` interrupted while waiting threw the provider's "Request interrupted" exception | throws `CancellationException`, the interrupt kept |

**The provider model classes are removed; every provider returns a `Model`.** `AnthropicModel`,
`OpenAIModel` and `GeminiModel` were one forwarding surface written three times. Each provider now
assembles a `core.provider.StreamingModel` from its own request builder, stream parser and JSON
binding, and `create(...)` returns it as the `ModelProvider` SPI's `Model`. Requests, responses,
streamed events, errors and validation messages are unchanged. The three classes' constructors were
already package-private, so code reached them only through a provider; code that typed the result
as the provider's class types it as `Model`.

| 2.x | 3.0 |
|---|---|
| `AnthropicModel model = anthropicProvider.create(id, config)` (and with a `CachePolicy`) | `Model model = anthropicProvider.create(id, config)` |
| `GeminiModel model = geminiProvider.create(id, config)` | `Model model = geminiProvider.create(id, config)` |
| `anthropicModel.cachePolicy()`, `anthropicModel.promptCachingEnabled()` | removed: the policy is the one passed to `create(id, config, cachePolicy)`, `CachePolicy.shortLived()` when none is, and `cachePolicy.enabled()` answers whether it caches |
| `geminiModel.apiVersion()` | removed: response metadata `gemini.apiVersion` reports the effective version |

**Annotations move from `PgTraceStore` to the new `PgAnnotationStore`.** `PgTraceStore` keeps
traces and their spans (`store`, `findById`, `list`, `summarize`, `onEvent`). The five annotation
methods move unchanged, with the same SQL, rows and errors, to `PgAnnotationStore`, built from the
same `PgConfig`: `new PgAnnotationStore(pgConfig)`.

| 2.x | 3.0 |
|---|---|
| `pgTraceStore.storeAnnotation(annotation)` | `pgAnnotationStore.storeAnnotation(annotation)` |
| `pgTraceStore.upsertAnnotation(annotation)` | `pgAnnotationStore.upsertAnnotation(annotation)` |
| `pgTraceStore.findAnnotationsBySubject(subjectId)` | `pgAnnotationStore.findAnnotationsBySubject(subjectId)` |
| `pgTraceStore.findAnnotationsBySubjects(subjectIds)` | `pgAnnotationStore.findAnnotationsBySubjects(subjectIds)` |
| `pgTraceStore.listAnnotations(paginate, scimFilter)` | `pgAnnotationStore.listAnnotations(paginate, scimFilter)` |

**`JShellExecutionProvider.singleSandbox` is removed.** It was a second shortcut for the builder,
and its name suggested one sandbox where the provider spawns one per session.

| 2.x | 3.0 |
|---|---|
| `JShellExecutionProvider.singleSandbox(config, snippet)` | `JShellExecutionProvider.newBuilder().withReplConfig(config).withStartupSnippet(snippet).build()`, or `create(config)` when there is no snippet |

**Schema properties and required tool names are ordered types.** The iteration order of
`Map.of` and `Set.of` changes with every JVM start, so a schema or tool choice built from one
reached the model in a different order in every process. The components now name an ordered type,
and an unordered map or set no longer compiles in their place.

| 2.x | 3.0 |
|---|---|
| `JsonSchema.properties` is a `Map<String, JsonSchema>` | a `SequencedMap<String, JsonSchema>`, stored as an unmodifiable copy in the given order; a null name or schema is rejected. Pass a `LinkedHashMap`, or build with `JsonSchema.object().withProperty(...).build()` |
| `ToolChoice.Required.allowedTools` is a `Set<String>` | a `SequencedSet<String>`, stored as an unmodifiable copy in the given order. Construct it through `ToolChoice.required(String...)`, which keeps the varargs order and drops a repeated name |
| `gemini.api.ToolChoiceConfig.allowedTools` and `validated(Set<String>)` | `SequencedSet<String>` |
| `Prompt.variables`, `Prompt.extractVariables(String)` and `Prompt.Builder.withVariables(Set<String>)` are a `Set<String>` | a `SequencedSet<String>` in order of first occurrence, stored as an unmodifiable copy; a null name is rejected. Pass a `LinkedHashSet`, or let `build()` extract them |
| `persistence.mapper.DbTypeMapperProvider.readStringSet` returns a `Set<String>` | a `SequencedSet<String>` in the array's order |
| `ModelIntegrationContract` (core test-jar) declares `model()` | also declares `model(ModelConfig.Builder)`: a subclass completes the builder with its API key and model id and returns a model the caller closes |

**`ThinkingLevel` is replaced by `Reasoning`: exactly what is asked, or a rejection.**
`ThinkingLevel` mixed three decisions (whether the model reasons, at what effort, and whether
the reasoning is returned) and each provider resolved them differently: `NONE` meant thinking
off on one model, the API's default effort on another and the lowest effort on a third, and
`XHIGH`/`MAX` were sent on some models and clamped on others. `ModelConfig.thinkingLevel` and the
enum are removed with no bridge.

| 2.x | 3.0 |
|---|---|
| `core.model.ThinkingLevel` | `core.model.Reasoning`: `Reasoning.Off`, or `Reasoning.Effort(Level, Display)` with `Level` `MINIMAL`..`MAX` and `Display` `HIDDEN` / `SUMMARY` / `PROGRESS` |
| `ModelConfig.thinkingLevel()`, default `NONE` | `ModelConfig.reasoning()`, an `Optional<Reasoning>`, empty by default |
| `ModelConfig.Builder.withThinkingLevel(level)` | `withReasoning(reasoning)`; `null` clears it |
| `AnthropicModelId.ThinkingShape`, `thinkingShape()`, `ThinkingShape.acceptsSamplingParameters()` | `AnthropicModelId.ReasoningRules`, `reasoning()`, with `Off`, `ReasoningSupport` and `Sampling` declarations |
| `OpenAIModelId.EffortSupport`, `effortSupport()` | `OpenAIModelId.ReasoningRules`, `reasoning()` |
| `GeminiModelId.lowestThinkingLevel()` | `GeminiModelId.reasoning()`, a `core.provider.ReasoningSupport` |
| `anthropic.api.ThinkingConfig(type, budgetTokens, display)`, `enabled(int)`, `adaptive()` | `ThinkingConfig(type, display)`, `adaptive(display)`; `enabled` and `budget_tokens` are gone |
| `anthropic.api.OutputConfig.LOW` ... `MAX` | `new OutputConfig("low")` ... |
| `openai.api.ResponsesRequest.ReasoningConfig.of(effort)` (always `summary: "auto"`) | `new ReasoningConfig(effort, summary)`; `summary` null omits it |

- **Absent sends nothing.** With `reasoning` empty no thinking, reasoning or effort field is sent
  and the API's own defaults apply. In 2.x the builder default was `NONE`, so a caller who set
  nothing now gets different behaviour wherever `NONE` sent a field: Opus 5 and Sonnet 5 think
  (they were sent `disabled`), Sonnet 5.5 thinks up front (it was sent `between_tools`), OpenAI
  gpt-5.5 and later reason at the API default `medium` (they were sent `none`, or `low` on
  gpt-6-astra and gpt-6.1-sol), and Gemini 3.x thinks at its default level (it was pinned to its
  lowest). Set `Reasoning.Off`, or an `Effort`, to keep the 2.x request.
- **Exact or throw.** A present `Reasoning` is sent as asked or rejected by the provider's
  `create`, before any I/O, with an `IllegalArgumentException` naming the model, the rejected
  value and every value the model accepts. Nothing is clamped or substituted; `Off` on a model
  that cannot stop reasoning (Fable, Mythos, Opus 5.5, gpt-6-astra, gpt-6.1-sol, o-series,
  Gemini 3.x) throws. A model id the catalogue does not know accepts every value and is sent the
  current shape (Anthropic: adaptive thinking with `output_config.effort`, `Off` as `disabled`;
  OpenAI: `reasoning.effort`, `Off` as `none`); the API judges it. `Display.PROGRESS` has no
  OpenAI or Gemini spelling and throws there.
- **`Display.PROGRESS`** sends `thinking.display: "updates"` on Fable 5 / 5.1, Mythos 5.1, Opus 5.5
  and Sonnet 5.5, and the Anthropic provider adds the `thinking-display-updates-2026-08-18` beta
  to the `anthropic-beta` header itself, after any beta the configured headers already name.
- **Haiku 4.5's invented budgets are gone.** `MINIMAL`..`HIGH` sent `enabled` with 1024 / 4096 /
  10000 / 32000 `budget_tokens` and raised `max_tokens` above the budget; Haiku 4.5 now accepts no
  `Effort`.
- **Sampling parameters are exact or throw too.** 2.x dropped `temperature` and `topP` without a
  word where the model would reject them. They are now sent as set, or rejected at construction
  with the model, the parameter and the rule: Claude 4.7 and later (Opus 4.7 / 4.8 / 5 / 5.5,
  Sonnet 5 / 5.5, Fable, Mythos) reject either whenever it is set; Opus 4.6 and Sonnet 4.6 reject
  `temperature` alongside `Reasoning.Effort` and accept `topP` there only from 0.95 to 1; Opus 4.6,
  Sonnet 4.6 and Haiku 4.5 reject the two together, which the API answers with a 400. On OpenAI,
  gpt-6-astra, gpt-6.1-sol and the o-series reject both always; gpt-6-sol, gpt-6-luna, the gpt-5.6
  family and gpt-5.5 accept them only with `Reasoning.Off` (effort `none`); the gpt-5.4 family
  accepts them unless an `Effort` is set (its default effort is `none`); gpt-4.1 and gpt-4o accept
  them always.
- **One reader of the declaration.** A new architecture rule allows only the `*RequestBuilder`
  classes to read a model's reasoning declaration, so effort and display have one interpretation
  per provider.

What each 2.x level sent, and the `Reasoning` that sends the same now. `E(L, D)` is
`new Reasoning.Effort(Level.L, Display.D)`; "absent" is no `withReasoning` call.

| Provider family | 2.x level | 2.x request | 3.0 |
|---|---|---|---|
| Fable 5 / 5.1, Mythos 5 / 5.1, Opus 5.5 | `NONE` | no `thinking`, no `output_config` (API default effort, reasoning not returned) | absent (`Off` throws) |
| | `MINIMAL`, `LOW` | `adaptive`, `summarized`, effort `low` | `E(LOW, SUMMARY)` |
| | `MEDIUM`, `HIGH`, `XHIGH`, `MAX` | `adaptive`, `summarized`, the same effort | `E(<same>, SUMMARY)` |
| Sonnet 5.5 | `NONE` | `thinking.type=between_tools` | `Off` |
| | `MINIMAL`..`MAX` | as Opus 5.5 | as Opus 5.5 |
| Opus 5, Sonnet 5 | `NONE` | `thinking.type=disabled` | `Off` |
| | `MINIMAL`..`MAX` | as Opus 5.5 | as Opus 5.5 |
| Opus 4.7, 4.8, and an uncatalogued Claude id | `NONE` | no `thinking` | absent (or `Off` on 4.7 / 4.8; `Off` sends `disabled` on an uncatalogued id) |
| | `MINIMAL`..`MAX` | as Opus 5.5 | as Opus 5.5 |
| Opus 4.6, Sonnet 4.6 | `NONE` | no `thinking` | absent or `Off` |
| | `MINIMAL`, `LOW`, `MEDIUM`, `HIGH`, `MAX` | as Opus 5.5 | as Opus 5.5 |
| | `XHIGH` | threw | throws |
| Haiku 4.5 | `NONE` | no `thinking` | absent or `Off` |
| | `MINIMAL`, `LOW`, `MEDIUM`, `HIGH` | `enabled` with `budget_tokens` 1024 / 4096 / 10000 / 32000 | no equivalent: every `Effort` throws |
| | `XHIGH`, `MAX` | threw | throws |
| gpt-6-astra, gpt-6.1-sol | `NONE`, `MINIMAL`, `LOW` | `reasoning: {effort: low, summary: auto}` | `E(LOW, SUMMARY)` |
| | `MEDIUM`..`MAX` | the same effort, `summary: auto` | `E(<same>, SUMMARY)` |
| gpt-6-sol, gpt-6-luna, gpt-5.6 family | `NONE` | `reasoning: {effort: none, summary: auto}` | `Off` (sends `{effort: none}`, no summary) |
| | `MINIMAL`, `LOW` | effort `low`, `summary: auto` | `E(LOW, SUMMARY)` |
| | `MEDIUM`..`MAX` | the same effort, `summary: auto` | `E(<same>, SUMMARY)` |
| gpt-5.5, gpt-5.4 family | `NONE` | `reasoning: {effort: none, summary: auto}` | `Off` |
| | `MINIMAL`, `LOW` .. `XHIGH` | as gpt-5.6 | as gpt-5.6 |
| | `MAX` | clamped to `xhigh` | `E(XHIGH, SUMMARY)`; `MAX` throws |
| o3, o4-mini | `NONE` | no `reasoning` | absent (`Off` throws) |
| | `MINIMAL`, `LOW` | effort `low`, `summary: auto` | `E(LOW, SUMMARY)`; `MINIMAL` throws |
| | `MEDIUM`, `HIGH` | the same effort, `summary: auto` | `E(<same>, SUMMARY)` |
| | `XHIGH`, `MAX` | clamped to `high` | `E(HIGH, SUMMARY)`; `XHIGH` / `MAX` throw |
| gpt-4.1, gpt-4o families | `NONE` | no `reasoning` | absent or `Off` |
| | `MINIMAL`..`MAX` | a `reasoning` field these models do not take | no equivalent: every `Effort` throws |
| an uncatalogued OpenAI id | `NONE` | no `reasoning` | absent |
| | `MINIMAL`, `LOW` | effort `low`, `summary: auto` | `E(LOW, SUMMARY)` (`MINIMAL` now sends `minimal`) |
| | `MEDIUM`, `HIGH` | the same effort, `summary: auto` | `E(<same>, SUMMARY)` |
| | `XHIGH`, `MAX` | clamped to `high` | `E(HIGH, SUMMARY)` (`XHIGH` / `MAX` are now sent as they are) |
| Gemini 3 Flash Preview, 3.1 Flash-Lite, 3.5 Flash, 3.5 Flash-Lite, 3.6 Flash | `NONE`, `MINIMAL` | `thinking_level: minimal` | `E(MINIMAL, HIDDEN)` (`Off` throws) |
| Gemini 3.1 Pro Preview, 3.7 Flash, 3.8 Flash | `NONE`, `MINIMAL` | `thinking_level: low` | `E(LOW, HIDDEN)`; `MINIMAL` throws |
| every Gemini 3.x | `LOW`, `MEDIUM`, `HIGH` | the same level | `E(<same>, HIDDEN)` (`SUMMARY` adds `thinking_summaries: auto`) |
| | `XHIGH`, `MAX` | clamped to `high` | `E(HIGH, HIDDEN)`; `XHIGH` / `MAX` throw |

| 2.x sampling | 3.0 |
|---|---|
| `temperature` / `topP` on a model that rejects them were dropped from the request | thrown at construction: `IllegalArgumentException("Model <id> does not accept <parameter> <rule>.")`; remove the parameter, or set `Reasoning.Off` where the model accepts sampling only without reasoning |
| a NaN or infinite `temperature` / `topP` was sent as the string `"NaN"` / `"Infinity"` | thrown by `ModelConfig`: `IllegalArgumentException("<parameter> must be a finite number, got <value>")` |

**The CodeAct and RLM presets are removed; JShell runs through `Execute` only.** Neither preset
had real use: in live runs the model spent turns discovering how its input was bound, passed only
when it improvised a batched rewrite, and one run ended with an empty `Success` and no `submit`.
`CodeExecutionTool`, an older second way to run JShell, goes with them. The sandbox itself stays
(`repl.sandbox`, its policy verifier and module limits, `ReplSession`, `JShellExecutionProvider`)
and is reached through `Execute` with `Runtime.JSHELL`, plus the custom host functions registered
through `ReplConfig`. Sub-agents, planned for 3.1, replace in-sandbox sub-model fan-out.

| 2.x | 3.0 |
|---|---|
| `repl.codeact.CodeActPreset.typed(...)` | `JShellExecutionProvider` as the session's `ExecutionProvider` (`withExecutionProvider`, which registers `Execute`), and the task's own system prompt; the final answer is the model's last message, through `runBlocking(message, OutputSchema)` for a typed one |
| `repl.codeact.CodeActPreset.withSubLm(...)` (RLM: `predict(...)` / `submit(...)` in the sandbox) | no replacement in 3.0; sub-agents in 3.1 replace sub-model fan-out. A host-side call the snippet needs is a custom `HostFunction` registered through `ReplConfig.Builder.withHostFunction` |
| the rest of `repl.codeact` (`CodeActStrategy`, `RlmStrategy`, `PromptRendering`, `PredictFunction`, `PredictTool`, `SubmitFunction`, `SubmitTool`, `SubmitValidation`, `SubmittedValueHolder`, `OnSubmitStopHook`, `RequireExecuteCodeHook`, `InputFunction`, `OwnedExecutionProvider`) and the package's JPMS export | removed with the presets |
| `repl.CodeExecutionTool` | `Execute` with `runtime: JSHELL` through `JShellExecutionProvider` |
| `repl.InputBindings` | removed; seed per-session state with `JShellExecutionProvider.Builder.withStartupSnippet` |
| `repl.SandboxBudgetExceededException` | removed; nothing threw it |
| the `predict`, `submit`, `getInput`, `fetch` and `query` statics of `HostBridge` | removed; `HostBridge.__call`, which every synthesized host-function wrapper calls, stays. A custom host function may now be named `predict`, `submit`, `fetch`, `query` or `getInput` and gets a typed wrapper |
| `HostFunctionRegistry.RESERVED_NAMES` held seven names | holds `__call` only |
| `sandbox.ExecutionResult.submitted()`, `hasSubmittedValue()`, `success(String, Object)`, `Builder.withSubmitted(Object)`, and the canonical constructor's `submitted` component | removed: no snippet can submit. The execute reply no longer carries `submitted` |
| `ReplConfig.maxOutputCharsToModel`, `DEFAULT_MAX_OUTPUT_CHARS_TO_MODEL`, `Builder.withMaxOutputCharsToModel(int)` and the canonical constructor's component | removed: only `CodeExecutionTool` read it |
| `ReplSession.config()` | removed: only `CodeExecutionTool` read it |
| `Tool.resultCompactor()`, `Tool.DEFAULT_RESULT_COMPACTOR`, `Tool.Builder.withResultCompactor(Function)` and the canonical constructor's `resultCompactor` component | removed: no Helios code read it, and context compaction summarises history without it. `Tool(name, description, parameters, executor)` is the canonical constructor |
| `examples/codeact-demo`, `examples/rlm-demo` | removed |

**A typed `runBlocking` never returns `null`.** `AgentSession.runBlocking(message, schema)` on a
run that ends as `Success` with a blank result (a model that ends its last turn with no text) now
throws `IllegalStateException("session ended with Success but produced no result to parse as
<type>")` instead of returning `null`. The untyped `runBlocking(message)` still returns that
`Success`, and `StructuredContentParser.parse` still returns `null` for blank input.

**A sandbox execute reports its timeout, and an RPC timeout has its own type.**
`repl.sandbox.ExecutionResult` gains a `boolean timedOut` component after `exitCode` (the
3.0 canonical constructor, after the removals under the secret registry entry below, is
`ExecutionResult(stdout, stderr, exitCode, timedOut, duration)`);
`ExecutionResult.Builder.withTimedOut(boolean)` sets it.
`RpcChannel.RpcException` is now `sealed`, with `RpcChannel.RpcTimeoutException` as its only
subclass: a call whose response does not arrive in time throws it, with the same message. New:
`RpcChannel.call(method, params, timeout)`.

**One secret registry per session, applied once where tool output enters the session.** A session
redacts every tool result's output once, against its own `SecretRegistry`, before the
`QueryEvent.ToolResult`, `PostToolUse` hooks and the history see it, and redacts the messages of
every error it reports. No tool or execution provider redacts its own output any more, and the
REPL's bindings listener is gone.

| 2.x | 3.0 |
|---|---|
| Each component held its own `SecretRegistry`; sessions had none | `SessionOptions` gains a `secretRegistry` component and `Builder.withSecretRegistry(SecretRegistry)` (default: a new empty registry). `SessionContext` gains a `secrets` component, `SessionContext(sessionId, cancellation, clock, secrets)`; `SessionContext.forTesting` uses a new empty registry. Tools reach it as `ToolContext.sessionContext().secrets()` |
| `ReadTool.binding(workspace, tracker, Redactor)`, `GrepTool.binding(workspace, Redactor)` | removed; `ReadTool.binding(workspace, tracker)` and `GrepTool.binding(workspace)`, whose output the session redacts |
| `LocalProcessExecutionProvider.defaultPosix(SecretRegistry)`, `Builder.withSecretRegistry`, `secretRegistry()` | `defaultPosix()`; the other two removed. Stdout and stderr are returned raw |
| `JShellExecutionProvider.Builder.withSecretRegistry`, `secretRegistry()` | removed. Stdout and stderr are returned raw |
| `ToolResult(success, output, data, attachments)`, `ToolResult.data()`, `ToolResult.success(String, Object)` | `ToolResult(success, output, attachments)`; `data` is gone, since an object cannot be redacted. `ExecuteTool` and the `CommandGrant` tool return their text only, which already names exit code, timeout and truncation |
| `session.execution.ExecutionResult.secretRedactionCounts()`, `totalRedactions()`, `Builder.withSecretRedactionCounts`; `CommandGrant.InvocationResult.redactionCounts()`, `totalRedactions()`; `RedactionResult.mergeCounts` | removed; `QueryEvent.ToolResult` gains `Map<String, Integer> redactions`, the per-secret counts of the session's pass over that result. `ExecutionResult(exitCode, stdout, stderr, duration, timedOut)` |
| `QueryEvent.ToolResult(sessionId, turnIndex, timestamp, call, result)` | `QueryEvent.ToolResult(sessionId, turnIndex, timestamp, call, result, redactions)` |
| `CommandGrant.Builder.withSecretRegistry` optional (a private registry by default) | required: `build()` without it throws `IllegalStateException`. The tool refuses to run, starting no process, in a session whose registry is a different instance |
| `CommandGrant.invoke(List)`, `CommandGrant.InvocationResult` and `CommandGrant.RejectedException` public, output redacted | package-private, output raw; `InvocationResult.duration` is removed |
| `ProcessOutcome(exitCode, stdout, stderr, timedOut, truncated, elapsed)` | `ProcessOutcome(exitCode, stdout, stderr, timedOut, stdoutTruncated, stderrTruncated, elapsed)`; `truncated()` is derived. `BoundedProcess.Builder.withSecretRegistry` names the secrets whose trailing start a cut stream drops, through the new `Redactor.trimPartialSecret` |
| `repl.SandboxBindingsListener`, `ReplConfig.sandboxBindingsListener`, `maxBindingValueChars`, `maxBindingSnapshotChars`, `DEFAULT_MAX_BINDING_VALUE_CHARS`, `DEFAULT_MAX_BINDING_SNAPSHOT_CHARS` and their builder methods; `sandbox.ExecuteParams`; `Sandbox.execute(request, ExecuteParams)`; `sandbox.ExecutionResult.bindings()` and `Builder.withBindings`; the `captureBindings`, `maxBindingValueChars` and `maxBindingSnapshotChars` execute parameters and the reply's `bindings` | removed: the listener received unredacted output, served every session and carried no session id, and nothing registered one |
| `sandbox.ExecutionResult.executedCode()`, `Builder.withExecutedCode`; `ReplConfig.maxExecutedCodeChars`, `DEFAULT_MAX_EXECUTED_CODE_CHARS`, `Builder.withMaxExecutedCodeChars`; `ReplSession.history()` | removed: only the listener's observers used them. `sandbox.ExecutionResult(stdout, stderr, exitCode, timedOut, duration)`; `ReplConfig(sandboxFactory, executionTimeout, maxConcurrentSessions, hostFunctions)` |

**A tool call's outcome is a typed component of `ToolResult`.** `core.tool.ToolOutcome` is a
sealed interface with six records: `Succeeded`, `Failed`, `Threw(exceptionType)`,
`TimedOut(limit)`, `Rejected(by, reason)` and `NotFound`. Count or route on it, never on the result
text. Each `ToolResult` factory sets the outcome and writes the model-facing text, so the two cannot
drift.

| 2.x | 3.0 |
|---|---|
| `ToolResult(boolean success, output, attachments)`, `success()` | `ToolResult(ToolOutcome outcome, output, attachments)`, `succeeded()`. New framework factories `threw(toolName, cause)`, `timedOut(toolName, limit)`, `notFound(toolName)`, `rejected(by, reason)`, and `withOutput(text)` |
| `ToolResult.failure(String, Exception)` | removed: return `failure(text)`, or throw and Helios reports `Threw` |
| A tool's thrown exception: `Tool execution failed: <msg>`; a throwable from dispatch, including a call cancelled before it ran: `tool dispatch failed: <msg>` | both `Threw`, text `tool '<name>' threw: <msg>` (the simple class name when the message is null) |
| A `PreToolUse` `Block`: `blocked by hook: <reason>` | `Rejected(by, reason)`, text `blocked by <by>: <reason>`, where `by` is the name `ToolBlocked` carries. A permission denial is `Rejected("Permission", reason)` too |
| A `PreToolUse` `Inject`: `tool skipped: hook injected user message` | `Rejected(by, "hook injected a user message")`, text `blocked by <by>: hook injected a user message` |
| A `PostToolUse` `MutateResult` became a successful result with no attachments | the result keeps its outcome and attachments and replaces only its output: a hook changes what the model reads, not what happened |
| SSE `ToolResult` event: `"result":{"success":true,...}` | `"result":{"outcome":{"kind":"Succeeded"},...}`; the outcome object carries `kind` (the record's simple name) and the record's components, e.g. `{"kind":"TimedOut","limit":"PT1M"}`. The runtime adds the serializer to whatever `ObjectMapper` it is given |
| JSONL `ToolCallCompleted`: `success` | `success` plus `"outcome":"<kind>"` |

**The dispatcher owns a tool call until its work actually ends.** The loop runs tool calls one at a
time, and each runs on a virtual thread of its own with its own cancellation token, linked to the
session's, and one deadline that covers the tool's start and run; past it, the caller waits only
for the stop grace below. A tool not started by the deadline, or before the session is cancelled,
never starts. The call's token is cancelled on a thread of its own, so a callback that blocks or
throws holds up neither the loop, the session's cancellation nor the worker's interrupt. Every
`Error` of a call's work, a callback's and the tool's own, is recorded by the session's ending
(`SessionState.ending()`), which ends the session (see below). Java offers no safe way to stop
arbitrary code, so a tool that ignores both its token and interruption runs on after its call is
answered. While a call runs on after its caller stopped waiting, at its deadline, on session
cancellation or on the caller's interrupt, every new call, of any category, is refused at once and
its tool never starts, so a session leaks at most one call. Closing the session cancels and
interrupts every call still running.

| 2.x | 3.0 |
|---|---|
| A timed-out call was interrupted and reported `TimedOut` at once, and its category permit was given back while the tool could still be running. The tool's `ctx.cancellation()` was the session's token, which the timeout never cancelled; permit wait and result wait ended only on interruption | At the deadline the call's token is cancelled and its worker interrupted. A tool that never started is `TimedOut`, and so is one that stops within the new `SessionLimits.stopGrace` (default 5 s, `Builder.withStopGrace`). One still running after it is the new `ToolOutcome.Unknown(limit)`, text `tool '<name>' timed out after <limit> and did not stop; it may still be running and its effects may still land` (`ToolResult.unknown(toolName, limit)`; SSE `{"kind":"Unknown","limit":"PT2M"}`). Session cancellation during either wait, or during the stop grace, ends the call, woken by the call's link to the session's token |
| `ToolContext(sessionContext, deadline)`, `ToolContext.of(sessionContext, deadline)`; `ctx.cancellation()` was `sessionContext().cancellation()` | `ToolContext(sessionContext, cancellation, deadline)`; `ctx.cancellation()` is the call's own token. Session-scoped cleanup registers on `ctx.sessionContext().cancellation()`, which one call's timeout never cancels. `ToolContext.of` is removed |
| `ToolOutcome.Rejected(by, reason)` came only from a hook | Also from the dispatcher: a call refused while an earlier call runs on after its caller stopped waiting is `Rejected("ToolDispatch", "tool '<name>' did not stop; no tool runs until it ends")`, text `blocked by ToolDispatch: tool '<name>' did not stop; no tool runs until it ends` |
| A tool executor that returned `null` ended the session with `ErrorDuringExecution` (`NullPointerException` in the tool round) | `Threw("java.lang.NullPointerException")`, text `tool '<name>' threw: executor returned null`, and the session goes on. `ToolExecutor.execute` documents its result as non-null |
| A `ScheduledExecutorService` deadline cancelled by the session (a stream-idle re-arm) stayed queued until its delay passed | the session holds no scheduler: its wall clock is a virtual thread that ends with the session |
| A tool that ignored its token and interruption ran on past its timeout while later calls ran beside it, any number at a time | While such a call runs on, `stopGrace` past its deadline or after its caller stopped waiting, every later call of the session, of any category, is `Rejected` until it ends: a `READ` tool that never stops now blocks the rest of the session's tool calls |

**`ConcurrencyLimits` is removed; the steering queue's capacity is a `SessionOptions` component.**
The loop runs one tool call at a time, so the three tool caps bounded nothing a session did beyond
letting stuck calls pile up, which the rule above bounds at one.

| 2.x | 3.0 |
|---|---|
| `ConcurrencyLimits(maxConcurrentToolCalls, maxConcurrentFileWrites, maxConcurrentExecutions, maxQueuedUserMessages)`, `SessionOptions.Builder.withConcurrencyLimits(limits)`, `SessionOptions.concurrency()` | `SessionOptions.Builder.withMaxQueuedUserMessages(int)` (default 256; `IllegalArgumentException("maxQueuedUserMessages must be positive, got <n>")` below 1) and `SessionOptions.maxQueuedUserMessages()`, which sizes the steering queue behind `SteeringQueueFullException`. The three tool caps are removed with no replacement |
| `ToolDispatch.limits()`, `availableToolCallPermits()`, `availableFileWritePermits()`, `availableExecutionPermits()` | removed |

**A host `Error` ends the session as itself, and the session ends in a bounded teardown.** Every
`Error` caught on a thread the session runs work on (the loop, a tool's worker, a callback of the
session's token or of a tool call's, the model stream's signals and its cancel, the compaction
summary call, `onSessionStart` and `onSessionEnd`) is recorded by one sink, the session's ending
(`SessionState.ending()`, `SessionEnding`). Recording one cancels the session with the reason
`host error <class>`, so the loop winds down as on any cancellation and no further model or tool
call is made. The result settles with the first recorded `Error`, as the same instance, every later
one added to it as suppressed once; an `Error` recorded after the result settled is logged at
`SEVERE` on the `AgentSessionImpl` logger. A loop thread that ended with an `Error` then throws the
result's `Error` to its uncaught-exception handler; nothing promises that the JVM stops, the host
decides. A thread the session does not own (a model publisher's worker after its stream ended) is
outside the sink: its `Error` reaches that thread's uncaught-exception handler. Every end of a
session, a natural one included, then runs one teardown in the reverse order of what the session
acquired: it cancels the session, which cancels and interrupts every tool call still running, and
waits for those calls to end; it calls `onSessionEnd` and waits for it to return; it ends the
event streams without waiting for their readers. The waits share one deadline, the new
`SessionLimits.stopGrace` (default 5 s, `Builder.withStopGrace`) from the teardown's start, the same
grace a timed-out tool call is given to stop. A tool call or
`onSessionEnd` still running at the deadline is logged as a `WARNING` on the `AgentSessionImpl`
logger and left running, never treated as ended: a tool that ignores both its token and interruption
may still run, and its effects land, after the result settles.

| 2.x | 3.0 |
|---|---|
| An `Error` thrown by the model became a `FinishReason.ERROR` turn and an `ErrorDuringExecution` terminal; a tool's became a `ToolOutcome.Threw` result and the turn went on; one from a `FaultTolerance` timed operation was wrapped in a `RuntimeException`; one from the compaction summary call was wrapped and the compaction skipped; one the execution provider's future failed with became an `Execute: provider failed` tool result | Each is recorded as above and fails `result()` as the same instance: `dispatch` (even once the session it ended was cancelled), `FaultTolerance`, the compaction and `ExecuteTool` rethrow it as itself, and a model turn that ends with one rethrows it, so a model's `Error` leaves the loop as a tool's or a hook's does, with no `LoopEnded` event. An `Error` that `onSessionStart`, `onSessionEnd`, the model stream, a context compactor, the token counter or the compaction summary call throws as the cause of a `CompletionException` or `ExecutionException`, as `join` or `get` on a failed future does, counts as that `Error` (`SessionEnding.failWith`). An `Error` a `BlockingModel` call throws after its stream was closed reaches the stream's reader, which records it too. `ErrorDuringExecution` and `Threw` come only from an `Exception`; a hook's, a tool's or a cancellation callback's `CompletionException` stays one even when it carries an `Error` |
| An `Error` from `onSessionEnd` was thrown on the loop thread, or from `close()` for a session never started, while `result()` completed with the terminal; a teardown step that threw skipped the steps after it | `result()` fails with the error, after every step has run; `close()` never throws. Any other exception of `onSessionEnd` is still only logged, and rides on a recorded `Error` as suppressed |
| `onSessionEnd` ran on the loop thread with no bound, and nothing waited for tool calls still running; a run that ended on its own left the session's token uncancelled | the teardown above |
| `onSessionStart` ran on the creating thread, before the rest of the session was built and with no bound, so a provider that never returned kept `AgentSession.create` from returning; a `null` outcome threw `NullPointerException` from `AgentSession.create`, and so did an `Error`; a refused start kept its event executor and scheduler until `close()` | `onSessionStart` is the last step of construction and runs on a thread of its own, waited for `SessionLimits.startTimeout` (default 60 s, `Builder.withStartTimeout`). A `null` outcome is a refusal (`ErrorProviderUnavailable`, reason `onSessionStart returned null`), and so is a start that has not returned by the timeout or when the creating thread is interrupted (`onSessionStart did not return within <timeout>`, `interrupted while waiting for onSessionStart`); an `Error` fails `result()`. Either way, and on a refusal, the event streams are ended before the result settles, `onSessionEnd` is never called and `isStarted()` is `true`. What the provider does after the session stopped waiting is logged; a late acceptance is ended by the session's teardown: its token is cancelled and `onSessionEnd` is waited for within `stopGrace`, and reported if it does not return |
| `SessionLimits` had eight components; the event publisher drained for a fixed 5 s | `SessionLimits(maxTurns, maxBudgetMicroUsd, maxWallClock, toolTimeoutDefault, stopGrace, maxContextTokens, streamRetryPolicy, startTimeout)`; the drain waits `stopGrace` |
| `close()` cancelled the session's token on the caller's thread: a cancellation callback that threw an `Error` escaped `close()` and, for a session never started, left `result()` pending and the executors running; one that blocked blocked `close()`, and the wall-clock deadline ran callbacks on the scheduler's thread | the session is cancelled through the new `CancellationToken.cancelAsync(reason, onError)`: the token is cancelled when it returns, its callbacks run on a virtual thread of their own, each callback `Error` goes to `onError` (the session's ending) and the remaining callbacks still run, and the returned future completes once they all ran; the teardown waits for it within `stopGrace`. A turn reading the model stream, and the retry back-off between its attempts, also read the token as they wait, so a callback that blocks before their own cannot keep a closed session's stream open or its loop asleep |
| A turn cancelled the model stream's subscription on the thread that ended it, with no bound, so a `cancel` that blocked kept the session from ever settling; an exception it threw on an idle timeout was lost on the scheduler | `cancel` runs on a thread of its own, waited for within `stopGrace`; one still running is interrupted and logged as a `WARNING` (`the stream is not known to be closed`), and the turn ends. An `Error` it throws is recorded whenever it is thrown; an exception is logged |
| A `SessionLimits` duration over `Long.MAX_VALUE` nanoseconds (about 292 years) was accepted and overflowed when a session used it | `SessionLimits` rejects it with `IllegalArgumentException` |
| `send` and `interrupt` were accepted after the loop ended with an `Error` | they throw `IllegalStateException("session is terminal")` once `result()` has completed |

**A provider stream succeeds only at its provider's terminal event.** A stream that ended without
one used to complete with finish reason `STOP`: a dropped connection after some text was a
finished answer. Now every stream ends in exactly one `Done` or `Error`. `Done` comes only from
Anthropic's `message_stop` (with a `stop_reason` Helios knows), OpenAI's `response.completed`
(status `completed`) or `response.incomplete` (status `incomplete`), and Gemini's `interaction.completed` (status
`completed`, `requires_action` or `incomplete`). Anything else ends in an `Error`, which carries what
the stream produced before it failed.

| 2.x | 3.0 |
|---|---|
| `StreamEvent.Error(message, cause)`, `Error(message)` | `Error(message, cause, partial)`: `partial` is the `Response` so far (text, completed tool calls, usage, thinking, citations, metadata), finish reason `ERROR` |
| A body that ended without the terminal event completed with `STOP` | `Error` "Stream ended before its terminal event", cause `EOFException`; the blocking `chat` throws a retryable `TransientStreamException` |
| A malformed event or a provider `error` event yielded an `Error`, and the stream read on to a `Done` | the `Error` ends the stream |
| Tool arguments that were not a JSON object reached the tool as `{"_raw": "..."}` (Gemini: the step's start arguments, or `{}`; a Gemini step that started with a JSON string holding an object took that object) | the stream ends in an `Error` naming the tool and the parse failure; the call is never dispatched. A Gemini step's start arguments follow the same rule, so a JSON string is an `Error` too |
| A terminal event that arrived while a tool call was still open completed without that call | `Error` naming the open call |
| An unknown Anthropic `stop_reason` or a missing one finished `STOP` | `Error` |
| Anthropic usage took input and cache tokens from `message_start` only, so a server-tool turn reported the input of its first sampling step | each count `message_delta` carries replaces the last; its counts are cumulative, so the turn reports the input of every server-tool step |
| OpenAI `response.incomplete` was ignored, so the turn finished `STOP` with no usage | `Done` with `LENGTH` (`CONTENT_FILTER` for `content_filter`), the usage, and `openai.incompleteReason` metadata; `response.completed` with any status but `completed`, and `response.incomplete` with any but `incomplete`, is an `Error` |
| OpenAI reasoning summary deltas were read from `text`, so the thinking was `null` | read from `delta`; a part that streamed no delta completes as one delta of its `text`, and the parts join with a blank line |
| An OpenAI refusal was dropped and the turn finished `STOP` | `REFUSAL`, its text as `Response.REFUSAL_EXPLANATION_KEY` |
| A provider's in-stream error message held the raw event (OpenAI's `response.failed`: the request's `instructions` and `tools` included; Anthropic's `error` event); Gemini dropped a string `code` | one shape for every provider, built by `core.provider.StreamErrorMessage`: `<summary>: <code>: <message>`, a missing part left out (`API stream error: overloaded_error: Overloaded`, `API response failed: server_error: ...`, `API error: UNAVAILABLE: ...`); the usage `response.failed` reports stays on the partial |
| OpenAI's in-stream `server_error` and `rate_limit_exceeded` (an `error` event or `response.failed`) ended in a non-retryable `OpenAIException` | a retryable `TransientStreamException`, as Anthropic's `overloaded_error` already was; every other code stays non-retryable |
| Gemini `interaction.completed` was ignored; status `failed` or `budget_exceeded` finished `ERROR` inside a `Done` | `interaction.completed` ends the stream, judged by the status its own interaction carries, never one from an earlier status update; `failed`, `cancelled`, a missing status and any other is an `Error` |
| An Anthropic server-tool input that was not a JSON object was echoed back as `{"_raw": "..."}` | the stream ends in an `Error` naming the tool and the parse failure |
| SSE framing took each `data: ` line (exactly one space) as one event | events end at a blank line; `data` with or without the space, several `data` lines joined by a newline, CR, LF or CRLF, comments ignored, one byte order mark at the start of the body dropped; an event cut off before its blank line is dropped; a line or an event over 16 MiB ends the stream in an `Error` |
| `SseReader.Parser.finished()` and `complete()` | removed; a `Done` or `Error` from `parse` ends the stream, and `partial()` gives what a cut-off stream produced |
| `ToolCall.Builder.withArguments` rejected a `null` value | a JSON `null` argument stays a `null` value (`core.common.JsonSnapshot`); `HookOutcome.MutateArgs`, both `QueryEvent.ToolMutated` inputs and `HeliosEvent.ToolCallStarted.args` keep it too |

**A tool call is a validated, deeply immutable snapshot of a JSON object.** The call a hook
authorises is now the call the tool executes, the events record and a store persists: its
arguments are copied once, all the way down, where they enter (`ToolCall`, `HookOutcome.MutateArgs`,
both `QueryEvent.ToolMutated` inputs, `HeliosEvent.ToolCallStarted.args`),
by the new `core.common.JsonSnapshot.ofObject(name, map)`. A JSON value is `null`, a `Boolean`, a
`String`, a `Byte`, `Short`, `Integer`, `Long`, `BigInteger` or `BigDecimal` (not a subclass), a
finite `Float` or `Double`, a `List`, or a `Map` with `String` keys; a number is kept as the same
instance. The limits, each within Jackson's default parser limits, are `MAX_DEPTH` (64 nested
containers), `MAX_VALUES` (100,000), `MAX_STRING_LENGTH` (16 MiB), `MAX_KEY_LENGTH` (50,000) and
`MAX_NUMBER_DIGITS` (1,000 digits written out in full, so `1E+999` is accepted and `1E+1000`
rejected).

| 2.x | 3.0 |
|---|---|
| `new ToolCall(id, name, arguments)` accepted a `null` or blank id or name and kept the caller's map; `ToolCall.Builder` copied only the top level, so a nested list or map stayed the caller's and could change after construction | the canonical constructor and the builder validate alike: a `null` id, name or arguments throws `NullPointerException`, a blank id or name `IllegalArgumentException`, and the arguments are a deep, unmodifiable snapshot. `ToolCall.Builder.withArguments(null)` no longer means no arguments: leave it unset, or pass `Map.of()` |
| Tool arguments could hold any Java object, a non-`String` key, `NaN` or an infinity, or themselves | `IllegalArgumentException` naming where, such as `arguments.items[2] is a java.io.File; ...`. A `PreToolUseHook` whose `MutateArgs` is rejected has thrown, so the hook counts as `Continue` and the call runs as it was |
| Provider-decoded arguments past a `JsonSnapshot` limit (nested deeper than 64, more than 100,000 values) reached the tool | the stream ends in an `Error` naming the tool, `Arguments of tool call '<name>' are rejected: <where> <why>`; the call is never dispatched |
| A provider tool call without an id (Anthropic `tool_use` without `id`, OpenAI `function_call` without `call_id`, Gemini `function_call` step without `id`) came back from `chat` as a `ToolCall` with a `null` id, while `chatStream` ended in a bare `NullPointerException` | both end in the provider's exception, `Tool call '<name>' streamed without an id`, through the new `core.provider.ToolCallStarts`; a call without a name ends in `A tool call streamed without a tool name`, where a terminal event used to name it `'<unnamed>'` |
| `OrderedMaps.copyOfKeepingNullValues(map)` | removed; `JsonSnapshot.ofObject(name, map)` |

**Every runtime route requires an authenticated identity and acts only on its caller's own
sessions.** `RuntimeServer` and `AgentHttpService` authenticate every request through one
`runtime.Authenticator` before they read its body, look up a session, subscribe or queue work. A
session is owned by the `runtime.Principal` (tenant, subject) that created it; any other principal
gets the same `404` as an unknown id. To keep an externally bound runtime working, configure
`RuntimeServer.Builder.withAuthenticator(...)`: behind an authenticating reverse proxy that is
`TrustedProxyAuthenticator.newBuilder().withTrustedProxy(proxyAddress).build()`, which reads
`X-Authenticated-Tenant` and `X-Authenticated-Subject` only from that proxy's connections (the
proxy must strip client-supplied copies); otherwise an `Authenticator` over the deployer's token
or certificate verifier. An `Authenticator` declares its challenge (`challenge()`, such as
`Bearer realm="helios"`), which every `401` carries in `WWW-Authenticate`; `TrustedProxyAuthenticator`
challenges with `TrustedProxy realm="helios"` unless `Builder.withChallenge(...)` names another,
and a value that is not an RFC 9110 challenge fails construction. A loopback-bound runtime may
instead call `withoutAuthentication()`. There is no unauthenticated external bind.

| 2.x | 3.0 |
|---|---|
| `RuntimeServer.Builder.build()` served unauthenticated on any host | `build()` throws `IllegalStateException` unless `withAuthenticator(...)` or, for a loopback host only, `withoutAuthentication()` was called; a non-loopback host without an authenticator is refused with a message naming the host and the address it resolved to. The host is resolved once and the server binds that address. An unresolvable host throws `IllegalArgumentException` |
| `new AgentHttpService(registry, optionsFactory, objectMapper, eventsPathPrefix)` | `new AgentHttpService(registry, optionsFactory, authenticator, eventsPathPrefix, limits)`, with no `ObjectMapper` (the wire format is fixed, see below), whose `limits` is a `RequestLimits(maxRequestBytes, maxConcurrentRequestsPerPrincipal)` (`RequestLimits.defaults()`: 256 KiB and 32), and whose `optionsFactory` is a `Function<SessionRequest, SessionOptions>` given the generated session id and the creating `Principal`; there is no unauthenticated `AgentHttpService` |
| `RuntimeServer.Builder.withOptionsFactory(Function<String, SessionOptions>)` | `withOptionsFactory(Function<SessionRequest, SessionOptions>)`; `SessionRequest(sessionId, owner)` gives the factory the principal that will own the session, so it can configure a session per tenant or subject |
| Any request reached any session by id | `401 {"error":"unauthenticated"}`, every one with `WWW-Authenticate` set to `Authenticator.challenge()`, for a missing, malformed, expired, revoked or forged credential, `503 {"error":"authentication unavailable"}` when the authenticator throws (logged by exception type only), `404 {"error":"session not found"}` for another principal's session |
| An SSE stream and a result long poll outlived the client's credential | an identity holds for `Authentication.lifetime()` or until `Authentication.revoked()` completes; then an open SSE stream closes without a further event, a pending long poll, or one whose result is still serializing, answers `401`, and a message or interrupt whose body upload, or a create whose options factory, outlived the identity answers `401` and does nothing. `revoked` is a `CompletableFuture<Void>` that a verifier shares across every authentication of one credential; a request watches it only while it runs |
| `RuntimeServer` installed every Helidon `ServerFeature` found on the classpath through service discovery | no discovered feature is installed, so none can mount a route the `Authenticator` does not see; mount `AgentHttpService` on your own `WebServer` to combine it with Helidon features |
| `SessionRegistry.create(options)`, `get(id)`, `close(id)` | `create(options, owner)`, `find(id, requester)`, `close(id, requester)`; `find` and `close` act only for the owner |
| An options factory returning options for another session id was logged and its id used | `500 {"error":"session options factory failed"}` and no session is created, so a factory cannot attach a caller to another user's session; a factory returning `null` gets the same answer |
| The `500` bodies for a failed options factory and a session that terminated abnormally quoted the exception message, and any other exception escaping a route reached the client as Helidon's default `500` body, its message | fixed messages, `session options factory failed`, `session terminated abnormally` and, for any other failure in a route, `internal server error`; the exception is logged |

**The runtime admits sessions and requests against finite bounds.** `SessionRegistry` takes
capacity atomically before the options factory runs, under two hard caps that count every session
held, finished ones included: per `Principal` and across the registry. Room for a create comes only
from evicting the same principal's oldest terminal session, never another principal's. A session
not running, terminal or never started, is swept after one retention, and `RuntimeServer` runs the
sweep itself. Each principal has a bounded number of requests in flight, and a `RuntimeServer` a
bounded number in all. Every refusal is immediate, with a fixed body; the runtime's own `429` and
`503` also carry `Retry-After: 5`.

| 2.x | 3.0 |
|---|---|
| No session, body or request limit by default; a full registry, an evicted session of another owner or a full steering queue answered `500` or `409` | Defaults: 16 sessions per principal and 256 per registry, finished ones included (`SessionRegistry.Builder.withMaxSessionsPerPrincipal`, `withMaxSessions`); a session not running, terminal or never started, kept 15 minutes (`withRetention`); request bodies up to 256 KiB and 32 requests in flight per principal on every route, open event streams and waiting result polls included (`RequestLimits(maxRequestBytes, maxConcurrentRequestsPerPrincipal)`, through `RuntimeServer.Builder.withRequestLimits` or the new last `AgentHttpService` constructor argument); 512 requests in flight per `RuntimeServer`, one more answered by Helidon's own `503`, without `Retry-After`, and its connection closed (on a `WebServer` of your own, Helidon's `maxConcurrentRequests`); the sweep runs every 30 seconds. A create over a principal's cap answers `429 {"error":"session quota reached"}`, over the registry's `503 {"error":"server at session capacity"}`, whatever other principals hold; a request beyond the principal's requests in flight `429 {"error":"too many requests in progress"}` before any session lookup or body read, its slot held until the response ends even if its session is deleted, and by a revoked stream until its event delivery under way finishes; a full steering queue `429 {"error":"session message queue full"}`, where it was `409`; a body over the bound `413` (its body below). A deleted session that is still at work keeps its capacity until its loop stops |
| `SessionRegistry.create` checked `withMaxSessions` outside any lock, so concurrent creates passed it, and evicted the oldest terminal session of any owner | `create(options, owner)` takes capacity atomically before the factory runs and gives it back if the factory fails or the id is a duplicate; there is no public reservation. A refusal throws `SessionCapacityException`, whose `scope()` is `PRINCIPAL` or `SERVER` |
| `SessionRegistry.withFactory(Function)` | `SessionRegistry.newBuilder().withFactory(f).build()` |
| `RuntimeServer.registry()` | removed; keep the `SessionRegistry` you passed to `withRegistry` |
| `SessionRegistry.purgeTerminalOlderThan(Duration)` | `sweep()`, using the registry's one retention |
| `SessionRegistry.sessionIds()` | removed; `size()` remains |
| A full steering queue threw `IllegalStateException` from `AgentSession.send` and `interrupt` | `SteeringQueueFullException`, a subclass, so a host tells backpressure from a closed or terminal session |
| `AgentSession.close()` on a session never started completed `result()` before `ExecutionProvider.onSessionEnd` ran | `result()` completes after `onSessionEnd` returns, as after a run, so a registry that frees capacity on the result never frees it while the provider still holds the session |
| `AgentSession` had no way to tell an unused session from a working one | `AgentSession.isStarted()`; an implementation outside Helios adds it |

**No body byte is ever parsed as a request.** On a kept-alive HTTP/1.1 connection Helidon either
drained a body the route had refused, outside every bound, or, when the client sent `Expect:
100-continue` and no `100 Continue` went out, parsed the bytes the client sent next as a new
request: a request smuggled in a refused one's body ran with identity headers of the client's
choosing, which a `TrustedProxyAuthenticator` behind a streaming proxy would trust. Helios's own
refusals now answer without reading the body and close the connection. `RuntimeServer` sets
Helidon's `maxPayloadSize(maxRequestBytes)` and `Http1Config.continueImmediately(true)`, and a
host's own `WebServer` that mounts `AgentHttpService` must set both.

| 2.x | 3.0 |
|---|---|
| A `401`, `404`, `413`, `429` or `503` answered before the body was read, a fixed `500`, or a `201` for a create that sent a body, kept the connection alive and drained or re-parsed the unread body | each is written whole with `Connection: close` and then the connection ends, on any server; a client sends its next request on a new connection. A request whose body was read to its end, or that has none, keeps its connection |
| Helidon's own answer to an unmatched path, to an `Error` a route lets escape, or a route that sent nothing, drained the body without bound, or, after `Expect: 100-continue`, parsed it as the next request | on a `RuntimeServer`, and on a `WebServer` with both settings, Helidon reads at most `maxRequestBytes` of the body before it answers; the `404` and `500` then keep the connection. Every request sent with `Expect: 100-continue` gets `100 Continue` at once, so its body is always framed as a body. An escaping `Error` is Helidon's `500 Internal Server Error`, quoting its message only with `ErrorHandling.includeEntity` (off by default) |
| A body of any declared length reached the route | a body declared over `maxRequestBytes` is Helidon's `413`, with Helidon's empty body and `Connection: close`, before routing and authentication, and none of it is read; a chunked body is refused as soon as it passes the bound, `413 {"error":"request body too large"}` |

**Helios routes write their own JSON.** Every body is a Helios record or a JSON value, written
with one fixed mapper, so `AgentHttpService` works on any Helidon `WebServer` with no media support
registered; mounted on a plain server it used to answer every request `415`.

| 2.x | 3.0 |
|---|---|
| `JacksonSupport`, `JacksonRuntimeException` | removed. Helios routes write their own JSON. A host's own routes that used `JacksonSupport` copy it, or write bytes with their own Jackson 3 `ObjectMapper` (Helidon's `helidon-http-media-jackson` supports Jackson 2 only) |
| `RuntimeServer.Builder.withObjectMapper(ObjectMapper)` | removed. The wire format is fixed: camelCase record component names and a `ToolOutcome`'s `kind` discriminator, as before with the default mapper |
| A body honoured an `Accept` charset | every body is UTF-8 |

**Every hook has a non-blank name, checked when the session is built.** `Hook.name()` promised a
non-blank name and nothing held a hook to it: an anonymous hook's default name was empty, so its
first `Block`, `MutateArgs`, `MutateResult` or other decisive outcome built an event that rejected
the blank name and ended the session with `ErrorDuringExecution`.

| 2.x | 3.0 |
|---|---|
| `HookRegistry` never checked a hook's `name()` and called it again at every decision, so a blank or throwing `name()` ended the session at the hook's first decisive outcome, and a `null` one fell back to the phase name, such as `PreToolUseHook`, at some decisions and ended the session at others | `AgentSession.create` reads each hook's `name()` once and rejects a `null` (`NullPointerException`) or blank (`IllegalArgumentException`) one, and an exception `name()` throws propagates; that name is the one every event, decision and log line carries. An anonymous hook's default name is its binary class name, such as `com.acme.Policies$1` |

**Tool authorization is mandatory, fails closed and runs on the call that executes.** The
permission evaluator was an ordinary `PreToolUse` hook, so a throwing permission-key extractor, or
a `MutateArgs` from a hook ordered before or after it, got a denied call executed; a `WRITE` tool
ran under `LOCKED_DOWN`. Every call now runs the `PreToolUse` chain, then authorization of the
final immutable call, then dispatch of that same call. Only an `ALLOW` admits it; anything else,
including a failure and a permission key carrying a secret of the session's `SecretRegistry`, is
`ToolOutcome.Rejected("Permission", reason)` with a `ToolBlocked` event. That reason and a hook's
`Block` reason are redacted through the session's `SecretRegistry` and cut at 1024 characters; an
approval prompt's reason is redacted too. A call naming no registered tool skips the
stage and ends `NotFound`.

| 2.x | 3.0 |
|---|---|
| The permission evaluator is a `PreToolUseHook` at priority 50 | a fixed stage after every `PreToolUse` hook; its refusals carry the name `Permission`, which `AgentSession.create` rejects for a hook (`IllegalArgumentException`) |
| A `PreToolUseHook` that throws or returns `null` is `Continue` | it refuses the call, `Rejected(by = the hook)`, with reason `hook threw <exception class>: <message>` or `hook returned null`, logged at `WARNING` with the exception's redacted copy; later `PreToolUse` hooks do not run |
| The first `PreToolUse` `MutateArgs` ends the chain | it passes the new arguments to the next hook; `Block`, `Inject` and `Stop` end the chain |
| `DefaultPermissionEvaluator` implements `PreToolUseHook`: `beforeTool`, `priority()`, `name()`, `Builder.withPriority(int)` | the session runs its policy as a fixed stage after every `PreToolUse` hook; no priority, since no hook runs after it; `Permission.evaluate(key, category)` dry-runs the policy |
| `PermissionDecision(effect, reason)`; deny reasons read `permission: deny rule for <tool>` | `PermissionDecision(effect, reason, rule)`; reasons name the tool, key and rule or default that decided, such as `deny rule Write(/etc/**) matched Write(/etc/passwd)` |

**A path tool's permission key is the canonical path it touches.** `Read`, `LS`, `Glob`, `Grep`,
`MemoryRead` and `MemoryWrite` were keyed on the raw `path` argument but ran on a resolution of it.
The permission stage now canonicalises every call of a registered tool: a path that names no
target is refused before any rule or approver sees it, and the canonical call is the one keyed,
announced, dispatched and shown to `PostToolUse` hooks; `PreToolUse` hooks still see the model's
spelling.

| 2.x | 3.0 |
|---|---|
| `ToolBinding.permissionKey(Map)` | `ToolBinding.canonicalize(ToolCall)` → `CanonicalCall(call, key)` |
| Path tools key on the raw `path` argument | keys are canonical: `.`, `./a/b`, `/memories/a/b`; a trusted workspace's outside target by its absolute real path |
| A rule glob is matched as written, whatever it could match | a rule for a path tool whose glob can never match a canonical key fails `AgentSession.create` |
| `X/**` does not match `X`; `/**/` needs one segment | `X/**` matches `X`; `/**/` and a leading `**/` match zero segments |
| `Read`/`LS`/`Glob`/`Grep` re-resolve the raw path when they run | they run on the authorised canonical path and fail when it no longer resolves to itself |
| A non-string `path`, JSON `null` included, is treated as missing (`"."` for `LS`, `Glob`, `Grep`) | refused |
| `Glob`, `Grep`, `LS` and `MemoryRead` `list` return every entry below their path | they withhold entries their own DENY rules match (and, for `Grep`, which returns contents, entries a `Read` DENY or ASK rule decides, without asking), never descend into a withheld directory, and end with `[withheld by permission policy: <n>]` |
| `new ToolContext(SessionContext, CancellationToken, Duration)` | `ToolContext(SessionContext, CancellationToken, Duration, Predicate<String> withheld)`; `ToolContext.noop()` withholds nothing |
| A permission-key extractor that throws is refused as `permission key of <tool> failed: <class>: <message>` under a `Permission`, and is never called without one | refused before authorization as `<tool>: <message>` |

**`Execute`'s permission key is the whole script, and the approval prompt shows the arguments.**
The key was `<RUNTIME>/<first whitespace-delimited token of the script>`, so a rule saw only the
first command. A rule now matches the whole script, stripped, every line included. The runtime is
the `Runtime` constant the argument names, or `UNKNOWN` when it names none, a call `Execute` refuses
to run. A script carrying a registered secret's value is refused as `permission key of Execute
carries a secret` before any rule or approver sees it. A deny glob is a guard rail: `BASH/**rm **`
misses `rm<TAB>-rf` and `r''m`, and matches `echo farm `. An allow rule admits whatever
`environment`, `args`, `stdin` and `workingDirectory` come with a matching script (e.g.
`BASH_FUNC_ls%%` in `environment`), and `*` matches `;`, so allow rules on `Execute` are not a
sandbox. Real control is `ASK`, whose prompt shows every argument.

| Before | 3.0 |
|---|---|
| `Execute(BASH/rm)` matched the key `BASH/rm`, the first token alone | it matches only the script `rm`; the key of `ls; rm -rf ~/` is `BASH/ls; rm -rf ~/`, not `BASH/ls;`. A glob such as `Execute(BASH/**rm **)` catches more spellings, not every one (see above) |
| The human approver asks `Allow <tool>(<key>)?` | it asks `Allow <tool>(<key>)?`, a blank line, then `Arguments: <arguments>`; the key, and the reason describing `Allow`, show as JSON string text through `JsonSnapshot.escape` (`"`, `\`, controls, line separators and format characters such as bidi overrides and zero-width characters escaped), cut at 1024 characters, so a script cannot fake a line of the question |
| `SecretRegistry.register` accepts a value that starts or ends with a space | refuses it as `Secret '<name>' must not start or end with a space`, since a stripped key would cut that space |

**A preset takes the model and a path and returns a `SessionOptions.Builder`.** `SessionPreset`
and `SessionOptions.Builder.withPreset` are removed: presets only called setters, so a later one
replaced an earlier one, and stacking them bought nothing. Each `SessionPresets` factory now
returns a builder that already holds the model and throws `NullPointerException` naming a null
argument. The presets no longer register `MemoryWrite` themselves; the session registers
`MemoryRead` and `MemoryWrite` whenever a memory backend is set (see Fixed).

| Before | 3.0 |
|---|---|
| `withPreset(SessionPresets.minimal())` | `SessionPresets.minimal(model)`, or `SessionOptions.newBuilder().withModel(model)` |
| `withModel(model).withPreset(SessionPresets.readOnly(path))`, `workspace(path)`, `openEnded(path, provider)` | `SessionPresets.readOnly(model, path)`, `workspace(model, path)`, `workspace(model, path).withExecutionProvider(provider)` |
| `SessionPresets.readOnly(WorkspaceRoot)`, for a trusted root (`new WorkspaceRoot(path, false)`) or another curated root | `SessionOptions.newBuilder().withModel(model).withTools(List.of(ReadTool.binding(root), GlobTool.binding(root), GrepTool.binding(root), LsTool.binding(root))).withPermission(Permission.planMode())` |
| `SessionPresets.workspace(WorkspaceRoot)` | the same four bindings, `.withPermission(Permission.defaultInWorkspace()).withMemoryBackend(FileSystemMemoryBackend.of(root))` |
| `SessionPresets.openEnded(WorkspaceRoot, ExecutionProvider)` | the `workspace` wiring above plus `.withExecutionProvider(provider)`, which registers `Execute` |
| A custom `SessionPreset` (`b -> b.withHook(hook)`) | an ordinary method that takes and returns a `SessionOptions.Builder` |

**A failed turn has no side effects, and the turn limit admits its last turn.** A turn runs the
tool calls it emitted only when it ended in `TOOL_CALLS` or `STOP`; the calls of any other turn
never run, emit no tool event and never reach the history.

| Before | 3.0 |
|---|---|
| `maxTurns = N` reported a session that answered on turn N as `ErrorMaxTurns` | `maxTurns = N` allows N turns; a session that needs turn N+1 ends in `ErrorMaxTurns` |
| A turn that ended in `ERROR`, `LENGTH` or by cancellation ran the tool calls it emitted and continued as a `TOOL_CALLS` turn | it runs none of them and ends with its own error terminal (`ErrorDuringExecution`, `ErrorTransientStream` or `Cancelled`) |

**An `ExecutionProvider` declares its runtimes and executes on the tool call's thread.**
`ExecutionCapabilities` advertised network and filesystem flags that nothing enforced, and its
`maxTimeout` was read back only by the provider that set it. `execute` returned a future that its
only caller, `Execute`, waited on at once, so each call took a second thread, and a provider that
ignored its token ran on unseen after an interrupt made `Execute` return. `execute` now runs on the
tool call's own thread and must stop when that thread is interrupted or the call's token fires.

| 2.x | 3.0 |
|---|---|
| `ExecutionCapabilities` (record and builder), `ExecutionProvider.capabilities()` | `ExecutionProvider.supportedRuntimes()`; `maxTimeout` stays each provider's `Builder.withMaxTimeout` |
| `LocalProcessExecutionProvider.Builder.withNetworkAllowed`, `withFilesystemWriteAllowed` | removed; they were never enforced. Isolate the host process yourself (container, VM) |
| `JShellExecutionProvider.Builder.withNetworkAllowed`, `withFilesystemWriteAllowed` | removed; use `JvmSandboxConfig.Builder.withSandboxPolicy` (e.g. `SandboxPolicy.noEgress()`) |
| `CompletionStage<ExecutionResult> execute(session, request, token)` | `ExecutionResult execute(session, request, token)`, run on the tool call's thread; it must stop on interrupt or cancellation, throwing `CancellationException` with the interrupt status kept, and a closed provider throws `IllegalStateException`. An interrupted JShell execute now closes its sandbox session, where it returned an RPC-failure result while the snippet ran on |

**`BoundedProcess.await` takes the call's `CancellationToken`.** Cancelling it or interrupting the
waiting thread kills and reaps the child and its descendants, forcibly after `BoundedProcess.GRACE`.

| 2.x | 3.0 |
|---|---|
| `BoundedProcess.await()`, which left the child running on interrupt | `BoundedProcess.await(CancellationToken)`; a child it killed reports exit code `-1` |
| `BoundedProcess.kill()` sent `SIGTERM` and returned | `kill()` also kills forcibly what is alive after `GRACE`, and returns once the tree has ended or a second `GRACE` passed |

**The default compactor summarises the whole conversation.** `DropMiddleToolResultsCompactor`
kept the first 3 and the last 20 messages around a summary of the middle. `SummarizingCompactor`
keeps every leading system message and the pending user turn and replaces everything else with
one summary message, prefixed `SummarizingCompactor.SUMMARY_PREFIX`, so no earlier assistant turn,
tool call or tool result is replayed. It returns the history unchanged in the middle of a tool
round. The default summary prompt asks for the user's goal, the facts found, the decisions made,
the open questions and the work left, since the agent continues from the summary alone.

| 2.x | 3.0 |
|---|---|
| `DropMiddleToolResultsCompactor` (with `withHeadPreserved`, `withTailPreserved`) | `SummarizingCompactor`: the whole conversation becomes one summary message; no head or tail is kept |
| `DropMiddleToolResultsCompactor.SUMMARY_PREFIX` | `SummarizingCompactor.SUMMARY_PREFIX` (same text) |
| `summaryModel()`, `headPreserved()`, `tailPreserved()`, `summaryTimeout()` accessors | removed; the builder's `newBuilder(model)`, `withSummaryPrompt`, `withSummaryTimeout` remain |

**`Read` and `Grep` bound what they build, and `Read`'s attachments fit Anthropic's request.** A
`Read` page is at most 60,000 chars, so it fits under the session's tool-result cap and ends with
the `offset` to continue from, whichever page cap stopped it. `Read` and `Grep` cut each line at
2,000 chars through one shared cut, which keeps no part of a registered secret, so a line longer
than that is read in parts through `Execute` (for example `cut -c`). `Read` validates `offset` and
`limit` before it reads the file, for every file type. A PDF of 18,000,000 bytes is 24,000,000
bytes base64-encoded, leaving 8,000,000 bytes of Anthropic's 32 MB request; an image of 7,500,000
bytes is 10,000,000 bytes encoded, Anthropic's per-image limit. A larger attachment is refused with
a message that names the cap and suggests a file-upload host tool. `Grep` matches through the
call's cancellation token, so a backtracking pattern on a long line stops with its call, and a
cancelled call fails with `Grep: cancelled` instead of returning what it found so far.

| 2.x | 3.0 |
|---|---|
| `ReadTool.MAX_PDF_BYTES` = 20 MiB | 18,000,000 bytes |
| `ReadTool.MAX_IMAGE_BYTES` = 5 MiB | 7,500,000 bytes |
| `ReadTool.MAX_LINE_BYTES` (1 Mi), marker `[line truncated to <n> bytes]` | `ReadTool.MAX_LINE_CHARS` (2,000), marker `[line truncated to <n> chars]` |
| `ReadTool.MAX_OUTPUT_BYTES` (4 Mi), marker ending `Use Grep to locate the section you need.` | `ReadTool.MAX_OUTPUT_CHARS` (60,000), marker ending `Use offset=<n> to continue, or Grep for a narrower target.` |
| `Grep` returned each hit line whole | each hit line is cut at `GrepTool.MAX_HIT_LINE_CHARS` (2,000) |
| A cancelled `Grep` returned the hits found before the cancellation | `ToolResult.failure("Grep: cancelled")` |

**`CancellationToken` runs each callback on a virtual thread of its own.** The callbacks one
`cancel` or `cancelAsync` claims run concurrently and in no order, each on a virtual thread named
`helios-cancellation-callback`, so a callback that blocks delays no other, whoever cancels the
token; `cancelAsync`'s `onError` may be called concurrently. `cancel(reason)` still returns once
every callback has run and throws the first `Error` one threw; `cancelAsync`'s future still
completes once every callback has run. The session now wakes each of its waits (a model stream, a retry back-off, a tool call, a pending
question, an execution) from that wait's own callback; the 10 ms poll of the token is gone.

| 2.x | 3.0 |
|---|---|
| `cancel(reason)` ran the claimed callbacks one after another on the caller's thread, in registration order | each on its own virtual thread, and `cancel` waits for all of them; register one callback for steps that must run in order |
| `cancelAsync` ran the claimed callbacks one after another on one thread, in registration order | each on its own virtual thread; register one callback for steps that must run in order |
| `cancelAsync`'s `onError` received the callbacks' `Error`s in order, on one thread | possibly concurrently, from each callback's thread |

**A retryable HTTP status at stream open is a `TransientStreamException`, and the session honours
`Retry-After`.**

| 2.x | 3.0 |
|---|---|
| A 408, 429 or 5xx (529 included) answer to a stream request was the provider's exception (`AnthropicException`, `GeminiException`, `OpenAIException`); `chat` threw it and a session ended in `ErrorDuringExecution` | a `TransientStreamException` whose cause is the provider's exception with the status; `chat` throws it, and a session retries the turn under `streamRetryPolicy`, ending in `ErrorTransientStream` when the attempts run out |
| A Gemini in-stream `error` with a numeric 408, 429 or 5xx code was a `GeminiException` | a `TransientStreamException` whose cause is that `GeminiException` |
| `ProviderException.isRetryable()` was `true` for status 0 | `false`: only 408, 429 and 500 or more are retryable |
| `SseReader.open(client, request, idleTimeout, parser, failure)` | the lazy `new SseReader(client, request, apiName, idleTimeout, parser, failure, providerName)` |

**`AgentSession` gains `pendingQuestions()`.** It returns an immutable snapshot of the questions
the session asked and that are not yet answered, cancelled or expired, each as its `QuestionAsked`
event carried it. An `AgentSession` implemented outside Helios must add it.

| 2.x | 3.0 |
|---|---|
| A host learned of an open question only from the `QuestionAsked` event | `AgentSession.pendingQuestions()` → `List<AskUserQuestionRequest>`; implementations add it |

**Permissions are safe by default and the most restrictive matching rule wins.** Every session has
a permission policy; one built without `withPermission` gets `Permission.empty(DEFAULT)`, which
allows reads and asks before every write, execution and network call. **Headless hosts**, where no
one answers questions, set `withPermission(Permission.empty(PermissionMode.BYPASS_PERMISSIONS))` or
allow rules for the tools their runs need; otherwise a run waits on its first ask until
`maxWallClock` (1 h by default). Over HTTP, list and answer the asks with
`GET /sessions/{id}/questions` and `POST /sessions/{id}/questions/{questionId}`. A call is decided
by the first matching deny rule, else ask rule, else allow rule, in every mode, else by the mode's
default for the tool's category. So "ask broadly, allow a narrow exception" (`ASK
Read(./secrets/**)` with `ALLOW Read(./secrets/README.md)`) now asks for the exception too: where
the mode already asks for the category (`WRITE`, `EXECUTION`, `NETWORK` under `DEFAULT`), drop the
broad ask and keep the allow; for a `READ` or `CONTROL` tool, or under `LOCKED_DOWN`, list the
exception's siblings as asks instead. The presets lose the rules that changed no decision but
overrode a user's own: a user's `ASK Read(./secrets/**)` added to `defaultInWorkspace()` used to
evaluate to `ALLOW`.

| 2.x | 3.0 |
|---|---|
| No permission set → every call admitted | `Permission.empty(DEFAULT)`: writes, execution and network ask; headless hosts set `Permission.empty(PermissionMode.BYPASS_PERMISSIONS)` or allow rules |
| `SessionOptions.permission()` → `Optional<Permission>`; `withPermission(null)` cleared it | `Permission`, non-null; `withPermission(null)` throws `NullPointerException` |
| Rule order deny → BYPASS → allow → ask → default; an allow overrode a matching ask, and `BYPASS_PERMISSIONS` ignored ask rules | deny > ask > allow > default in every mode; ask rules apply, and withhold from `Grep`, under `BYPASS_PERMISSIONS` |
| `PermissionRule(effect, toolName, argPattern)`, `PermissionRule.withGlob(effect, tool, glob)` / `any(effect, tool)`, `PermissionRule.effect()` | `PermissionRule(toolName, argPattern)`, `PermissionRule.of(tool, glob)` / `any(tool)`; the list a rule sits in is its effect |
| `Permission.firstRuleFor(tool)` | removed: it ignored globs; inspect `allow()`, `ask()` and `deny()` |
| `Permission.planMode()` allowed `Read`, `LS`, `Glob`, `Grep` on `./**` | `Permission.empty(PLAN)`, deciding every call as before |
| `Permission.defaultInWorkspace()` allowed the read tools, `MemoryRead` and `MemoryWrite`, and asked for `Write`, `Edit`, `MultiEdit` and `Execute` | `DEFAULT` plus `allow MemoryWrite(/memories/**)`, deciding every call as before; an `Execute` ask names no rule (the category default asks) |

**The session drops its dead layers.** The file tracker, `StopReason`, the second "off"
implementations and an implementation detail on `AgentSession` are gone. A typed `runBlocking` on
a session built with an output schema accepts only that schema, and a `PostModelTurn` hook sees
what a failed turn streamed rather than its error. To keep a read ledger, register a
`PostToolUseHook` on `Read`: it receives each call's path and result.

| 2.x | 3.0 |
|---|---|
| `FileTracker`, `InMemoryFileTracker`, `FileFingerprint`, `FileSnapshot` | removed; to keep a read ledger, register a `PostToolUseHook` on `Read` |
| `ReadTool.binding(WorkspaceRoot, FileTracker)` | `ReadTool.binding(WorkspaceRoot)`; `Read` no longer hashes the file it reads, nor fails with `Read: I/O error fingerprinting` |
| `QueryEvent.TurnEnded.reason()` → `StopReason` | the turn's `FinishReason`; SSE values change `END_TURN` → `STOP`, `TOOL_USE` → `TOOL_CALLS`, `MAX_TOKENS` → `LENGTH`, and `REFUSAL` splits into `REFUSAL` and `CONTENT_FILTER`; `StopReason` is removed |
| `MemoryBackend.disabled()` | removed: do not set a memory backend |
| `AgentSession.rawOutputCapturePolicy()` | removed; `runBlocking(msg, schema)` is now abstract, so an `AgentSession` implemented outside Helios implements it |
| `runBlocking(msg, schema)` on a session built with a different schema parsed against the given one | throws `IllegalArgumentException` naming both types, before sending; pass the session's schema, or build the session without one |
| A `PostModelTurn` hook on a failed turn saw the redacted error message as the response content | it sees the text the model streamed before the failure (empty when none); the error is in the terminal, redacted |
| `ContextCompactor.disabled()` was documented as skipped by the loop | the loop calls it at the 0.95 watermark and it leaves the history unchanged, so a long session overflows and the provider's error ends it |

**Each hook phase returns its own outcome type, so an outcome the phase would ignore does not
compile.** A wrong-phase outcome used to end its phase silently: the first non-`Continue` outcome
won, the phase ignored it, and no later hook of the phase ran, so a sibling's typo could defeat a
guard. `HookOutcome` now permits six sealed phase types, each admitting exactly what its phase
honours (`OnUserMessage`: Continue, MutateText, Block, Stop; `PreModelTurn`: Continue,
MutateHistory, Inject, Stop; `PostModelTurn` and `PreStop`: Continue, Inject, Stop; `PreToolUse`:
Continue, MutateArgs, Block, Inject, Stop; `PostToolUse`: Continue, MutateResult, Inject, Stop).
What each phase does with each outcome is unchanged. The two compaction hooks are removed: wrapping
the compactor does their job.

| 2.x | 3.0 |
|---|---|
| Hook methods return `HookOutcome` | each returns its phase type (`HookOutcome.OnUserMessage`, `PreModelTurn`, `PostModelTurn`, `PreStop`, `PreToolUse`, `PostToolUse`); a hook returning an outcome its phase ignored no longer compiles. `HookOutcome.cont()`, `block(...)`, `inject(...)`, `stop(...)` and the `mutate*(...)` factories return their record types |
| `PreCompactHook`, `PostCompactHook`, `CompactionPayload`, `HookRegistry.firePreCompact`, `firePostCompact` | wrap the compactor (`SessionOptions.Builder.withContextCompactor`) to see the history before and after, and observe `QueryEvent.ContextEdited` for the counts; to stop after a compaction, return `Stop` from a `PreModelTurnHook` |

**`AgentSession.events()` returns an `EventStream`, not a `Flow.Publisher`.** `EventStream` is an
`Iterable<QueryEvent>` that is also `AutoCloseable`, read once with for-each inside
try-with-resources, the shape of `java.nio.file.DirectoryStream`. Each call opens a new stream with
its own 256-event buffer, attached before `events()` returns. The session no longer waits for
readers at its end: every event is in each stream's buffer, or missed by it, before `result()`
completes, and the reader drains it on its own thread.

| 3.0 snapshot | 3.0 |
|---|---|
| `Flow.Publisher<QueryEvent> AgentSession.events()`; `session.events().subscribe(subscriber)` | `EventStream AgentSession.events()`; `try (var events = session.events()) { for (var event : events) { ... } }` on a thread of the reader's own |
| A subscriber's `onComplete` / `onError` | the iteration ends: when the stream is closed, when the session ended and every buffered event was read, or when the reading thread is interrupted (`hasNext()` returns `false`, the interrupt kept); a stream never fails |
| `Subscription.cancel()` | `EventStream.close()`, from any thread, idempotent, never throwing |
| A `LoopEnded` subscriber's work was done before `result()` returned, since the teardown drained the subscribers | the teardown no longer waits for readers; a reader that must have handled `LoopEnded` before `result()` returns synchronises with its own reading thread (join it, or complete a future from it) |
| An `AgentSession` implementation returned a `Flow.Publisher` | it returns its own `EventStream` |
| `core.events.EventSinkPolicy` | removed; it had no use |
| `examples.session.ConsoleEventPrinter` was a `Flow.Subscriber` | `new ConsoleEventPrinter(out).print(events)` prints an `Iterable<QueryEvent>` until it ends |

**The teardown's waits share one `stopGrace` deadline.** Every end of a session waits for the
token's callbacks, the live tool calls and `onSessionEnd` until one deadline, `stopGrace` (5 s)
from the teardown's start, instead of up to `stopGrace` each: `close()` and every other end add at
most `stopGrace`, not three times it. A step after one that used the deadline up (a callback or a
tool that never returns) still runs, `onSessionEnd` is still called and the event streams still
end, but it is not waited for: `onSessionEnd` may then still run when the result settles, and its
`WARNING` is logged.

**The session's public surface is what a user configures.** Tools append like hooks; setting an
execution provider registers `Execute`, as a memory backend registers the memory tools, and
`AskUserQuestion` is opt-in. Two tool categories that behaved exactly like two others are merged
into them. The agent loop, the permission evaluator, the approval prompt and the hook registry are
no longer exported; `Permission.evaluate` dry-runs a policy, and a compactor gets the hooks'
`HookContext` instead of the loop's state.

| 2.x | 3.0 |
|---|---|
| `SessionOptions.Builder.withTools(ToolRegistry)`; `SessionOptions.tools()` → `ToolRegistry` | `withTool(ToolBinding)` appends, `withTools(List<ToolBinding>)` replaces the list; `tools()` → `List<ToolBinding>`. `ToolRegistry` is internal. Two tools with one name, a built-in included, still fail `AgentSession.create` with `duplicate tool name in registry: <name>` |
| `ToolCategory.SEARCH`, `ToolCategory.DELEGATION` | `READ` and `CONTROL`, which they behaved exactly like; `Grep` and `Glob` are `READ`, and a default decision's reason reads `default-allow READ (...)` |
| `NoopExecutionProvider`, the default execution provider | set no provider: the session then has no `Execute` tool and no start or end step |
| `SessionOptions.executionProvider()` → `ExecutionProvider` | `Optional<ExecutionProvider>`; `withExecutionProvider(p)` registers `Execute` over `p` and runs its `onSessionStart` and `onSessionEnd` with the session |
| `ExecuteTool.binding(p)` in the tools next to `withExecutionProvider(p)` | delete the binding; registering both fails `AgentSession.create` with `duplicate tool name in registry: Execute`, and a tool named `Execute` without a provider fails it too, since its provider would never be started |
| `SessionPresets.openEnded(model, root, provider)` | `SessionPresets.workspace(model, root).withExecutionProvider(provider)` |
| `AskUserQuestion` registered in every session | registered only with `SessionOptions.Builder.withAskUserQuestion()`; a permission `ASK` still asks through `QuestionAsked` without it |
| the `session.loop` package (`AgentLoop`, `TurnRunner`, `ToolDispatch`, `StopClassifier`, `SessionState`, `ConversationHistory`, `SessionTotals`, `SessionEnding`, `TurnOutcome`) was exported | internal; no replacement needed |
| `ContextCompactor.compact(List<Message>, SessionState)` | `compact(List<Message>, HookContext)`, the context the session's hooks get at that turn |
| `DefaultPermissionEvaluator`, its `evaluate(key, category)` and `ruleDecision(key)` | internal; dry-run a policy with `Permission.evaluate(key, category)`, which carries the deciding rule in `rule()` |
| `RuleMatcher`, `HookDecision`, `HookRegistry` (with `countOf`) | internal; `countOf` is removed |
| Automated approval through a hand-built evaluator's approver | answer the `QuestionAsked` event with `AgentSession.answer`, or decide the call in a `PreToolUseHook` |

**The v1 conversation tables are gone from `schema.sql`.** Nothing has written
`helios_sessions`, `helios_messages`, `helios_archive` or `helios_core_blocks` since 2.0.0's
removal of `PgMemory`; `schema.sql` no longer creates them. Existing tables are not dropped, since
`schema.sql` only creates what is missing. A deployment that never used them may drop them:

```sql
DROP TABLE IF EXISTS helios_messages CASCADE;
DROP TABLE IF EXISTS helios_sessions CASCADE;
DROP TABLE IF EXISTS helios_archive CASCADE;
DROP TABLE IF EXISTS helios_core_blocks CASCADE;
```

| Before | 3.0 |
|---|---|
| `persistence.sql.MessageSql`, `persistence.sql.SessionSql`, `persistence.mapper.MessageMapper`, `JsonbMapper.toolCallsFromJsonb` | removed; a deployment that stored conversations in its own copies of these tables moves to `PgSessionStore` |

**`core.events` is gone; a finished trace goes to one `TraceListener`.** Nothing emitted a
`HeliosEvent` since the v1 agent was deleted, so the package, its sinks and the span open/close
events go. `core.trace.TraceListener.onTrace(Trace)` receives a finished trace.

| 2.x | 3.0 |
|---|---|
| `core.events` (`HeliosEvent`, `EventSink`, `CollectingEventSink`, `JsonlEventSink`; `EventSinkPolicy` has its own row above) | removed; nothing emitted these events. Session activity: read `AgentSession.events()` (`QueryEvent`). Finished traces: implement `core.trace.TraceListener` |
| `TraceBuilder.start(String, UUID, List<EventSink>)` | `TraceBuilder.start(String)`; spans no longer emit open/close events |
| `PgTraceStore implements EventSink`: `onEvent(HeliosEvent)` and `store(Trace)` | `PgTraceStore implements TraceListener`: `onTrace(Trace)`, one transaction as before |

**The durable-run layer is gone.** The session never used the run store, the tool-call journal or
the resume scanner, so the toolkit, its in-memory and Postgres implementations and its two tables
are removed. Durable conversations are `SessionStore` / `PgSessionStore`. `schema.sql` no longer
creates `helios_agent_runs` or `helios_tool_calls`; existing tables are not dropped, since
`schema.sql` only creates what is missing. A deployment that never used them may drop them, child
table first:

```sql
DROP TABLE IF EXISTS helios_tool_calls;
DROP TABLE IF EXISTS helios_agent_runs;
```

| 2.x | 3.0 |
|---|---|
| `core.runtime`: `AgentRun`, `AgentRunStatus`, `Durability`, `DurabilityCoordinator`, `DurableResumeScanner`, `InMemoryRunStore`, `InMemoryToolCallJournal`, `RunStore`, `ToolCallJournal`, `ToolCallJournaling`, `ToolCallRecord`, `ToolCallStatus`, `UnsafeResumeException`, `UnsafeResumePolicy`; persistence: `PgDurability`, `PgRunStore`, `PgToolCallJournal`, `mapper.AgentRunMapper`, `mapper.ToolCallRecordMapper`, `sql.AgentRunSql`, `sql.ToolCallJournalSql` | removed, with no replacement; a host that checkpoints its own runs keeps its own table |
| `Tool.idempotent()`, `Tool.Builder.withIdempotent(boolean)` and the canonical constructor's `idempotent` component | removed: nothing read it and the session never retries a tool call. `Tool(name, description, parameters, executor)` |
| `PgConfig.agentId()`, `PgConfig.Builder.withAgentId(String)` and the canonical constructor's `agentId` component | removed: only `PgRunStore` read it. `PgConfig(dbClient, schema, redactor)` |
| `JsonbMapper.argumentsFromJsonb(String)` | removed: only the journal read it |

### Added

- **Every run records a trace.** `SessionOptions.Builder.withTraceListener(listener)` hands the
  listener one `Trace` per run, on every ending (a terminal, a host `Error`, a crash, a close or
  an interrupt), before the run's result completes: a `model.call` span per model attempt with its
  usage, cost and duration, a `tool.<name>` span per tool call and a `hook.<name>` span per hook
  decision, totals equal to the run's result and `sessionId` the session's id, so a
  conversation's runs are found together. `withTraceDetail(TraceDetail.VERBOSE)` also stores the
  prompt, answer, tool arguments and results, redacted by the session; the default `STANDARD`
  stores no content. A listener's `Exception` is logged; its `Error` fails the run's result.
  `.withTraceListener(pgTraceStore)` stores them in Postgres.
- **`QueryEvent.ModelCallEnded` reports each model attempt**, a compactor's summary call
  included: the model id it was priced at, its attempt number, finish reason, usage, cost,
  duration and redacted error. An accounted call's event carries exactly the usage it added to
  the run, so the events' usage sums to the result's; a retried attempt reports zero usage.
- **Sessions can be stored and reopened.** `SessionOptions.Builder.withStore(store)` makes a
  session durable: `AgentSession.open` loads the conversation stored under the session id, or
  starts it when there is none, after the options' system prompt, which is never stored. Each
  turn appends one batch (`MessageAdded` per message, or one `HistoryReplaced` after compaction
  or a `MutateHistory` hook), and so does each run's end. Each append names the last sequence
  number the session read or wrote; a store holding another refuses it with
  `SessionConflictException`, the run ends `ErrorDuringExecution` kind `SessionConflict` (any other
  store failure: `SessionStoreFailed`) and the session closes itself. `InMemorySessionStore` and
  `FileSessionStore` (one `<sessionId>.jsonl` per session, owner-only on POSIX, refusing a
  directory group or others can write and a file whose name on disk is not its id's, a torn last
  line ignored and truncated) ship in `com.standardapplied.helios.session.store`, with the
  `SessionStore` SPI, `SessionEntryJson` (an entry reads back as the same JSON value, a number
  exact in value but not always in Java type) and the `SessionStoreContract` test case in the
  session test jar.
- **`PgSessionStore`** keeps sessions in PostgreSQL, one row per entry in
  `helios_session_entries` (primary key `(session_id, seq)`, the entry as `SessionEntryJson`
  text, so a message holding U+0000 is stored verbatim), and passes `SessionStoreContract`. An
  append is one transaction whose first row is inserted only when the session's highest sequence
  number is the one the writer expects; a load reads from the last `replaced` row in one statement.
  `delete` locks the table against writers (`SHARE ROW EXCLUSIVE`), so an append in progress is
  removed whole or conflicts afterwards. It applies no redactor: the session already redacted what
  it stores.
- **A session is a conversation that runs many times.** `send` steers the run in progress or
  starts the next one and returns that run's result future; a session runs again after any ending
  until it is closed; `interrupt()` stops the current run and keeps the session; events carry
  `runId`, and `RunStarted`/`RunEnded` bracket each run. A run that ends with tool calls
  unanswered (a hook's `Stop`, a cancel, an error) answers each with a "not run" tool message, so
  the next run's history is one a provider accepts.
- **`ChunkDrain.drain(CloseableIterator<ModelChunk>)`** is the one drain of a model's chunk
  stream into its response: text and thinking joined, completed tool calls in order, and the
  `MessageStop`'s finish reason, usage, metadata and citations; what the stream throws is thrown
  as itself, and a stream without its `MessageStop` throws `IllegalStateException`.
- **`GET /sessions/{id}/questions` and `POST /sessions/{id}/questions/{questionId}`.** An HTTP
  client lists a session's pending questions, permission approvals (`perm-` ids) and the model's
  `AskUserQuestion` (`q-` ids), and answers one with `{"selectedLabels": [...], "customText":
  "..."}`: `202` once the session accepted it, `400` for an invalid body, `404` for a question not
  pending, `409` for a closed session. Both are authenticated and owner-scoped like every route;
  the answer is admitted and read like `POST .../messages`.
- **`TransientStreamException(message, cause, providerName, retryAfter)` and `retryAfter()`;
  `Backoff.maxDelay()`.** A `TransientStreamException` for a retryable status at stream open
  carries the `Retry-After` seconds of the answer; `maxDelay()` is the longest delay a back-off
  waits, `Fixed`'s `delay` or `Exponential`'s cap.
- **`helios-core` publishes its test fixtures as `helios-core-<version>-tests.jar`.** `Await`
  (waits for an event under one 60-second hang guard), `LineSink` and `FeedableInputStream`, in
  `com.standardapplied.helios.core.test`, are what the Helios test suite uses instead of sleeps,
  self-chosen timeouts and piped streams. `StubHttpServer` (a loopback HTTP/1.1 server that
  records each request before answering it) and `RedirectTrap` (the redirect contract a
  credentialed provider client is held to) test HTTP clients. `ModelHarness` runs a provider
  `Model` against a stub that replays recorded server-sent events (`SseReplies`), `Transcript`
  renders what it streamed and returned as canonical text, `Golden` compares it with a file under
  `src/test/resources/golden` (`-Dgolden.update=true` rewrites the files), and
  `ConversationFixture` is the conversation every provider's request snapshots share.
  `ChildJvm` runs a class of the test classpath in fresh JVMs, the JaCoCo agent passed through, and
  asserts its output is the same in each; `DeclarationOrderFixture` is a conversation whose
  schema, tool parameters and replayed arguments each have eight or more names in a declared
  order, and asserts a request body or prompt keeps it. `FailingInputStream` fails on read or
  close, and `SseEvents` writes server-sent events and drains every event a reader yields, or
  only those with content. `ReasoningMatrix` is the
  model-by-`Reasoning` matrix each provider's reasoning test runs. `Accepted` holds the checks a
  live test makes whatever the model writes: a reply the request allows, a chunk stream that
  completes and ends in exactly one `MessageStop`, and a skip naming the recorded counterpart when
  an unconstrained structured reply does not parse. `StreamRead` reads a stream to its end and
  records its items and the exception that ended it, `FakeHttpClient` answers requests through a
  function the test supplies, `HeldSseServer` holds a reply open part by part and reports when the client closes the
  connection, and `ExchangeTurns` runs one request through a provider's `Exchange` the way
  `StreamingModel` does. Abstract contract tests run one set of cases against every implementation:
  `ModelIntegrationContract` (live provider parity, asserting only what holds for any model
  output), `ConversationRequestContract` (the system message and earlier turns a request carries,
  offline), `BoundedErrorBodyContract` and `PromptRegistryContract`.
  Depend on it with
  `<type>test-jar</type>` and `<scope>test</scope>`.
- **`core.common.OrderedMaps.copyOf(Map)`** is the one ordered copy of a map Helios sends to a
  model or reports to a user: unmodifiable, in the given iteration order, and rejecting a null map,
  key or value as `Map.copyOf` does, the value message naming its key. Tool-call, `MutateArgs`
  and event arguments, event attributes and data, `JsonSchema` properties and REPL bindings are
  stored through it.
- **`helios-session` publishes its test fixtures as `helios-session-<version>-tests.jar`**, in
  `com.standardapplied.helios.session.test`: `EventCollector` (reads a session's `EventStream`
  on a virtual thread, records its events, optionally reacting to each, and waits for the stream
  to end through `Await`), `QuestionAnswers`
  (answers every `AskUserQuestion` with one label), `HookInputs` and `SampleDocuments`.
- **`helios-testing` scripts any response and any stream.**
  `ScriptedModel.Builder.withResponseTurn(Response)` returns exactly the given response, metadata,
  thinking and citations included; `withStreamTurn(CloseableIterator<ModelChunk>)` answers
  `chatStream` with the given stream and fails a `chat` call; `ScriptedModel.outputSchemas()`
  records the output schema each call carried. `ModelStreams.of(chunks...)`,
  `failing(error, chunks...)` and `stalled(onFirstPull)` build one-pass streams.
- **`CircuitBreaker.Builder.withClock(InstantSource)`.** The breaker reads the current instant from
  an injectable source (default `Clock.systemUTC()`), so the half-open delay can be driven by hand
  instead of by sleeping. A `java.time.Clock` is an `InstantSource` and can be passed directly.
- **`core.process`: one bounded way to run a child process.** `BoundedProcess` starts a process
  from an explicit argv and environment (nothing inherited from the JVM), captures stdout and
  stderr into capped buffers with a truncation marker, kills the process and its descendants when
  its timeout elapses, and removes its per-call working directory on `close()`; `ProcessOutcome`
  is what it produced. `CommandGrant` and
  `LocalProcessExecutionProvider` both run on it. `BinaryResolver` pins a binary to an absolute
  path once. An architecture rule keeps `java.lang.ProcessBuilder` inside `core.process` and the
  REPL sandbox launcher.
- **`core.provider`: what every streaming provider shares**, for the built-in providers and for a
  provider written outside Helios. `StreamingModel` is the `Model` a provider assembles through
  `StreamingModel.newBuilder()` from its identity, `ModelConfig`, HTTP client, `RequestFactory`
  (one turn's request), `Exchange` (how a turn's event stream is opened), a `ProviderFailure`
  and a JSON adapter; it implements streaming (each event as its `ModelChunk`, structured turns
  checked once complete unless they called tools), chat (the stream drained), structured chat and
  close once, and maps a failed turn's exception one way (the provider's exception and
  `TransientStreamException` pass through, an `IOException` becomes a retryable
  `TransientStreamException`). A provider's exchange opens a lazy `SseReader`, which sends its
  request on the first `hasNext()`, makes a failure to open it the stream's one error event and
  reads server-sent events with a per-line idle timeout, `JsonPost` builds the JSON `POST` with default
  and configured headers, and `ProviderFailure` creates a provider's exception. Architecture rules
  keep provider modules on them: no class in a provider implements `Model`, reads a stream with a
  `BufferedReader`, or builds a Jackson mapper outside its `api` holder.
- **`StreamEvent.KeepAlive` and `ModelChunk.KeepAlive`** mark a part of a provider's stream that
  carries nothing for the turn. `SseReader` yields a `StreamEvent.KeepAlive` for every event
  without content: one the parser yields nothing for, one with no or blank data, the `[DONE]`
  marker and a comment-only heartbeat (`: ping`). `StreamingModel` yields it as a
  `ModelChunk.KeepAlive`, the liveness signal a direct caller may use; `chat` and a session add
  nothing for it.
- **`ModelChunk.MessageStop.of(response)` and `MessageStop.finishReason()`** build the message
  stop that ends a turn's chunk stream from its response (finish reason `STOP` and zero usage when
  unset) and read its stop reason back as a `FinishReason` (`STOP` for a spelling that names none).
- **`RedactionResult.mergeCounts(other)`** sums two streams' per-secret counts in encounter order.
- **`HttpClientFactory.createForDownloads()`**, the one factory client that follows redirects, for
  unauthenticated downloads such as Hugging Face files served from its CDN. It uses
  `Redirect.NORMAL`, which never follows `https` to `http`, and the 10-second connect timeout of
  `create(...)`. It takes no `ModelConfig`, so no configured key or header reaches it, and an
  architecture rule allows it only in `helios-onnx`. `create(...)` still never follows a redirect.
- **`JsonSnapshot.write(Map)` and `JsonSnapshot.escape(String)`.** `write` writes a JSON object of
  `JsonSnapshot`'s values as compact JSON, keys in the map's order; an approval's arguments are
  written through it. `escape` is the one JSON string escaping: what RFC 8259 requires plus the
  line separators U+2028 and U+2029 and every format character, so the text shows what it holds.
  Event JSON escapes its strings through it too, so an event now also shows line separators and
  format characters as `\uXXXX`.
- **`Strings.truncated(String text, int maxChars, String note)`.** Cuts a text to at most `maxChars`
  characters, never inside a surrogate pair, then appends `note` formatted with how many were cut.
  Redacting before the cut means a secret the cut falls across is never partly shown. A refusal's
  reason, an approval's arguments and the approval prompt's key and reason are bounded through it.
- **`PermissionDecision.rule()` and `Permission.evaluate(key, category)`.** `rule()` is the rule
  that decided, empty when a mode or category default did. `evaluate` is the policy's pure decision
  for one key, the one the session enforces, so a host unit-tests its policy without a session: no
  question is asked and nothing runs. A call the policy asks about is asked through `QuestionAsked`
  for the final immutable call, its arguments shown redacted and bounded, under a fresh question id
  per request, so an answer resolves one call only.
- **`PathSpace`, `ToolBinding.PathArgument`, `ToolBinding.Builder.withPathArgument`,
  `WorkspaceRoot.canonical`, `WorkspaceRoot.spell`, `WorkspaceRoot.resolveCanonical`,
  `session.tools.Withholding`, `ToolContext.withholds`,
  `ToolBinding.PathArgument.withContentRulesOf`.** A `PathSpace` defines a path argument's canonical
  spelling (`canonical`), says whether a rule glob can match one (`admits`) and gives a sample
  (`example`); `WorkspaceRoot` is the workspace's, and memory has its own. A binding declares its
  path argument with `PathArgument.required(name, space)` or `optional(name, default, space)`.
  `Withholding` is the one filter a walking tool applies to each entry, through the call's
  `ToolContext.withholds`, withholding an entry a rule decides (`Permission.evaluate(...).rule()`
  present), and `withContentRulesOf` names the tools whose content a walk returns (`Read` for `Grep`), whose DENY
  and ASK rules then withhold its entries.

### Changed

- **`helios-persistence` now depends on `helios-session`**, for `PgSessionStore`; its module
  requires `com.standardapplied.helios.session`.
- **A session no longer holds a platform thread.** Its wall-clock deadline was a
  `ScheduledThreadPoolExecutor` with one platform thread per session (`helios-deadline-<id>`); it
  is now a virtual thread, `helios-wall-clock-<id>`, that waits on the session's token for
  `maxWallClock` and ends with the session. Nothing a caller does to `result()`, such as
  `orTimeout`, disarms it.

- **A slow event reader never stalls the session.** A reader whose buffer is full misses a routine
  event at once, logged at FINE, instead of delaying the loop, or the turn's stream reader, by up to
  1 s per event. `LoopEnded`, `QuestionAsked` and `Error` still wait up to 30 s for room in each
  full stream, then are dropped there with a WARNING; the terminal stays readable through
  `result()`, an open question through `pendingQuestions()`. The session's event executor and its
  drain at teardown are gone.
- **Requests to Claude Fable 5.1, Opus 5.5 and Sonnet 5.5 ask the API to verify replayed
  thinking.** With adaptive thinking they carry the beta `thinking-binding-controls-2026-08-01`
  and `thinking.block_binding.prefix_mismatch_behavior: "error"`, so a mismatched block fails the
  same way on every account, whatever its age. `AnthropicModelId.bindsReasoning()` names those
  models. Every assistant message records the stamp of the prompt it was produced under,
  `anthropic.promptStamp` or `gemini.promptStamp` (`core.provider.PromptStamp`: the SHA-256 of
  the canonical JSON of the system prompt and tools sent).

### Fixed

- **A blocked first message still called the model.** When an `OnUserMessageHook` blocked the only
  queued message, a session with a system prompt called the model with the system prompt alone.
  A run now calls the model only after a user message of its own reached the history, and ends in
  `ErrorDuringExecution` kind `NoUserMessage` otherwise.
- **The session's usage, cost and budget missed spend that had already happened.** A
  `PreToolUseHook` or `PostToolUseHook` `Stop` ended with the totals from before the turn that
  called the tool, a schema self-correction turn was never counted, and a compaction whose summary
  call reported only cache reads or writes was dropped. Each turn's usage is now added as soon as
  its stream ends, before any tool call or terminal decision, and a compactor's usage is added
  when any of its four token counts is positive (`Usage.reportsTokens()`). `CompactionResult` now
  rejects a blank `modelId` for cache-only usage too, instead of pricing it at the main model's
  rate.
- **A rate-limited or overloaded provider ended the session at once.** A 429, 503 or 529 at
  stream open was never retried, and `Retry-After` was ignored. The session now retries it, and
  waits the `Retry-After` seconds the provider sent, capped by the retry policy's maximum delay;
  `TurnRetried.backoff` is the delay it waited.
- **Compaction broke sessions on models that bind thinking to the conversation.** The default
  compactor kept the last 20 messages, whose thinking blocks were signed against the full history,
  so Fable 5.1, Opus 5.5 and Sonnet 5.5 rejected the next request with a 400 and the session ended
  in `ErrorDuringExecution`. The new default, `SummarizingCompactor`, carries no earlier assistant
  turn.
- **An unreadable Anthropic thinking value was sent as no thinking.** An assistant message whose
  `anthropic.thinkingBlocks` cannot be read, or holds a block without a signature, now fails
  before the request with an `AnthropicException` naming the key, instead of reaching the API
  without its blocks.
- **A backgrounded child hung a command call, and an interrupted or cancelled grant left its child
  running.** A process left behind (`server &`) held the output pipes, so `CommandGrant` and
  `LocalProcessExecutionProvider` waited forever; each stream now gets `BoundedProcess.GRACE` after
  the child exits and is then reported truncated. An interrupted or cancelled grant now kills and
  reaps its child (a cancelled one returns `Cancelled while invoking <name>`), and a descendant
  that ignores `SIGTERM` is killed forcibly even when its parent died, also on
  `LocalProcessExecutionProvider.close()`. A sandbox launch that fails after its RPC channel opens
  now closes the channel, kills the child's descendants and registers no shutdown hook.
- **A session from `SessionPresets.workspace` or `openEnded` failed to start.** Both presets
  registered `MemoryWrite` and set a memory backend, for which the session registers `MemoryRead`
  and `MemoryWrite` itself, so every `AgentSession.create` threw `IllegalArgumentException:
  duplicate tool name in registry: MemoryWrite`. The presets now leave the memory tools to the
  session.
- **The README quick starts did not compile.** They called a preset API that did not exist. Every
  `java` block in `README.md` and `session/README.md` that calls `SessionPresets` is now the body
  of a method in `examples/session-demo`'s `QuickStarts`, which the build compiles, and
  `QuickStartsDocsTest` fails when a block and its method differ.
- **A stream idle timeout under a second was reported as "0s".** The message rounded the timeout
  down to whole seconds; it now gives milliseconds: `Stream idle timeout: no data received for 50 ms`.
- **`Execute` on the JShell runtime ignored its time budget; every snippet stopped at 30 seconds.**
  `JShellExecutionProvider` ran each snippet under `ReplConfig.executionTimeout()` (30 s by
  default), whatever `timeoutSeconds` the model passed, and `Execute` itself defaulted to 30 s
  instead of the session's per-tool timeout. A CodeAct/RLM snippet that fanned out a few
  `predict(...)` calls in a row was killed at 30 s even when the model asked for 120. The snippet
  now runs under the requested budget, capped at the provider's `withMaxTimeout` (5 minutes by
  default), and `Execute` without `timeoutSeconds` defaults to the session's
  `SessionLimits.toolTimeoutDefault()`. New: `ReplSession.execute(String, Duration)`.
- **A timed-out JShell snippet was reported as an ordinary failure, and one with a budget beyond
  the call timeout looked like a broken sandbox.** The sandbox stopped a snippet that outlived its
  budget, but its reply carried no timeout flag, so `Execute` reported `timedOut=false` and never
  printed `TIMEOUT`, unlike the process runtimes. It now reports `timedOut=true` and keeps the
  snippet's output up to the stop. The host also waited for an execute's reply only
  `JvmSandboxConfig.callTimeout()` (60 s by default), so a snippet given a longer budget came back
  as `Call timed out` while it still ran, and the next execute was refused as concurrent. The host
  now waits for the snippet's budget, the stop grace and the call timeout. A sandbox that does not
  answer even then is closed, and the execute returns `timedOut=true` with `Sandbox did not answer
  the execute within <wait>; the sandbox is closed`.
- **`JShellExecutionProvider`: a session ended while it started could free its slot twice, and a
  closed provider could keep a live sandbox.** If `onSessionEnd` ran while the session's startup
  snippet was running and the snippet then failed, the end and the failed start each gave the
  session's slot back, so the provider then admitted one session more than
  `withMaxConcurrentSessions` allows. A `close()` that ran while a session's sandbox was spawning
  left that sandbox running in the closed provider and its start accepted. The slot is now freed
  only by whoever removes the session, and a start that finds the provider closed once its sandbox
  is up closes it and refuses with `provider is closed`.
- **A provider given no `ModelConfig` failed with a `NullPointerException`.** `AnthropicProvider`
  and `OpenAIProvider` read `config.baseUrl()` while resolving a model id they do not catalogue, so
  `create("custom-id", null)` threw a bare `NullPointerException`; `GeminiProvider` reported the
  model as unsupported instead. Every provider now checks for a configuration first and fails with
  `IllegalArgumentException("config is required")`.
- **`CommandGrant`: a timed-out process could leave a descendant running and stall the call.** On
  timeout the grant terminated the child before looking up its descendants. If the child died
  first, its descendants were no longer listed, so a grandchild that held stdout survived, and the
  call waited for it to exit on its own. Descendants are now signalled before the child, as
  `LocalProcessExecutionProvider` already did.
- **Cleaning up a process's working directory could throw.** A child that left a directory it
  could not read (mode `000`) made `CommandGrant` and `LocalProcessExecutionProvider` throw
  `UncheckedIOException` from cleanup, losing the call's result. Cleanup is best-effort and no
  longer throws.
- **`AgentSession.events()`: subscribing after the session ended threw, and `GET
  /sessions/{id}/events` on a terminated session broke the response mid-stream.** The session shut
  its publisher's executor down at terminal, so a later `subscribe` threw
  `RejectedExecutionException`; over HTTP that happened after the `200` was sent, and the client
  saw a truncated chunked body with neither `Ready` nor `LoopEnded`. A subscriber that attaches
  after the terminal `LoopEnded` was emitted now receives that same event once it requests, then
  `onComplete`; one that attaches after a session ended without a `LoopEnded` (closed before it
  started, or the loop escaped with an `Error`) is completed at once. `subscribe` never throws for
  a non-null subscriber and no subscriber sees `LoopEnded` twice. The SSE route therefore answers
  `Ready`, `LoopEnded` and a clean end of stream, as `SessionRegistry` always documented.
- **An event emitted by the thread that attached the session's first subscriber could be held
  back until the next event.** `java.util.concurrent.SubmissionPublisher` (observed on JDK 25.0.3)
  gives the first subscribing thread a faster publishing path that can leave an item in the buffer
  with no consumer task until the next item or the close. A session whose first
  `events().subscribe(...)` was made on a thread that later emits, such as a hook or a tool
  running on the agent loop, could therefore hold a `QuestionAsked` back while the session waited
  for its answer. The session's publisher no longer lets any emitting thread take that path.
- **`LocalProcessExecutionProvider.close()` could miss a subprocess that a concurrent `execute`
  was still launching.** `close()` marks the provider closed and scans the in-flight processes
  once; a call that had passed its closed check and started its process after that scan was never
  reaped, and the child ran on until its own timeout. The launching call now reaps it and returns
  the same killed-process result a running call gets when the provider closes.
- **`CircuitBreaker`: a failed half-open probe could be followed at once by a second probe.** The
  breaker reopened before it recorded the new failure time, so a concurrent caller judged the
  half-open delay against the previous failure and moved the circuit straight back to `HALF_OPEN`.
  The failure time is now recorded first.
- **`CircuitBreaker`: a successful probe could be forgotten.** A caller that lost the race to move
  the circuit to `HALF_OPEN` still reset the success count, discarding a probe that had already
  succeeded when `successThreshold` is above 1. Only the caller that makes the transition resets
  it.
- **`JvmSandbox`: a snippet that outlived its timeout kept running inside the sandbox until the
  sandbox exited.** On timeout the bootstrap interrupted its own eval thread before calling
  `JShell.stop()`. That ended the wait but never reached the snippet, and by the time `stop()` ran
  it no longer did anything. A blocked snippet stayed parked, a looping one kept spinning, and the
  request's remaining statements ran after the timeout. Now the snippet is stopped through
  `stop()`, repeated until the snippet and every thread it started have ended, because a stop
  issued while JShell is still compiling or starting the statement is lost. No later statement
  of the request runs. A snippet still running once the stop grace has passed
  (`JvmSandboxConfig.stopGrace`, 5 s by default) is blocked where nothing in the JVM can reach it,
  for example entering a monitor held by a thread outside the snippet. The sandbox answers that
  execute with `exitCode 1`, `Execution timed out` and a line saying the snippet could not be
  stopped, then halts with exit code 3 without running shutdown hooks, so a hook the snippet
  registered cannot keep it alive. A later `execute` fails, and its message gives the exit code
  and, for code 3, the cause.
- **The ONNX model downloader waited on the operating system to give up on a connection.** Its
  HTTP client had no connect timeout, so a connection attempt to Hugging Face that went
  unanswered lasted as long as the host's TCP settings allowed. It now takes its client from
  `HttpClientFactory.createForDownloads()`, and a connection attempt fails after 10 seconds.
- **Schemas, tool definitions and replayed tool calls reached providers in a different order in
  every JVM.** A record's schema properties, `JsonSchema.object()` builds, the provenance schemas,
  `Tool.parametersAsJsonSchema()`, tool-call and `MutateArgs` arguments,
  `Provenanced.provenanceByField()` and the REPL's bindings were frozen with `Map.copyOf`, and
  OpenAI's strict-schema transform rebuilt every map as a `HashMap`. Each now keeps declaration
  order (record components, builder insertion, parameter lists, parsed JSON), so request bodies and
  messages to the model are byte-identical across JVMs. This fixes prompt-cache misses across
  restarts and across instances, and structured output generated out of its declared field order.
  `ExecutionProvider.supportedRuntimes()` of the built-in providers, which an `Execute` refusal
  names, iterates in `Runtime` declaration order, and `ReplSession.calledHostFunctions()` is sorted
  by name. The sandbox's `countBy` helper lists its keys in the order they first occur, the
  arguments of `QueryEvent.ToolMutated` and `HeliosEvent.ToolCallStarted`, the attributes of
  `HeliosEvent.RunStarted` and the data of `HeliosEvent.Custom` keep their given order in the
  session stream and the JSONL event log, the runtime's `POST /sessions` and result bodies list
  their fields in a fixed order, and REPL bindings still reject a null name or repr.
- **`Prompt.variables` lost the first-occurrence order `Prompt.extractVariables` documents.** It
  was frozen with `Set.copyOf`, so a prompt's variables, and the `variables` array
  `PgPromptRegistry` stores and reads back, came in a different order in every JVM. They now keep
  the order they first occur in the template, through the database round trip.
- **OpenAI forced a random tool when `ToolChoice.required` named several.** It sent the first name
  a hash set returned as a single forced function. Several names are now sent as the Responses
  API's `allowed_tools` choice, `{"type": "allowed_tools", "mode": "required", "tools": [...]}`, in
  the given order; one name is still sent as `{"type": "function", "name": ...}`.
- **The sandbox verifier named an arbitrary one of nested denied packages.** With `java.lang` and
  `java.lang.reflect` denied, a reference to `java.lang.reflect.Method` was labelled with either
  package depending on the JVM run. It is now labelled with the most specific one,
  `deniedPackages:java.lang.reflect`.
- **A changed system prompt or tool set no longer breaks a conversation on Claude models that bind
  reasoning.** Fable 5.1, Opus 5.5 and Sonnet 5.5 bind each thinking block to the system prompt,
  tools and messages before it, so a deploy that edits the prompt, a stored conversation reopened
  under a new tool set, or a tool shown only on some turns made the next request fail on accounts
  created from 2026-08-31. Thinking is now dropped from every assistant turn up to the last one
  produced under another prompt, oldest first as Anthropic documents, and requests ask the API to
  verify the rest.
- **An expired Gemini interaction no longer fails the turn.** A continued request answered `404`
  before any event is resent once with the whole conversation, logged at INFO with the interaction
  id; a turn produced under another system instruction or tool set is never continued, since an
  interaction keeps the ones it was created with.

### Security

- **A deny rule on a path can no longer be bypassed.** It was bypassed by spelling the path
  differently (`secrets/key`, `docs/../secrets/key`, an absolute path, a symlink), by swapping a
  symlink in after approval, or by searching or listing from a directory above it (`Grep .`,
  `LS .`). Path tools now key on the canonical path they touch (see Breaking).
- **A deny rule on `Execute` no longer misses every command after the first, and the approval
  prompt shows what runs.** `Execute` was keyed on the script's first token, so with deny
  `Execute(BASH/rm)` the script `ls; rm -rf ~/` was keyed `BASH/ls;`, no rule matched, and the
  user was asked `Allow Execute(BASH/ls;)?` without seeing the script; `Allow` ran all of it. The
  key is now the whole script and the question shows the call's arguments, redacted and bounded
  (see Breaking).
- **A deny rule no longer misses a key that holds a line break.** `RuleMatcher` compiled a rule's `*`
  and `**` to a pattern whose `.` did not match a line terminator, so a deny rule missed a key
  holding one: `Write(/etc/**)` did not match `Write(/etc/cron.d/x\ny)`, and the mode's default,
  such as `ACCEPT_EDITS`, allowed the write. A rule's wildcards now match across line terminators.
- **`helios-runtime` and `helios-persistence` resolve Helidon 4.5.5 (was 4.4.1).** No advisory is
  filed against 4.4.1; the upgrade fixes framing. Through 4.5.3 a response carrying `Connection:
  close` did not end the connection: Helidon skipped draining the request body and then parsed the
  body's bytes as the next request, so a body smuggled a request even without `Expect:
  100-continue`. 4.5.4 is the first release that flushes such a response and ends the connection;
  4.5.5 is the latest 4.x release and also stops trusting a client's own `X-Forwarded-For` and
  `X-Forwarded-Port` on a PROXY-protocol listener. `AgentHttpService`'s close of a connection whose
  body it left unread relies on the 4.5.4 behaviour; an application that pins its own Helidon must
  move to 4.5.4 or later. Helidon 4.5.5 and 4.5.6 still reset the connection after an `Expect:
  100-continue` request answered without a `100 Continue`, so its body is parsed as a new request;
  `Http1Config.continueImmediately(true)`, which `RuntimeServer` sets, prevents it by sending
  `100 Continue` at once.
- **A registered secret no longer reaches the model or the session's events through paths 2.x left
  unredacted.** In 2.x each component redacted only when handed its own registry, so a registered
  value leaked through: `Read` and `Grep` built by `SessionPresets` (no redactor); every custom
  tool, `MemoryRead` and a `PostToolUse` `MutateResult`; the text of tool failures built from
  exception messages (`threw:`, `tool dispatch failed:`, `Tool execution failed:`); the messages in
  `TurnRetried` and terminal `SerializedError` cause chains and the reason of
  `ErrorProviderUnavailable` (which carried the JShell startup snippet's raw output); the error
  message a model stream failed with after completing a tool call, which became the turn's assistant
  message and so reached the next request; `ToolResult.data`, which the runtime's SSE stream
  serialised unredacted; the prefix of a secret that straddled an output cap, since output was
  capped before it was redacted; and the stdout, stderr and code a `SandboxBindingsListener`
  received. The session now redacts every tool result and every error it reports against its one
  registry, a cut stream drops a trailing partial secret first, and `data` and the listener are gone
  (see Breaking).
- **A registered secret no longer reaches a log handler through a hook's or a tool's failure, or an
  event subscriber through a tool call's arguments.** The `WARNING` of a throwing hook or
  `ContextCompactor`, the `SEVERE` of a tool that threw after its call was answered and the
  `WARNING` of a throwing `onSessionEnd` attached the raw exception; they now attach a copy whose
  messages are redacted and whose frames are the original's. `ToolUse`, `ToolMutated`, `ToolBlocked`
  and `ToolResult` carried the arguments as the model or a hook wrote them; they now carry them
  redacted, in keys and in string values at any depth. The tool, its `PreToolUse` and `PostToolUse`
  hooks and the history still see them unredacted.

- **`helios-persistence` resolves the PostgreSQL JDBC driver `org.postgresql:postgresql` 42.7.12
  (was 42.7.7).** 42.7.7 is inside the affected range of two pgJDBC advisories:
  [GHSA-98qh-xjc8-98pq](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-98qh-xjc8-98pq)
  (CVE-2026-42198, affected 42.2.0 up to but not including 42.7.11: a server can demand an
  unbounded SCRAM-SHA-256 PBKDF2 iteration count and exhaust client CPU) and
  [GHSA-j92g-9f8w-j867](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-j92g-9f8w-j867)
  (CVE-2026-54291, affected 42.7.4 through 42.7.11: `channelBinding=require` is silently
  downgraded to SCRAM-SHA-256 without channel binding when the server certificate's signature
  algorithm has no `tls-server-end-point` hash). 42.7.12 is the first release outside both ranges
  and changes nothing else. Two connections that 42.7.7 accepted are now refused with SQLState
  `08004`: a role whose SCRAM iteration count exceeds 100,000 (raise the driver's
  `scramMaxIterations` connection property for a server you trust), and `channelBinding=require`
  against a server certificate signed with Ed25519, Ed448 or another algorithm without a
  channel-binding hash (use an RSA or ECDSA certificate). An application that pins its own driver
  version must move to 42.7.12 or later itself.
- **Every module that maps JSON resolves Jackson `tools.jackson.core:jackson-databind` and
  `jackson-core` 3.1.7 (was 3.0.4), with `jackson-annotations` 2.21 (was 2.20).** The advisory scan
  described below reported 20 advisories against 3.0.4, six on `jackson-core` and fourteen on
  `jackson-databind`, the highest scored 8.7 (GHSA-6v53-7c9g-w56r and GHSA-r7wm-3cxj-wff9, parser
  constraint bypasses) and 8.1 (GHSA-j3rv-43j4-c7qm and GHSA-rmj7-2vxq-3g9f, polymorphic type
  validator bypasses). The 3.0 line has no later release; the fixes shipped in 3.1.x and 3.2.x,
  and 3.1.7 is the lowest release outside every range. Jackson 3.1 keeps JDK 17 as its floor and
  the Apache-2.0 license, and changes three defaults an application sharing Helios's Jackson may
  notice: `StreamReadConstraints.maxStringLength` rises from 20M to 100M, a missing
  `AtomicReference<T>` property deserializes to an empty reference instead of `null`, and
  `JsonNode.asXxx(defaultValue)` returns the default for a JSON `null`.
- **The build inventories its resolved dependencies and CI fails on a known advisory.** `mvn
  verify` writes two CycloneDX files at the reactor root, `target/helios-runtime.cdx.json` (what
  the deployed modules ship) and `target/helios-build.cdx.json` (the same plus test scope and the
  example modules). CI scans both with OSV-Scanner on every push, pull request and once a day;
  any advisory fails the build unless `config/security/advisory-exceptions.toml` holds a reasoned
  exception that expires within 90 days, and a scan that could not run fails distinctly from one
  that found something. Policy and blind spots: `CLAUDE.md`, "Supply-chain gate".

## [2.12.0] — 2026-10-01 — Claude Opus 5.5, Sonnet 5.5, the GPT-6 family and hardened confinement

The last 2.x feature release. 3.0 renames the root package and removes the compatibility
shims 2.x accumulated.

### Added

- Anthropic: `claude-opus-5-5` and `claude-sonnet-5-5` (1M context, 128K output). Opus 5.5 always
  thinks (`ThinkingShape.ALWAYS_ON`); Sonnet 5.5 gets the new
  `ThinkingShape.ADAPTIVE_BETWEEN_TOOLS`, whose `ThinkingLevel.NONE` sends a bare
  `thinking.type=between_tools` because the model rejects `disabled`. Both reject forced tool use,
  so `AnthropicModel` fails fast at construction for `ToolChoice.any()` / `required(...)`.
  On Opus 5.5, `ThinkingLevel.NONE` omits `thinking` and `output_config`, and the API's default
  effort there is `medium`, not `high` — set a level to choose the effort explicitly.
- `AnthropicPricing.calculator(cachePolicy)`: a `CostCalculator` carrying Anthropic's list price
  for every model in `AnthropicModelId` (dated snapshots included), with per-model cache-read
  rates (0.025× on Fable 5.1 / Mythos 5.1, 0.05× on Opus 5.5, 0.10× elsewhere) and the
  cache-write rate matching the policy's TTL. `AnthropicPricing.AS_OF` records when the rates were
  last checked. Opt-in: `SessionOptions` still defaults to `CostCalculator.ZERO`.
- `ResultMessage.Refusal.category()` (and `categoryOpt()`) carries the provider's refusal
  category — Anthropic's `stop_details.category` (`cyber`, `bio`, `frontier_llm`,
  `reasoning_extraction`, `general_harms`, or absent). When the model declined before producing
  any text, `refusalText` is now the provider's explanation instead of `[refused without text]`;
  the explanation also takes precedence over output the refusal cut short.
  Providers report both through `Response.REFUSAL_CATEGORY_KEY` / `REFUSAL_EXPLANATION_KEY`
  metadata.
- OpenAI: `gpt-6-astra`, `gpt-6.1-sol`, `gpt-6-sol` and `gpt-6-luna` (1,050,000 context, 128K
  output). `gpt-6-astra` and `gpt-6.1-sol` reject `reasoning.effort=none`
  (`EffortSupport.FULL_WITHOUT_NONE`), so `ThinkingLevel.NONE` sends `low` there; `gpt-6-sol` and
  `gpt-6-luna` take the full `none`..`max` range.

### Changed

- Sessions now surface thinking. The default `Model.chatStream(..., CancellationToken)` chunk
  sequence starts with a `ModelChunk.ThinkingDelta` when the response has thinking text, so
  `QueryEvent.AssistantThinking` fires for every provider; it never fired under the session loop
  before. This matters most on Fable 5.1, Opus 5.5 and Sonnet 5.5, which return the notes they
  write between tool calls as `thinking` blocks rather than `text`.
- Anthropic: always-on models (Fable 5 / 5.1, Mythos 5 / 5.1, Opus 5.5) send
  `thinking: {"type":"adaptive","display":"summarized"}` with the effort for every
  `ThinkingLevel` except `NONE`. They previously omitted the field, which left the API's
  `omitted` display in force and every thinking block empty. `NONE` still omits it.
- `StopClassifier.classify(SessionState, SessionLimits, TurnOutcome, boolean)` replaces the
  seven-argument overload, which is deprecated and delegates.
- OpenAI: `temperature` and `top_p` follow one rule — sent without a reasoning config or at
  `reasoning.effort=none`, dropped otherwise. `top_p` used to ride every request, which the GPT-6
  family rejects with a 400 while reasoning; `temperature` used to be dropped at effort `none`,
  silently ignoring it for `ThinkingLevel.NONE` on gpt-5.4 and later.

### Fixed

- Anthropic: an uncatalogued release no longer inherits the request rules of the model its ID
  extends. `AnthropicModelId.fromWireId` treated any `<known-id>-<suffix>` as a snapshot, so
  `claude-opus-5-5` and `claude-sonnet-5-5` resolved to Opus 5 / Sonnet 5 and sent
  `thinking.type=disabled`, which both models reject with a 400. Only an eight-digit snapshot
  date (`claude-haiku-4-5-20251001`) now resolves to its family.
- Anthropic: a turn whose thinking blocks are interleaved with text or parallel tool calls, or
  that holds a `redacted_thinking` block, is echoed back in its original block order. The typed
  echo hoisted every thinking block to the front of the turn and dropped redacted blocks; the API
  requires both back unmodified and in place. Such turns keep their penultimate-message cache
  breakpoint when they end in a tool call.
- Anthropic: web search no longer fails its next turn with a 400 when the model filters results
  in code. The `web_search_20260318` tool runs code execution under the hood, and the
  `code_execution_tool_result` blocks it returns were dropped from the echoed turn, leaving their
  `server_tool_use` unpaired. Every server-side `*_tool_result` block is now captured verbatim.
- Anthropic: a retryable API error reported after the stream opened (`overloaded_error`,
  `api_error`, `timeout_error`, `rate_limit_error`) now surfaces as `TransientStreamException`,
  so the session retries the turn under `SessionLimits.streamRetryPolicy` instead of ending in
  `ErrorDuringExecution`.
- Anthropic: the iterator-based `chatStream` no longer emits an empty `StreamEvent.ThinkingDelta`
  for each thinking block returned under the `omitted` display, and a `thinking_delta` that arrives
  ahead of its `content_block_start` is no longer discarded.
- Sessions: a refused turn (`FinishReason.REFUSAL` / `CONTENT_FILTER`) no longer runs tool calls it
  had emitted before the refusal. The loop used to dispatch them and continue, which executed
  output the provider says to discard and lost the refusal.
- `CancellationToken` registration is now linearizable and exactly-once. A private lock guards the
  reason and the pending-registration set, so a callback registered while another thread is
  cancelling can no longer be dropped (the old `CopyOnWriteArrayList` snapshot-then-clear lost
  registrations that landed between the two). Callbacks still run outside the lock, so reentrant
  register/remove/cancel from a callback cannot deadlock. Clarified contract: `Registration.remove()`
  ordered after the winning `cancel` is a no-op that never blocks on a running callback; a callback
  throwing an `Error` no longer skips the remaining callbacks — they all run, then the first `Error`
  is rethrown with later ones attached as suppressed, matching the session loop's "Errors escape"
  rule. No public-surface change.

### Compatibility

- `ResultMessage.Refusal` gains a sixth record component, `category`. The five-argument
  constructor remains; record deconstruction patterns over `Refusal` need the extra binding.
- `DropMiddleToolResultsCompactor` is not safe on Fable 5.1, Opus 5.5 or Sonnet 5.5 for Anthropic
  accounts created on or after 2026-08-31: those models bind each thinking block to the history
  that produced it, and the first request after a compaction is rejected with a 400. 3.0 replaces
  it with `SummarizingCompactor`, which carries no earlier assistant turn. Changing the visible
  tool set mid-session has the same effect.

### Security

- `PolicyBytecodeVerifier` now applies the sandbox policy to indirect references. A method
  reference such as `FileReader::new` compiles to an `invokedynamic` whose bootstrap argument is a
  `REF_newInvokeSpecial` method handle, and no ordinary invoke instruction names the constructor;
  the previous scanner ignored `invokedynamic` entirely and only inspected class-literal constants,
  so `SandboxPolicy.noEgress()` let such a reference read a file. The verifier now walks every
  `invokedynamic` bootstrap, method-handle constant and dynamic constant (nested arguments
  included) at the constant-pool-entry level and checks each class, owner and member against the
  same rules as direct instructions. Array class literals are judged by their element class.
  Traversal is bounded (32 nested dynamic constants), shared constants are memoized with their
  checked depth to prevent exponential work, self-referencing constants are rejected, and
  a classfile the Classfile API cannot parse is rejected instead of skipped; the new rule labels are
  `dynamicConstantDepth`, `dynamicConstantCycle` and `malformedClassfile`.
- **Compatibility:** language bootstraps (`LambdaMetafactory`, `StringConcatFactory`,
  `ObjectMethods`, `SwitchBootstraps`, `ConstantBootstraps`) remain trusted as dispatchers, so
  lambdas, method references to allowed APIs, records, string concatenation and pattern switches
  (including qualified enum labels) are unaffected under every policy. Newly rejected constructs
  are exactly the method references, method-handle constants and dynamic constants that name an
  API the policy already denied when called directly; a bootstrap method outside the language set
  is now checked like a direct static call. No API change.
- README "Sandbox security model" now states the trust boundary explicitly — bytecode checks are
  defense in depth, and a same-user subprocess with a private working directory is not filesystem
  or network isolation — and documents a least-privilege rootless Podman deployment for the
  process that owns the sandbox. `IsolatedDeploymentTest` exercises that recipe against a host
  sentinel file and a host TCP listener, skipping with a reason where rootless Podman is
  unavailable.

- Workspace and memory I/O now use descriptor-relative Linux operations through JDK 25 FFM.
  Every ancestor is opened no-follow, files are pinned with `O_PATH` and verified regular before
  I/O, and directory creation/deletion use `mkdirat`/`unlinkat`. This closes ancestor-symlink races
  without opening FIFOs or devices. Walkers and `Ls` enumerate pinned directories; bounded reads
  validate the opened target and enforce limits during growth. `Grep` sniffs and searches one
  bounded buffer; `Read` classifies MIME without platform pathname probes. Host native-access
  grants are not forwarded to sandbox child JVMs.
- **Compatibility:** strict workspaces and filesystem memory require Linux x86-64/AArch64,
  mounted `/proc/self/fd`, the default filesystem and `--enable-native-access=ai.singlr.session`
  (module path) or `--enable-native-access=ALL-UNNAMED` (classpath). Unsupported configurations
  fail closed. The explicit portable `new WorkspaceRoot(root, false)` remains weaker trusted
  mode, never a preset default; filesystem memory remains strict even with that workspace.
  New files/directories are owner-only; strict output rejects `DELETE_ON_CLOSE`. Default output
  create/truncate semantics and covariant option arrays are preserved.
- Roots and resolved paths are canonical, including explicitly configured aliases. Outside,
  dangling and looping links are rejected before side effects. In-workspace aliases remain
  usable by workspace tools, but every symlink in a memory path is refused, including aliases
  within the workspace. Memory reads require valid UTF-8; unencodable writes fail before opening
  a target or creating directories. `Glob` no longer lists symlinks. Confinement is not a snapshot
  or protection against hard links, mount manipulation or in-place edits by another writer.

## [2.11.0] — 2026-09-04 — Claude Fable 5.1, Mythos 5.1 and Gemini 3.8 Flash

### Added

- Anthropic: `claude-fable-5-1` and `claude-mythos-5-1` (1M context, 128K output, always-on
  adaptive thinking). Both reject forced tool use, so `AnthropicModel` fails fast at construction
  when `ModelConfig.toolChoice` is `any()` or `required(...)` on them —
  `AnthropicModelId.acceptsForcedToolChoice()` records the rule. `auto()` and `none()` are
  unchanged.
- Gemini: stable `gemini-3.8-flash` (1,048,576 context, 65,536 output, `low`/`medium`/`high`
  thinking levels).

## [2.10.1] — 2026-09-01 — Gemini privacy controls and endpoint selection

### Added

- Gemini managed Files API uploads expose both a model-ready `FileReference` and the validated
  provider resource name. `ManagedFile` is `AutoCloseable`; deletion is idempotent, treats an
  already-absent resource as success, never follows redirects, and never derives its target from a
  provider file URI.
- `ModelConfig.withApiVersion(...)` selects Gemini Interactions `v1` or `v1beta` explicitly. The
  effective version is fixed when the model is built and available through non-sensitive response
  metadata and `GeminiModel.apiVersion()` diagnostics.
- Privacy controls now include stateless provider interactions, metadata-only JSONL events, and an
  opt-out policy for retaining raw model output in structured-output exceptions.

### Fixed

- Gemini stateless mode resends local conversation history without sending or propagating
  `previous_interaction_id` values.
- Gemini video documentation selects `v1beta`, matching the currently working Files API video
  contract, without automatic cross-version replay after a rejected stable request.
- Gemini Files API validation now enforces Google's 2 GB per-file limit instead of the aggregate
  project-storage limit.

## [2.10.0] — 2026-08-30 — Stable Gemini Interactions and video understanding

### Added

- Gemini video understanding through the Files API and stable Interactions API. The new
  `GeminiFilesClient` performs resumable, file-backed uploads with bounded HTTP response reads,
  disabled redirects, same-origin upload-session validation, processing-state polling, failure
  propagation, and a configurable processing timeout. It returns a provider-neutral
  `FileReference` that can be attached through `Message.Builder.withFileReferences(...)` or
  `UserMessage.Builder.withFileReference(...)` without buffering the video in heap.

### Changed

- Gemini Interactions now target the generally available `/v1/interactions` endpoint and no
  longer send the obsolete `Api-Revision` header. `temperature` and `topP` fail fast because the
  stable Interactions generation schema does not support them.
- Anthropic and OpenAI reject URI file references explicitly instead of silently dropping them.

## [2.9.2] — 2026-08-21 — Thinking shapes aligned with current provider docs

No breaking changes.

### Fixed

- OpenAI: `ThinkingLevel.NONE` now sends `reasoning.effort="none"` on every model whose page
  documents it (gpt-5.5, gpt-5.4, gpt-5.4-mini, gpt-5.4-nano) instead of omitting `reasoning`
  and silently running the model's default effort (medium on gpt-5.5). `gpt-5.4-mini` and
  `gpt-5.4-nano` document `xhigh` and no longer clamp it to `high`.
- Gemini: thinking levels resolve per model. `gemini-3.7-flash` and `gemini-3.1-pro-preview`
  document `low`/`medium`/`high` only, so `MINIMAL` now sends `low` there instead of an
  unsupported `minimal`. `ThinkingLevel.NONE` now pins the model's lowest documented level
  (`GeminiModelId.lowestThinkingLevel()`: `minimal` or `low`) — Gemini 3.x cannot turn
  thinking off, and omitting the field ran the model default (medium on 3.5/3.6/3.7 Flash,
  high on 3.1 Pro).

### Changed

- Anthropic: Claude Opus 4.6 and Sonnet 4.6 move from the deprecated
  `thinking.type=enabled` + `budget_tokens` shape to `adaptive` + `output_config.effort`
  (new `ThinkingShape.ADAPTIVE_WITHOUT_XHIGH`). `ThinkingLevel.MAX` is now honoured on 4.6;
  `XHIGH` still fails fast. Sampling parameters remain accepted on 4.6 while thinking is off
  (`ThinkingShape.acceptsSamplingParameters()`). `LEGACY_BUDGET` now covers Haiku 4.5 only.

## [2.9.1] — 2026-08-21 — Submit validator enforced in the session loop

No breaking changes.

### Fixed

- `OutputSchema.submitValidator` is now enforced on the plain `AgentSession` path, not only on
  the CodeAct `Submit` tool. `StructuredContentParser.parse` runs the validator after structural
  validation and raises the new `SubmitValidationException` (a `StructuredOutputParseException`
  subtype), so the session loop's existing schema self-correction appends the rejected attempt,
  injects the validator's correction as a synthetic user turn, and retries within
  `SessionLimits.maxTurns()`. A validator that never passes terminates as `ErrorMaxTurns`;
  `runBlocking(message, schema)` can no longer return a value the validator rejects.
- `SubmitValidator.validateSafely` turns a throwing or null-returning validator into a failure
  verdict; the parser and the CodeAct `Submit` pipeline share it.

### Added

- Gemini: stable `gemini-3.7-flash`.
- Anthropic: `claude-haiku-4-5` (200K context, 64K output, legacy `budget_tokens` thinking).

### Corrected

- Claude Opus 4.8 / 4.7 / 4.6 and Sonnet 4.6 maximum-output metadata now reflects their
  documented 128K-token output limit (was 32K / 32K / 32K / 64K).

## [2.9.0] — 2026-07-28 — Current model catalogs and configurable Anthropic caching

No breaking changes.

### Added

- OpenAI: canonical `gpt-5.6-sol` support while retaining the documented `gpt-5.6`
  alias.
- Gemini: stable `gemini-3.6-flash` and `gemini-3.5-flash-lite` support. Image-generation
  models remain outside Helios's text-model catalog.
- Anthropic: Claude Opus 5 and limited-access Mythos 5 with their documented thinking
  behavior.
- `AnthropicProvider.create(modelId, config, cachePolicy)` exposes the existing
  `CachePolicy` through the provider API. Managed five-minute prompt caching remains the
  default; callers can select the one-hour TTL or disable caching.
- `CostCalculator.Pricing.anthropicCaching5m(...)` and
  `anthropicCaching1h(...)` apply Anthropic's TTL-specific 1.25× and 2× cache-write rates.
  The existing `anthropicCaching(...)` factory remains a five-minute alias.

### Corrected

- Claude Fable 5 and Sonnet 5 maximum-output metadata now reflects their 128K-token
  output limit.
- Prompt-cache documentation now reflects that Anthropic rejects requests with more
  than four cache breakpoints rather than silently ignoring the extras.

## [2.8.1] — 2026-07-18 — Release-hygiene patch

Identical library code to 2.8.0. Cut because the 2.8.0 release pipeline produced two
Central Portal deployments (a failed status-poll left a stale pending deployment that was
published alongside the retry) — **consume 2.8.1, not 2.8.0**. Pipeline hardening so this
cannot recur:

- The release workflow refuses to deploy a version that already exists on Maven Central
  and serializes runs under a concurrency group — one version can only ever produce one
  publishable deployment after a successful publish.
- Central Portal deployments are now named `helios-<version>` instead of the generic
  "Deployment", so a pending duplicate is visible before anyone clicks Publish.
- (From the 2.8.0 pipeline fixes, restated: `central-publishing-maven-plugin` 0.7.0 →
  0.11.0 for the Portal's new `warnings` response field; `helios-testing` added to the
  published modules.)

## [2.8.0] — 2026-07-18 — Current-generation models: Fable 5, Sonnet 5, GPT 5.6; web tools; caching

### Breaking

- **`ModelConfig` canonical constructor changed**: `googleSearch`/`urlContext` become the
  provider-neutral `webSearch`/`webFetch`, and a new `promptCacheKey` component is added.
  Deprecated accessor and builder aliases (`googleSearch()`, `urlContext()`,
  `withGoogleSearch`, `withUrlContext`) keep existing callers compiling; builders are
  unaffected otherwise.
- **`FinishReason` gains `REFUSAL`** (provider safety decline, e.g. Claude Fable 5's
  `stop_reason: "refusal"`) — exhaustive `switch` consumers must add a branch. The
  session loop classifies it to the existing `ResultMessage.Refusal` terminal.
- **`AnthropicModelId.usesAdaptiveThinking()` and `OpenAIModelId.supportsXhighEffort()`
  are deprecated**, replaced by `thinkingShape()` (four-way enum) and `effortSupport()`
  (three-tier enum); both constructors changed accordingly.
- **`ContentBlock.content` (anthropic wire record) widened `String` → `Object`** to carry
  server-tool result arrays.

### Added — provider-neutral web search / web fetch

`ModelConfig.withWebSearch(true)` / `withWebFetch(true)`:

- **Gemini** maps them to Google Search grounding and URL context (exactly the old
  `googleSearch`/`urlContext` behavior).
- **Anthropic** emits the `web_search_20260318` / `web_fetch_20260318` server tools
  (dynamic filtering; supported by every model in the enum). Results stream through the
  normal event flow; web citations land on `Response.citations()`; the turn's full
  content-block array — including `encrypted_content` — is preserved in message metadata
  (`anthropic.rawContent`) and echoed verbatim on later turns as the API requires.
  `pause_turn` is continued automatically inside `AnthropicModel` (blocking and streaming
  paths, bounded at 8 resumes) so consumers never observe the pause.
- **OpenAI** fails fast at model construction — helios-openai has no native web-tool
  support yet.

### Added — current-generation model catalogs

- **Anthropic**: `claude-fable-5` and `claude-sonnet-5`. The `usesAdaptiveThinking`
  boolean becomes `ThinkingShape` (`LEGACY_BUDGET` / `ADAPTIVE` / `ADAPTIVE_DEFAULT_ON` /
  `ALWAYS_ON`): Fable 5 always thinks (the `thinking` field is always omitted; any
  explicit config 400s; depth rides `output_config.effort`), Sonnet 5 runs adaptive when
  the field is omitted so `ThinkingLevel.NONE` sends an explicit `disabled`.
  Adaptive-family models (Opus 4.7+, Sonnet 5, Fable 5) never send `temperature`/`top_p`
  — also fixes a latent 400 when temperature was set with thinking off. Stop-reason
  mapping gains `refusal` → `REFUSAL` and `model_context_window_exceeded` → `LENGTH`; the
  raw stop reason rides `Response.metadata` under `anthropic.stopReason`.
- **OpenAI**: `gpt-5.6`, `gpt-5.6-terra`, `gpt-5.6-luna` (1,050,000-token context, 128K
  output). `EffortSupport` tiers replace the xhigh boolean: `ThinkingLevel.MAX` now maps
  to the real `"max"` on the gpt-5.6 family and `NONE` maps to the explicit `"none"`
  effort there (omitting `reasoning` would run the model's default medium reasoning).

### Added — OpenAI prompt-cache accounting

`ModelConfig.withPromptCacheKey(...)` rides requests as `prompt_cache_key` (required for
improved cache matching on gpt-5.6+; choose per-tenant/per-agent keys grouping shared
prefixes). Usage parsing reads the new `input_tokens_details.cache_write_tokens` subset
into `Usage.cacheCreationInputTokens`, keeping the canonical shape disjoint — existing
`CostCalculator.Pricing` cache-write rates now apply to OpenAI's newly billed (1.25×)
cache writes on gpt-5.6+ with no cost-model changes.

### Added — refusal scripting + dated-snapshot resolution

`ScriptedModel.thenRefusal(text)` scripts a `FinishReason.REFUSAL` turn so refusal
handling is testable in deterministic CI evals. `AnthropicModelId.fromWireId` resolves
dated snapshot IDs (e.g. `claude-sonnet-4-6-20251114`) to their family so legacy
snapshots keep legacy request semantics; truly unknown Claude IDs still default to the
adaptive shape (which also means sampling parameters are not sent for them — pin a
family ID or use a known model if a custom gateway needs `temperature`).

### Unchanged, verified against current provider docs

Anthropic prompt caching (`CachePolicy` 5m/1h/off with system/tools/message breakpoints)
and adaptive thinking + effort mapping (`low`…`max`, `display: "summarized"` pinned)
already match the current API surface. OpenAI automatic-cache reads were already
accounted disjointly.

## [2.7.0] — 2026-07-18 — Eval-readiness: usage/cost telemetry, prompt drafts, rollups, scripted Model

Four features driven by consumer gap analysis (prompt management, tracing, and evals for
production readiness), plus the grounding-citations work that was pending release.

### Breaking

- **`Trace` and `Span` canonical record constructors changed arity** (both gain `usage` +
  `cost` components). Code constructing either positionally must add the two arguments;
  builder call sites are unaffected. Persisted rows and the `Trace.Builder`/`Span.Builder`
  APIs remain compatible.
- **`PromptRegistry` gains two abstract methods** (`registerDraft`, `activate`). External
  implementations must add them. They are deliberately not defaults — a registry that
  silently cannot draft/activate would defeat the safe-iteration contract.
- **`QueryEvent.AssistantCitations`** is a new 17th `QueryEvent` subtype (breaks
  exhaustive `switch` consumers without a `default` branch) — see the citations entry
  below.
- **Session usage accumulation now fails fast on overflow.** `SessionState` accumulates
  per-turn usage via the new `Usage.plus`, which uses `Math.addExact`; a session whose
  cumulative token counters exceed `Integer.MAX_VALUE` now terminates with an error
  instead of silently wrapping negative (which corrupted budget math).

### Added — per-call Usage + cost on spans, rolled up on traces

`Span` and `Trace` gain typed nullable `usage` (`Response.Usage`, four disjoint token
classes) and `cost` (`CostEstimate`) components. `SpanBuilder.usage(...)`/`.cost(...)`
record them per model call; `TraceBuilder` rolls both up recursively across the span tree
at trace completion. `Trace.totalTokens` resolves per span, recursively: a span's typed
usage wins when present, otherwise the legacy `inputTokens`/`outputTokens` attributes
count for model-call spans — partially migrated instrumentation sums every span instead
of dropping the legacy ones (this also fixes a pre-2.7 gap where attribute tokens on
*nested* spans were never counted). Stored traces can now answer "tokens/cost by prompt
version", "cache hit rate", and "cost per eval group". `Usage.plus(Usage)` sums all five
components (provider-reported totals survive accumulation); `SessionState.accumulateUsage`
reuses it. Persistence stores the four token classes; a reconstructed `Usage`'s total is
their sum (the trace-level `total_tokens` column preserves the original trace total
independently).

**DDL delta** (additive, both tables):

```sql
ALTER TABLE helios_traces ADD COLUMN input_tokens INT,
  ADD COLUMN output_tokens INT, ADD COLUMN cache_creation_tokens INT,
  ADD COLUMN cache_read_tokens INT, ADD COLUMN cost_micro_usd BIGINT;
ALTER TABLE helios_spans ADD COLUMN input_tokens INT,
  ADD COLUMN output_tokens INT, ADD COLUMN cache_creation_tokens INT,
  ADD COLUMN cache_read_tokens INT, ADD COLUMN cost_micro_usd BIGINT;
```

Null columns mean "no usage recorded", distinct from a recorded zero.

### Added — draft prompt versions with explicit activation

`PromptRegistry.registerDraft(name, content)` creates the next version inactive — the
current active version stays live — and `activate(name, version)` promotes any version
(also the rollback path), throwing on unknown name/version. This makes
candidate-vs-baseline prompt evaluation race-free against a shared database: register a
draft, pin it in the eval runner via `resolve(name, version)`, and promote only when it
clears the threshold. Both new methods are implemented by `InMemoryPromptRegistry` and
`PgPromptRegistry`; external `PromptRegistry` implementors must add them.
`InMemoryPromptRegistry.resolve(name)` now returns the *active* version rather than the
newest (observable only once drafts exist).

The "at most one active version per name" invariant is now database-enforced via a
partial unique index, closing a race where concurrent `register` + `activate` could
commit two active rows under READ COMMITTED (the loser now fails loudly and retries):

```sql
-- Deduplicate first if a historical race left multiple active rows per name:
--   UPDATE helios_prompts p SET active = FALSE
--   WHERE active AND version < (SELECT MAX(version) FROM helios_prompts q
--                               WHERE q.name = p.name AND q.active);
CREATE UNIQUE INDEX idx_helios_prompts_single_active
    ON helios_prompts (name) WHERE active;
```

### Added — first-class trace rollups: `PgTraceStore.summarize`

`summarize(TraceRollupKey, TraceFilter)` aggregates stored traces per group — run/error
counts, duration p50/p95, the four token-class sums, cost, thumbs up/down — grouped by
`GROUP_ID`, `PROMPT` (name+version), `NAME`, or `MODEL_ID`, with optional equality and
`start_time` window filters (`TraceFilter`, all bind-parameterized). Eval summaries and
dashboards no longer need hand-rolled SQL against Helios's private schema. Traces with a
null grouping dimension are excluded from that rollup.

### Added — `helios-testing` module with `ScriptedModel`

New artifact `ai.singlr:helios-testing` (JPMS `ai.singlr.testing`) for deterministic CI
evals without a live provider. `ScriptedModel.newBuilder().thenText(...).thenToolCalls(...)`
replays a fixed turn script through the real `Model` contract: optional per-turn `Usage`,
structured-output calls parsed through the real `StructuredContentParser` path (tool-call
turns skip parse, matching provider behavior), fail-fast on script exhaustion, and full
per-call message capture via `calls()`.

### Note — optimizer events

`HeliosEvent.OptimizerCandidateProposed/Scored` remain declared-but-unemitted SPI. No
prompt optimizer ships in 2.7.0; consumers building candidate-vs-baseline loops should do
so product-side on `registerDraft`/`activate`/`resolve(name, version)` + eval groups.

### Added — grounding citations surfaced on the session path

Grounding citations (`ai.singlr.core.model.Citation`) the model harvests (e.g. Google
Search / web grounding) now flow all the way through the agent session, on **both**
surfaces:

- **Streaming:** new `QueryEvent.AssistantCitations(sessionId, turnIndex, timestamp,
  List<Citation>)` — fires once per turn at message stop, only when the turn produced
  citations. This is the 17th `QueryEvent` subtype (a deliberate breaking change for
  `switch` consumers without a `default` branch).
- **Terminal:** `ResultMessage.citations()` (interface default `List.of()`) is carried
  by `ResultMessage.Success`, reporting the run's citations accumulated across turns,
  deduplicated and in document order. A 6-arg canonical `Success` constructor adds the
  citations component; the prior 5-arg form remains as a convenience defaulting to empty,
  so existing construction sites are unaffected.

Plumbing: `ModelChunk.MessageStop` gains a `List<Citation> citations` component (4-arg
canonical; the 2-arg and 3-arg convenience constructors are retained), and the default
`Model.chatStream` adapter forwards `Response.citations()` onto it — no provider changes
required, since all providers already harvest citations into `Response`. The HTTP
`GET /sessions/{id}/result` JSON gains an additive `citations` array on `Success`.

Note for `AgentSession.runBlocking(message, schema)` users (e.g. enrichment agents): the
typed convenience returns only the parsed value; read citations from the streaming
`AssistantCitations` events or the `ResultMessage.Success` terminal.

## [2.6.4] — 2026-06-10 — Grounded streaming + structured output fix (Gemini)

### Fixed — grounded streaming + structured output crash (Gemini)

`GeminiModel.chat(messages, OutputSchema)` with `ModelConfig.withGoogleSearch(true)`
threw `GeminiException: Failed to parse stream event` →
`MismatchedInputException: Cannot deserialize value of type java.lang.String from
Object value` on every grounded turn, killing the stream before any structured
output could surface.

**Root cause.** A grounded turn emits a `google_search_call` step on the
`step.delta` surface whose `arguments` ship as a **JSON object** (confirmed on the
live wire, `Api-Revision: 2026-05-20`:
`{"type":"google_search_call","arguments":{"queries":[...]},"search_type":"web_search"}`).
The streaming delta carrier `ContentItem.arguments` was a bare `String` with no
deserializer, so the object could not be bound. (`Step.arguments` already tolerated
object-or-string via `ArgumentsDeserializer`, which is why the non-streaming
`step.start` shape never hit this.)

**Fix.** New `RawArgumentsDeserializer` on `ContentItem.arguments` normalizes both
wire shapes the field actually carries to a `String`: a partial JSON **string**
fragment on the `arguments_delta` variant of a streamed `function_call` is returned
verbatim (it is accumulated into a buffer and parsed only when the step completes,
so it must not be parsed early — this is why `ArgumentsDeserializer`'s parse-to-`Map`
could not be reused), and a complete JSON **object** on a `google_search_call` delta
is re-serialized to its compact JSON string form. The `google_search_call` step is
also fully modeled: `Step` gains a typed `search_type` field plus
`Step.googleSearchCall(...)` / `Step.googleSearchResult(...)` factories.

**Citations on grounded turns (verified).** Grounded structured output surfaces
`url_citation` grounding citations: they arrive as a separate `text_annotation_delta`
streaming delta (annotations, no text), which the existing type-agnostic
`harvestCitations` branch folds into `Response.citations()`. Confirmed
deterministically by replaying live wire through `StreamingIterator` — substantive
grounded structured queries harvested dozens of citations with real source domains.
(A trivial single-fact query may legitimately return zero citations because the
model chooses not to cite, which is model discretion, not a mode limitation.)
`GeminiGroundedStructuredOutputIntegrationTest` covers parse-without-crash and
prose citations end-to-end; `StreamingIteratorTest` locks in structured-mode
citation harvest deterministically.

## [2.6.3] — 2026-06-03 — Annotation model v2

### Changed — Annotation model v2 (structured, queryable, facet-aware)

`ai.singlr.core.trace.Annotation` is now a structured note that can carry a
consumer's full evaluative context, replacing the thin single-`targetId` record.
This is a **breaking** change with no compatibility shim — the framework's only
consumers are internal, so the column and signature renames are clean.

**Model.** The new record is:

```
Annotation(
  UUID id, UUID subjectId, String facet, String label, AuthorKind authorKind,
  String authorId, Integer rating, String comment, Map<String,Object> metadata,
  OffsetDateTime createdAt, OffsetDateTime updatedAt)
```

- `targetId` → `subjectId` — still a real Helios trace or span id (opaque domain
  ids were deliberately rejected; an annotation always targets something Helios
  observed).
- `facet` (nullable) — a named sub-coordinate *within* a subject, so one author
  can hold several judgments about one subject (e.g. one rating per evaluation
  dimension) without synthesizing target ids.
- `authorKind` — new `ai.singlr.core.trace.AuthorKind` enum (`HUMAN | MODEL |
  SYSTEM`), replacing stringly-typed author prefixes. Required.
- `metadata` — first-class `Map<String,Object>` persisted to a `jsonb` column by
  `upsertAnnotation`; always a non-null immutable map (empty when unset),
  tolerant of null values.
- `updatedAt` — new; advanced on every upsert while `createdAt` is preserved.

**Idempotency.** The upsert key is now `(subjectId, facet, label, authorId)`,
enforced by a partial unique index. Null facets coalesce (`COALESCE(facet, '')`)
so a null-facet judgment still dedupes; author-less annotations continue to
insert without deduping, exactly as before. One author may now hold the same
`(subject, facet)` under several distinct labels.

**Store API (`PgTraceStore`).** `upsertAnnotation(Annotation)` persists the full
record including `metadata`. `findAnnotations(UUID)` is replaced by
`findAnnotationsBySubject(UUID)`, `findAnnotationsBySubjects(List<UUID>)` (batch),
and `listAnnotations(Paginate, scimFilter)` (SCIM filter over the first-class
columns `subject_id`, `facet`, `label`, `author_kind`, `rating`). Filtering over
`metadata` keys is not yet supported.

**DDL delta for `helios_annotations`:**

```sql
ALTER TABLE helios_annotations RENAME COLUMN target_id TO subject_id;
ALTER TABLE helios_annotations
    ADD COLUMN facet       VARCHAR(255),
    ADD COLUMN author_kind VARCHAR(32)  NOT NULL DEFAULT 'HUMAN',
    ADD COLUMN metadata    JSONB        NOT NULL DEFAULT '{}',
    ADD COLUMN updated_at  TIMESTAMPTZ;
UPDATE helios_annotations SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE helios_annotations
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN author_kind DROP DEFAULT;

DROP INDEX IF EXISTS idx_helios_annotations_target;
DROP INDEX IF EXISTS idx_helios_annotations_target_author;
CREATE INDEX idx_helios_annotations_subject
    ON helios_annotations(subject_id);
CREATE UNIQUE INDEX idx_helios_annotations_subject_facet_label_author
    ON helios_annotations(subject_id, COALESCE(facet, ''), label, author_id)
    WHERE author_id IS NOT NULL;
```

Deployers that already ran a consumer-side `metadata jsonb` column keep it and
skip that `ADD COLUMN`. Any trace-feedback trigger that referenced
`NEW.target_id` must be repointed to `NEW.subject_id`.

## [2.6.2] — 2026-05-29 — Claude Opus 4.8 + bring-your-own model config

### Added — Claude Opus 4.8

`AnthropicModelId.CLAUDE_OPUS_4_8` (`claude-opus-4-8`, 1M context, 32K output,
adaptive thinking) joins the curated model set.

### Changed — Unrecognised Claude model IDs are accepted against the default endpoint

`AnthropicProvider` no longer requires a model ID to be a known enum constant.
Any ID starting with `claude` (`AnthropicModelId.CLAUDE_ID_PREFIX`) is now
accepted against the default Anthropic endpoint and passed verbatim as the wire
`model`, so a newly-released Claude can be used before this framework ships an
enum entry for it. Previously such IDs threw unless `ModelConfig.baseUrl` was
set. For an unrecognised ID with no curated metadata, the provider defaults to
the **adaptive** thinking shape and a **32,000**-token output ceiling
(`AnthropicModel.DEFAULT_MAX_OUTPUT_TOKENS`); both are overridable via
`ModelConfig.Builder.withMaxOutputTokens(...)`. `supports(modelId)` matches the
`claude` prefix as well as exact enum membership; non-Claude IDs (e.g. `gpt-5`)
still require `baseUrl` and otherwise throw. `AnthropicModelId.isSupported(...)`
remains strict enum membership — use `hasClaudePrefix(...)` for the family check.

### Added — `ModelConfig.withContextWindow(Integer)`

New optional config knob (defaults to `null`) letting callers declare a model's
total context-window size when the provider does not enumerate the model id. All
three providers honour it with the precedence **config override → known model
metadata → `0`/"unknown"** (`AnthropicModel`, `OpenAIModel`, `GeminiModel`). It
is **not** sent on the wire — purely the metadata surfaced via
`Model.contextWindow()`, which the session loop reads to size compaction. Without
it, an unrecognized model reports `0` and the loop falls back to
`SessionLimits.maxContextTokens` (or the 180k auto-backstop); set it so
compaction sizes against the model's real window instead. Adding the
`contextWindow` record component is a source-compatible change for Builder
callers; direct canonical-constructor callers (none in-tree) must add the new
positional argument.

## [2.6.1] — 2026-05-27 — Gemini continuation + streaming robustness

### Fixed — System instruction dropped on continuation requests (critical)

The Gemini provider's continuation path (`previous_interaction_id`) did not
re-send `system_instruction`. After the first tool-use turn the model operated
without its system prompt — no role, no behavioral constraints, no tool-use
guidance. This was the root cause of degraded Gemini 3.5 Flash performance in
multi-turn agentic workflows reported by library users. The fix extracts the
system instruction from the message history and includes it on every
continuation request.

### Fixed — `incomplete` and `budget_exceeded` interaction statuses

The Interactions API returns `incomplete` when the model hits
`max_output_tokens` and `budget_exceeded` when the token budget is exhausted.
Both were previously treated as `STOP` (successful completion), causing
truncated outputs to be silently accepted. `incomplete` now maps to
`FinishReason.LENGTH`; `budget_exceeded` maps to `FinishReason.ERROR`.

### Fixed — SSE `error` events silently ignored

Mid-stream API errors (content safety, rate limiting, internal errors) sent as
`event_type: "error"` SSE events were silently dropped by `parseStreamEvent`.
The provider now surfaces them as `StreamEvent.Error` with the HTTP status code
propagated to `GeminiException` for correct retry classification (429/5xx
retryable, 4xx not).

### Fixed — `interaction.status_update` SSE events not handled

The legacy unified `interaction.status_update` event (which the deployed server
still emits instead of the doc-promised `in_progress`/`requires_action` split)
was silently dropped. The provider now captures `interactionId` and
`terminalStatus` from these events as defense-in-depth.

### Fixed — `ThinkingLevel.MINIMAL` mapped to `"low"` instead of `"minimal"`

The Gemini API distinguishes `minimal` (disabled for most queries) from `low`
(minimizes latency but still reasons). Both `MINIMAL` and `LOW` were previously
mapped to `"low"`. `MINIMAL` now correctly maps to `"minimal"`.

## [2.6.0] — 2026-05-26 — Type-safe hook outcomes

### Breaking — `HookOutcome.MutateInput` replaced with four typed variants

The stringly-typed `MutateInput(Map<String, Object>)` that carried different
payload shapes at different hook phases has been replaced with four
compile-time-safe records:

| Record | Payload | Hook phases |
|--------|---------|-------------|
| `MutateArgs(Map<String, Object>)` | Tool arguments | `PreToolUseHook` |
| `MutateHistory(List<Message>)` | Conversation history | `PreModelTurnHook`, `PreCompactHook` |
| `MutateText(String)` | User-message text | `OnUserMessageHook` |
| `MutateResult(String)` | Tool-result output | `PostToolUseHook` |

**Migration:**

| Before (2.5.x) | After (2.6.0) |
|-----------------|---------------|
| `HookOutcome.mutate(Map.of("history", list))` | `HookOutcome.mutateHistory(list)` |
| `HookOutcome.mutate(Map.of("text", s))` | `HookOutcome.mutateText(s)` |
| `HookOutcome.mutate(Map.of("output", s))` | `HookOutcome.mutateResult(s)` |
| `HookOutcome.mutate(argsMap)` (at PreToolUse) | `HookOutcome.mutateArgs(argsMap)` |

Returning a mutation variant at a phase where it is not meaningful is harmless
— the loop treats it as `Continue`.

### Removed — internal casting helpers

`TurnRunner.extractHistory()`, `TurnRunner.stringField()`,
`AgentLoop.stringField()`, and `AgentLoop.messageListField()` — the defensive
`instanceof` casting code that fished values out of the untyped map — are
deleted. The typed record accessors (`m.history()`, `m.text()`, `m.output()`,
`m.args()`) provide the payload directly.

### Fixed — `PreModelTurnHook` Javadoc

The Javadoc incorrectly stated that `MutateInput` was "not meaningful" at
`PreModelTurn`. In fact, `TurnRunner.handleTurnLevel` handled it for the
BYO-compactor-as-hook path. The corrected Javadoc documents `MutateHistory` as
a meaningful outcome at this phase.

## [2.5.8] — 2026-05-26 — Structured-output self-correction for JSON-syntax errors, provider DRY extraction, convention sweep

### Fixed — JSON-syntax errors now self-correct (all providers)

`StructuredContentParser.parse()` previously wrapped JSON-syntax failures via
the caller-supplied `exceptionFactory`, producing provider-specific exceptions
(`AnthropicException`, `GeminiException`, `OpenAIException`). The session
loop's `TurnRunner.trySelfCorrectSchema` only pattern-matches
`StructuredOutputParseException`, so JSON-syntax errors terminated the session
instead of triggering the correction-and-retry path. The parser now throws
`StructuredOutputParseException` for all parse failures — both schema-validation
mismatches and JSON-syntax errors. The unused `exceptionFactory` parameter was
removed from the `parse()` signature.

### Fixed — Gemini + OpenAI: `IOException` now triggers stream retry

`GeminiModel.streamAndDrain` and `drainToResponse` (and the OpenAI equivalents)
wrapped `IOException` as `GeminiException`/`OpenAIException` instead of
`TransientStreamException`. DNS failures, TCP resets, and mid-stream socket
drops against Gemini and OpenAI were silently non-retryable. Both providers now
match Anthropic's pattern: connect-time and mid-stream `IOException`s promote
to `TransientStreamException` so the session loop's bounded retry fires.

### Fixed — Gemini + OpenAI: `isRetryable()` now includes status 529

Copy-paste drift from 2.5.5: `GeminiException` and `OpenAIException` omitted
`statusCode == 529` (Cloudflare "site overloaded") from `isRetryable()` while
`AnthropicException` included it. The fix is structural — see the new
`ProviderException` base class below.

### Added — `ProviderException` base class in core

New `ai.singlr.core.model.ProviderException` carries the shared `statusCode()`,
`isClientError()`, `isServerError()`, `isRetryable()` logic. All three provider
exceptions now extend it with constructor-only subclasses. Eliminates three
copy-pasted implementations and prevents future drift.

### Added — `HttpClientFactory.readBoundedErrorBody` + `shutdownGracefully`

Two shared utilities extracted from identical implementations across all three
provider Model classes. `readBoundedErrorBody(InputStream)` caps error body
reads at 64 KB with a truncation marker. `shutdownGracefully(HttpClient)`
executes the 5-second shutdown/awaitTermination/shutdownNow pattern.

### Changed — defensive copies in `Message` and `Response`

`Message.assistant(content, toolCalls, metadata)` and its overloads now wrap
`toolCalls` in `List.copyOf()` and `metadata` in `Map.copyOf()`.
`Message.Builder.withMetadata()` and `Response.Builder.withMetadata()` apply
`Map.copyOf()`.

### Changed — `is*()` methods renamed on records (Jackson 3.x safety)

Jackson 3.x treats `is<Name>()` on records as a boolean getter for property
`<name>`. Renamed to avoid phantom serialization properties:

| Record | Old | New |
|--------|-----|-----|
| `CachePolicy.Disabled/ShortLived/LongLived` | `isEnabled()` | `enabled()` |
| `StreamRetryPolicy` | `isEnabled()` | `enabled()` |
| `HookDecision` | `isContinue()` | `shouldContinue()` |
| `ToolBinding` | `isVisible(ctx)` | `visible(ctx)` |
| `SandboxPolicy` | `isPermissive()` | `enforcesNothing()` |

### Changed — `RequireSignatureHook` builder naming

Builder methods renamed to follow the `with` prefix convention:
`requiring(Signature)` → `withSignature(Signature)`,
`requiringToolName(String)` → `withToolName(String)`.
Static factory also renamed: `RequireSignatureHook.requiringToolName(String)`
→ `RequireSignatureHook.withToolName(String)`.

### Changed — `Strings.isBlank()` / `Strings.isEmpty()` consistency

Replaced inline `s == null || s.isBlank()` patterns with `Strings.isBlank(s)`
in `Durability`, `TurnRunner`, and `OnnxModelDownloader`.

### Changed — inline FQNs replaced with imports

`JvmSandbox.java` (14 FQNs → 8 imports), `OnnxModelDownloader.java` (1),
`AnthropicModel.java` (4).

### Changed — ONNX copyright headers

12 files (6 main + 6 test) updated from single-line to multi-line format
matching the rest of the codebase.

### Breaking — API

- `StructuredContentParser.parse()` no longer accepts an `exceptionFactory`
  parameter (4th arg removed). All three provider `parseStructuredContent`
  methods updated.
- `CachePolicy.isEnabled()` → `enabled()`.
- `StreamRetryPolicy.isEnabled()` → `enabled()`.
- `HookDecision.isContinue()` → `shouldContinue()`.
- `ToolBinding.isVisible(ctx)` → `visible(ctx)`.
- `SandboxPolicy.isPermissive()` → `enforcesNothing()`.
- `RequireSignatureHook.requiringToolName(String)` → `withToolName(String)`.
- `RequireSignatureHook.Builder.requiring(…)` → `withSignature(…)`.
- `RequireSignatureHook.Builder.requiringToolName(…)` → `withToolName(…)`.
- `AnthropicException`, `GeminiException`, `OpenAIException` now extend
  `ProviderException` instead of `RuntimeException` directly. Existing
  `catch (AnthropicException e)` blocks still work; new
  `catch (ProviderException e)` catches any provider.

## [2.5.5] — 2026-05-25 — Transient-stream retry: bounded re-issue, typed terminal, cause-chain preservation

Fixes the Light Grid bug report (2026-05-25) where a mid-stream `IOException`
on Anthropic Sonnet 4.6 — typically a TCP reset 100+ seconds into a long
structured-output emit — aborted a 25-turn, $0.40+ matchmaking run with the
opaque `ProviderError: Stream read error` terminal and no underlying cause
visible to the application.

Three layered fixes; all three asks in the report addressed.

### Added — cause-chain propagation

`StopClassifier`'s `FinishReason.ERROR` branch used to collapse the failure
to `SerializedError.of("ProviderError", outerMessage)`, discarding the
throwable. It now routes via `SerializedError.of(throwable)` so the kind
(`java.io.IOException`), the originating message
(`"Connection reset by peer"`), the stack trace, and any recursive cause
chain reach the application via `error.cause()`. Same change applies to
every non-transient throwable that escapes a turn — not just streams.

`TurnSubscriber.toOutcome(int)` carries the throwable through unchanged
instead of extracting only `getMessage()`.

### Added — bounded automatic retry

New `SessionLimits.streamRetryPolicy` field (defaults: 3 attempts, 1s/4s
exponential back-off capped at 30s, ±25% jitter; `StreamRetryPolicy.disabled()`
opts out). `TurnRunner.runStreamWithRetry` orchestrates the retry around
`model.chatStream`; cancellation-aware `CountDownLatch` back-off so
wall-clock / explicit-cancel terminations abort cleanly without burning the
remaining attempts.

### Added — distinct terminal subtype

New permitted sibling `ResultMessage.ErrorTransientStream(providerName,
attemptsMade, error, …)` — surfaced when the retry budget is exhausted.
Callers `switch` on the subtype instead of string-matching on
`ErrorDuringExecution.error().message()`. Anthropic SSE has no event-id
resumption — the retries are full re-issues from the same history snapshot.

New observable `QueryEvent.TurnRetried(attemptNumber, backoff, providerName,
error)` fires between attempts so UIs can surface "retrying…" affordances.

### Added — typed provider signal

New `ai.singlr.core.model.TransientStreamException` carries provider name +
cause chain. Providers throw this for recoverable transport failures
(mid-stream IOException, connect-time IOException). Loop pattern-matches on
the type without coupling to provider-specific exception classes.

### Changed — Anthropic mapping

`AnthropicModel.drainToResponse` now promotes `StreamEvent.Error` with an
`IOException` cause to `TransientStreamException` instead of the old opaque
`AnthropicException("Stream read error", ioe)`. `streamAndDrain` also
promotes connect-time `IOException`s (matches `AnthropicException.isRetryable`
which already classified `status==0` network errors as retryable).
Non-`IOException` causes (Jackson parse errors, contract violations) keep
the `AnthropicException` path and do not retry.

### Breaking — sealed-interface permits

- `ResultMessage` permits 8 subtypes now (was 7) — exhaustive `switch`
  consumers need an `ErrorTransientStream` case (compiler-flagged).
- `QueryEvent` permits 16 subtypes now (was 15) — same shape for
  `TurnRetried`.

### Tests

- 6 session-level repros (cause chain, bounded retry, recovery on
  intermediate success, distinct subtype, retry-disabled,
  cancellation-during-backoff)
- 4 anthropic-level repros (StreamingIterator cause preservation, real
  ServerSocket mid-stream RST, non-`IOException` stays `AnthropicException`)
- 12 `StreamRetryPolicy` tests, 6 `TransientStreamException` tests
- `ErrorTransientStream` + `TurnRetried` validation + `StopClassifier`
  routing + `SessionLimits.streamRetryPolicy` plumbing

## [2.5.4] — 2026-05-24 — OpenAI: model-aware `xhigh` dispatch (gpt-5.4 / gpt-5.5)

Fix-forward for 2.5.3's OpenAI handling of `ThinkingLevel.XHIGH` and `MAX`.
The 2.5.3 cut clamped both to `"high"` uniformly across every OpenAI model;
the OpenAI developer docs (verified 2026-05-24) document the `"xhigh"` tier
as a first-class accepted value on `reasoning.effort` for both `gpt-5.4` and
`gpt-5.5`, so blanket-clamping silently downgrades callers' reasoning on the
two flagship models.

### Fixed — gpt-5.4 / gpt-5.5 receive `reasoning.effort="xhigh"`

`OpenAIModelId` gains a `supportsXhighEffort()` flag:

| Model | `supportsXhighEffort` | Source |
|---|---|---|
| `gpt-5.5` | true | developer docs gpt-5.5 model page |
| `gpt-5.4` | true | developer docs gpt-5.4 model page |
| `gpt-5.4-mini` | false | OpenAI has not published the per-variant tier matrix; conservative until confirmed |
| `gpt-5.4-nano` | false | same |
| `gpt-4.1`, `gpt-4.1-mini`, `gpt-4.1-nano` | false | non-reasoning families |
| `gpt-4o`, `gpt-4o-mini` | false | non-reasoning |
| `o3`, `o4-mini` | false | older o-series; documented to accept low/medium/high only |

`OpenAIModel.buildReasoningConfig` now dispatches per-model:

- `XHIGH` and `MAX` → `"xhigh"` on `supportsXhighEffort=true` models
  (`gpt-5.4`, `gpt-5.5`)
- `XHIGH` and `MAX` → `"high"` on every other model (conservative clamp;
  flip the flag in `OpenAIModelId` when OpenAI confirms wider support for
  the mini/nano variants)

Anthropic's `MAX` has no native OpenAI equivalent at any tier — clamping it
to OpenAI's highest (`"xhigh"` or `"high"` depending on model) is the right
call: the caller asked for the maximum reasoning available and gets exactly
that, no silent stop at `"high"` when a stronger tier exists.

### Tests

`OpenAIModelTest` gains five tests (test-first; RED confirmed 3 of 5 failing
against the 2.5.3 dispatch before the fix):

- `gpt55XhighMapsToXhighWireString`
- `gpt54XhighMapsToXhighWireString`
- `gpt55MaxClampsToXhighWireString` — MAX climbs to xhigh on xhigh-capable
  models, not stops at high
- `o3XhighClampsToHighWireString` — undocumented support → conservative
- `o4MiniMaxClampsToHighWireString`

233 OpenAI unit tests green.

## [2.5.3] — 2026-05-24 — adaptive thinking on Opus 4.7: `xhigh`, `max`, and the `display` field

The Opus 4.7 adaptive-thinking surface gained two effort tiers (`xhigh`, `max`)
and a `display` knob that flipped its silent default to `omitted` — the latter
would have zeroed out Helios's `ModelChunk.ThinkingDelta` event stream for
every existing caller. 2.5.3 closes both gaps, test-first, and documents the
per-provider clamping behaviour for Gemini and OpenAI.

### Added — `ThinkingLevel.XHIGH` and `ThinkingLevel.MAX`

Two new tiers appended to the provider-agnostic `ThinkingLevel` enum:

- **`XHIGH`** — extra-deep reasoning with extended exploration. Anthropic
  Opus 4.7 only on the native wire; other providers clamp to `HIGH`.
- **`MAX`** — unbounded reasoning ("always thinks with no constraints on
  thinking depth"). Anthropic adaptive-capable models on the native wire;
  other providers clamp to `HIGH`.

Per-provider dispatch:

| Provider | Model | XHIGH | MAX |
|---|---|---|---|
| Anthropic | Opus 4.7 (adaptive) | `output_config.effort="xhigh"` | `output_config.effort="max"` |
| Anthropic | Opus 4.6 / Sonnet 4.6 (legacy enabled+budget_tokens) | `IllegalArgumentException` at request build — no wire equivalent | `IllegalArgumentException` |
| Gemini | all | clamps to `"high"` | clamps to `"high"` |
| OpenAI | Responses API | clamps to `"high"` | clamps to `"high"` |

The legacy-path rejection is explicit rather than silent — callers who target
XHIGH/MAX on a 4.6-class model see the exception at build time naming the
required model (`Opus 4.7`) instead of receiving an undocumented downgrade.

### Added — `display` field on `ThinkingConfig` + `ThinkingConfig.adaptiveOmitted()`

`thinking.display` joins the request as a third record component (nullable;
omitted from wire when null). Helios pins `display="summarized"` by default
for adaptive requests via `ThinkingConfig.adaptive()` — Anthropic's silent
default on Opus 4.7 became `"omitted"`, which would empty the `thinking`
field on every response and silently break `ModelChunk.ThinkingDelta` event
emission for every existing Helios caller.

Callers who want the time-to-first-text-token win of omitted-mode opt in
explicitly via `ThinkingConfig.adaptiveOmitted()`. The encrypted `signature`
field is identical in both modes, so multi-turn conversations work in either.

### Wired

- `AnthropicModel.buildThinkingSpec` — switch arms for XHIGH/MAX on adaptive;
  typed `IllegalArgumentException` on legacy path for both new tiers.
- `GeminiModel.buildRequest` — `HIGH, XHIGH, MAX` collapse to `"high"`.
- `OpenAIModel.buildReasoningConfig` — same collapse.
- `OutputConfig.XHIGH` and `OutputConfig.MAX` constants alongside the existing
  `LOW`/`MEDIUM`/`HIGH`.

### Tests

- `AnthropicModelTest` gains five tests: XHIGH wire string, MAX wire string,
  adaptive default `display="summarized"`, Opus 4.6 rejects XHIGH with typed
  exception, Opus 4.6 rejects MAX.
- `SerializationTest.serializeAdaptiveThinkingShape` updated to assert the
  new `display="summarized"` baseline; new
  `serializeAdaptiveOmittedThinkingShape` covers the opt-in omitted path
  paired with XHIGH effort.
- `ThinkingLevelTest` updated to 7 values; explicit `valueOf` assertions for
  XHIGH and MAX.

### Breaking — record components added

- `ThinkingConfig(type, budgetTokens)` → `ThinkingConfig(type, budgetTokens,
  display)`. Canonical-constructor callers need updating; factory callers
  (`ThinkingConfig.enabled(int)`, `.adaptive()`, `.disabled()`) are
  source-compatible — the new field defaults to null in factories.

### Verify

201 anthropic + 228 openai + (gemini) unit tests green. Coverage gates on
session+runtime+core unaffected. javadoc:jar across the 9 published modules
clean.

## [2.5.2] — 2026-05-24 — visibility of subprocess-spawn failures + relative-classpath fix

Two cooperating fixes, both surfaced by client reports against the same code
path (`JShellExecutionProvider` → `JvmSandbox`). Test-first; the production
change followed RED reproductions in every case.

### Fixed — `JvmSandbox` resolves relative classpath entries against host cwd

Client report: a host running as `java -jar target/cron.jar` died with
`ClassNotFoundException: ai.singlr.repl.sandbox.JvmSandboxBootstrap` inside
the sandbox subprocess, then waited the full 30-second RPC accept timeout
for a connection that would never come.

Root cause: `System.getProperty("java.class.path")` returns the bare relative
path `"target/cron.jar"` when the host is launched with `-jar`. {@link
JvmSandbox} previously passed that string verbatim to the subprocess as
`-cp`, but the subprocess `cd`s to a private `/tmp/helios-sandbox-cwd-*`
where the relative path no longer resolves.

Fix: new package-private {@code JvmSandbox.resolveClasspathForSubprocess}
walks every {@code path.separator}-delimited entry. Absolute entries pass
through verbatim; relative entries are joined against the host JVM's
{@code Path.of("").toAbsolutePath()} and normalised. The subprocess receives
fully-qualified paths it can resolve from any working directory.

Manifest {@code Class-Path:} entries from jar-manifest dependencies (the
{@code libs/*.jar} pattern Maven produces alongside an executable jar)
continue to work because those entries are resolved by the JDK relative to
the jar's own location, not the JVM cwd — and the jar is now reachable via
its absolute path.

Coverage: {@code JvmSandboxTest#buildLaunchCommandResolvesRelativeClasspathEntriesToAbsolute}
and {@code #buildLaunchCommandLeavesAbsoluteClasspathEntriesUnchanged} pin
the contract via a {@code System.setProperty("java.class.path", "target/cron.jar")}
fixture that exactly reproduces the cron deployment scenario.

### Fixed — `SessionStartOutcome.Refuse` carries the underlying `Throwable`

Client report: when `JShellExecutionProvider` caught a subprocess-spawn
failure it forwarded only {@code e.getMessage()} into the refuse string,
discarding the cause chain. {@code IOException("No such file or directory:
target/cron.jar")} was buried inside {@code ReplException("Failed to start
JVM sandbox subprocess", ...)} and the deployer saw only the outer wrapper.
No way to distinguish missing binary from permission denied from disk full.

Fix, three layers:

1. **{@code SessionStartOutcome.Refuse}** gains a {@code Throwable cause}
   field plus a {@code refuse(String reason, Throwable cause)} factory.
   The original {@code refuse(String)} factory is preserved for
   policy-driven refusals (pool saturation, auth failure) that carry no
   underlying exception.

2. **{@code SerializedError}** gains a recursive {@code cause} field; the
   {@code of(Throwable)} factory walks {@code Throwable.getCause()}
   building a fully-serialised chain (bounded at depth 16 to defend
   against cyclic references). {@code withoutStackTrace()} strips frames
   recursively across the chain.

3. **{@code ResultMessage.ErrorProviderUnavailable}** gains a
   {@code SerializedError cause} field (nullable). {@code
   AgentSessionImpl.markRefused} plumbs the cause through from
   {@code Refuse#cause()} via {@code SerializedError.of(throwable)} so
   the typed chain reaches the deployer's terminal callback.

End-to-end test
{@code AgentSessionImplTest#providerRefuseWithCauseSurfacesFullSerializedErrorChain}
proves the round-trip: a {@code Refuse} carrying a 3-deep cause chain
({@code IllegalStateException} ← {@code RuntimeException} ← {@code
IOException}) produces an {@code ErrorProviderUnavailable} where
{@code err.causeOpt().get().cause().cause()} returns the {@code
IOException}'s class name and original message.

### Breaking — record components added

The following record components are new; direct canonical-constructor
callers need updating:

- {@code SerializedError(kind, message, stackTrace)} →
  {@code SerializedError(kind, message, stackTrace, cause)}
- {@code SessionStartOutcome.Refuse(reason)} →
  {@code Refuse(reason, cause)}
- {@code ResultMessage.ErrorProviderUnavailable(sessionId, providerName,
  reason, usage, cost, duration)} →
  {@code ErrorProviderUnavailable(sessionId, providerName, reason, cause,
  usage, cost, duration)}

Builder/factory callers ({@code SessionStartOutcome.refuse(String)},
{@code SerializedError.of(...)}) are unchanged — the new field defaults to
{@code null} in all factories.

### Test count

`helios-session`: 1173 → 1190 (+17 covering the cause-chain serialisation,
the new {@code Refuse} factory, and the end-to-end plumbing test).
`helios-repl`: 815 → 818 (+3 covering the classpath resolver, the absolute-
classpath pass-through, and the enriched {@code
onSessionStartRefusesWhenSandboxFactoryThrows} that asserts the full
3-deep cause chain reaches {@code Refuse#cause()}). Coverage gates green.

## [2.5.1] — 2026-05-24 — code-quality cleanup in 2.5.0's session-loop changes

Style-only follow-up — no behaviour change. A self-audit after 2.5.0 found three
regressions against Helios's coding standards (`feedback_consistent_code_style`,
the no-inline-comments rule in `CLAUDE.md`, and the 100 %-coverage-on-new-code
quality bar). 2.5.1 closes them.

### Fixed — inline FQDNs replaced with proper imports

`session/src/main/java/ai/singlr/session/loop/TurnRunner.java` had five inline
uses of `java.util.concurrent.ScheduledExecutorService` and
`java.util.concurrent.Executors` (field type, static field, factory return
type, constructor body, 10-arg constructor parameter). All now imported at the
top of the file alongside the other `java.util.concurrent.*` imports.

`session/src/main/java/ai/singlr/session/loop/TurnSubscriber.java#fireIdleTimeout`
had one inline `new java.util.concurrent.TimeoutException(...)`. Now imported.

Both files now consistent with the existing convention pinned in
`feedback_consistent_code_style.md`: imports at the top, no inline FQNs.

### Fixed — race-condition comment moved into method javadoc

`TurnSubscriber#armIdleTimer` carried two `//` lines inside the method body
explaining the post-terminal re-arm guard. Helios's no-inline-comments rule
(`CLAUDE.md`) allows comments for "subtle invariants" but prefers method-level
javadoc; the explanation now sits above the method as documentation rather than
inside as commentary. The body is now noise-free.

### Added — `TurnSubscriberRaceTest` covers post-terminal re-arm guard

New 3-test file (`session/src/test/java/ai/singlr/session/loop/TurnSubscriberRaceTest.java`)
pins the race-safety guards in `armIdleTimer` that the end-to-end
`AgentSessionStreamIdleTest` couldn't reach deterministically:

- `onNextAfterOnCompleteDoesNotReArmIdleTimer` — a post-`onComplete` chunk
  (Reactive Streams forbids it but real provider publishers have been seen to
  do it) must drop on the floor without scheduling a new task.
- `onNextAfterOnErrorDoesNotReArmIdleTimer` — same shape, after `onError`.
- `awaitDoneClearsIdleTimerAfterTerminal` — confirms no scheduled task
  outlives the turn via a `CountingScheduler` decorator that asserts on
  outstanding futures.

`armIdleTimer` branch coverage rose from 66 % → 83 % (instruction 87 % → 89 %).
The remaining gap is the genuine CAS-fail race between two concurrent
re-arms — same defensive-concurrent-fallback class as the JaCoCo gaps
documented in earlier releases (`PathJail`-style IOException catches). Pinning
that would require an injected synchronization barrier; not worth the test
fragility.

Total `helios-session`: 1170 → 1173 tests, coverage gate (95 % inst / 90 %
branch) green.

## [2.5.0] — 2026-05-24 — hung-session bugfix: wall-clock hard deadline + per-chunk stream-idle timeout

A client stack trace surfaced a Helios session pinned at `AgentSession.runBlocking` →
`CompletableFuture.join()` for 494 s despite a 2-minute wall-clock cap. Two cooperating defects:

1. **`maxWallClock` was only re-checked between turns.** A turn whose model stream
   delivered no terminal signal (silent socket, hung edge / proxy / load balancer)
   blocked the loop indefinitely in `TurnSubscriber.awaitDone()`. The wall-clock
   check in `StopClassifier` never got a chance to run.
2. **No per-chunk idle ceiling on the streaming subscriber.** Even with a working
   wall-clock deadline, a session whose stream stalls for 50 minutes burns the entire
   wall-clock budget waiting for chunks that never come — useful work stops long
   before termination.

2.5.0 closes both, test-first. Two RED regression tests
(`AgentSessionWallClockTest`, `AgentSessionStreamIdleTest`) reproduced the hangs
deterministically before any production change landed.

### Fixed — `maxWallClock` is now a hard deadline

`AgentSessionImpl` owns a session-scoped daemon `ScheduledExecutorService` that
arms a one-shot task at session start. When `maxWallClock` elapses the task
calls `state.cancellation().cancel("maxWallClock exceeded after Nms")`.

`TurnSubscriber.awaitDone(CancellationToken)` registers an `onCancel` callback
that counts down the latch, releasing the runner thread without waiting for the
provider's `onComplete` / `onError`. The cancellation registration is removed
in `finally` so the long-lived session token doesn't accumulate stale callbacks
across turns (the documented `CancellationToken.onCancel` cleanup contract).

`StopClassifier` priority order is now wall-clock → budget → cancellation →
turns. Without the reorder the wall-clock-triggered cancellation would surface
as `ResultMessage.Cancelled` instead of the more informative `ErrorMaxWallClock`.
Explicit `close()` and host-initiated cancels still produce `Cancelled` —
exercised in tests; the priority shift only changes the wall-clock-derived case.

### Added — `SessionLimits.streamIdleTimeout`

New per-chunk idle ceiling on the model stream, default **60 s**. Every
`onSubscribe` and `onNext` resets a scheduled task armed against the shared
session scheduler; if no chunk arrives for `streamIdleTimeout` the subscriber
synthesises an `onError(TimeoutException)`, the turn ends with
`FinishReason.ERROR`, and `StopClassifier` produces
`ResultMessage.ErrorDuringExecution`.

Wire via `SessionLimits.newBuilder().withStreamIdleTimeout(Duration.ofSeconds(15))`.
The shared per-session scheduler is reused; tests get a process-wide daemon
default via the 9-arg `TurnRunner` convenience constructor.

This is a compatible breaking change for direct constructors of `SessionLimits`
(the record gains a sixth component) and a binary-compatible additive change to
the Builder. Deployers using the Builder need no code changes; the new field
defaults to 60 s.

### Wired

- `AgentSessionImpl` constructs a `helios-deadline-<sessionId>` daemon
  scheduler in its constructor; passes it to `TurnRunner` and shuts it down in
  `closeRuntime()` after the publisher executor drains.
- `TurnRunner` exposes a 9-arg back-compat constructor (uses a lazily-created
  process-wide daemon scheduler) and a 10-arg constructor for production use.
- `TurnSubscriber` constructor signature gains `(ScheduledExecutorService,
  Duration)`. Package-private — no public surface change.

### Tests

- `AgentSessionWallClockTest` (2 tests, ~1.07 s): zero-chunk silent stream
  surfaces `ErrorMaxWallClock` within 500 ms of the configured deadline.
  Pre-fix: hung indefinitely.
- `AgentSessionStreamIdleTest` (2 tests, ~0.67 s): zero-chunk and mid-stream
  stall cases both surface `ErrorDuringExecution` within `streamIdleTimeout`
  while leaving `maxWallClock` (10 s) untouched as the trigger.
- `StopClassifierTest.wallClockExceededTakesPriorityOverCancellation` pins the
  priority reorder.
- `SessionLimitsTest` gains coverage for the new builder method, the canonical
  constructor's positivity / null validation, and the updated defaults
  assertion.

Total `helios-session`: 1163 → 1170 tests; coverage gate (95 % inst / 90 %
branch) green.

## [2.4.0] — 2026-05-23 — workspace file tools redact; `kb_*` family removed

A consolidation cut driven by a library-user observation: Helios shipped two
parallel filesystem-tool families — `Read` / `Grep` / `Glob` rooted at the
session's workspace, and `kb_read` / `kb_grep` / `kb_glob` from
`FilesystemKnowledge` rooted at a curated corpus. Same primitive operations,
different names, the agent had to disambiguate from descriptions. Claude Code
has one such family, not two. So 2.4.0 folds the substantive feature
(`SecretRegistry`-backed redaction) into the v2 workspace tools and removes
the duplicate surface.

### Fixed — `GlobTool` + `GrepTool` use Unix `**/` semantics

Forward-port of the 2.3.2 fix that was applied to the (now-removed)
`FilesystemKnowledge.compileGlob` but never to the v2 workspace tools.

Java NIO's default `getPathMatcher("glob:**/*.md")` requires `**` to
match at least one separator, so `'**/*.md'` against root-level
`intro.md` silently returns zero hits. Every other glob system agents
have seen (ripgrep, fd, gitignore, Python pathlib, Go filepath/match)
treats `'**/*.md'` as recursive-including-root, and the tool
descriptions advertised by `Glob` / `Grep` use `'**/*.java'` as the
example — so the model reaches for the pattern it can't use.

New `session.files.GlobMatchers.compile(FileSystem, String)` helper:
for any pattern starting with `'**/'`, also try the pattern with that
prefix stripped; a file matches if either form accepts it. Used by
`GlobTool` and by `GrepTool`'s optional `include` glob filter.
Semantics preserved for non-`'**/'`-leading patterns.

Caught by the rewritten `WorkspaceReadOnlyRedactionAgentSession*`
integration tests on first run against both Gemini and Anthropic —
exactly the live-API smoke the pre-PR checklist exists for.

### Added — `Redactor` overloads on `ReadTool` and `GrepTool`

`ReadTool.binding(workspace, tracker, Redactor)` pipes text-path output
through the supplied redactor before returning it to the model; the
existing 2-arg overload (`binding(workspace, tracker)`) is unchanged and
delegates with `null` for "no redaction".

`GrepTool.binding(workspace, Redactor)` redacts the `content` portion of
each `path:line:content` match; the path prefix is left alone (it is
structural information the model needs to navigate, not secret material).
The existing 1-arg overload (`binding(workspace)`) is unchanged.

`GlobTool` gets no `Redactor` overload by design — its output is paths
only, and paths are not treated as secret material.

This is the same contract `FilesystemKnowledge` provided to its `kb_*`
tools: any registered secret a `CommandGrant` (or any other source) wrote
to a file is scrubbed when the workspace `Read` or `Grep` tool later
returns a buffer containing it. Wire by passing
`registry.redactor()` from your session-level `SecretRegistry`.

### Removed — `core.knowledge` package (`FilesystemKnowledge`, `PathJail`, `kb_*` tools)

```
core/src/main/java/ai/singlr/core/knowledge/FilesystemKnowledge.java   (deleted)
core/src/main/java/ai/singlr/core/knowledge/PathJail.java              (deleted)
core/src/main/java/module-info.java   exports ai.singlr.core.knowledge  (removed)
```

`FilesystemKnowledge` predated the v2 session-level file tools (commit
`629a648`, 2026-05-04) and shipped before `ReadTool` / `GrepTool` /
`GlobTool` landed in v2 (commit `3bb1cd6`). After v2, it was vestigial:
same primitive, parallel tests, parallel namespace. The only delta was
its output redactor — now ported to the workspace tools above.

**Migration.** Deployers using `FilesystemKnowledge` to expose a curated
corpus to an agent rewire as:

```java
// Before:
var kb = FilesystemKnowledge.builder(corpus).withSecretRegistry(registry).build();
agentTools.addAll(kb.tools());

// After:
var workspace = WorkspaceRoot.of(corpus);
var tracker   = InMemoryFileTracker.create();
var redactor  = registry.redactor();
var bindings  = List.of(
    ReadTool.binding(workspace, tracker, redactor),
    GrepTool.binding(workspace, redactor),
    GlobTool.binding(workspace));
```

Tool names visible to the model change from `kb_read` / `kb_grep` /
`kb_glob` to `Read` / `Grep` / `Glob`; prompts that reference the old
names by name need updating. Path-jail and resource-cap behaviour is
preserved (the v2 tools have always had `WorkspaceRoot` enforcement and
their own per-file / per-line / per-output caps).

`PathJail` in `core.knowledge` had no external callers — the v2
`WorkspaceRoot` (in `session.files`) provides the equivalent jail.

### Doc + javadoc sweep

- `README.md`: removed the dedicated "FilesystemKnowledge — curated
  corpus, no vector DB" section; folded the redaction story into the
  workspace-file-tools section.
- `repl/SandboxPolicy.java` javadoc: removed `{@link
  ai.singlr.core.knowledge.FilesystemKnowledge}` reference; points at
  session-level `Read` / `Grep` / `Glob` tools as the example.
- `persistence/PgConfig.java` javadoc: source-level redaction list now
  names `ReadTool` / `GrepTool` (wired with a `Redactor`) instead of
  `FilesystemKnowledge`.

### Test count

`helios-session` + `helios-core` unit tests: +5 (Redactor overload
coverage). `examples/session-demo` integration tests: net zero (the two
`FilesystemKnowledgeAgentSessionIntegrationTest` files were rewritten as
`WorkspaceReadOnlyRedactionAgentSessionIntegrationTest` against the v2
tools — same three scenarios per provider).

## [2.3.3] — 2026-05-23 — outputSchema + tools fix; session-loop schema self-correction

Three cooperating fixes for the Light Grid bug report (2026-05-22): an
`AgentSession` configured with both a `ToolRegistry` and
`SessionOptions.withOutputSchema(...)` used to terminate on turn 1 with
`ProviderError: Failed to parse structured output: <prose>` because the
provider's structured-output parse fired on the model's tool-calling
prose preamble. The 6-second cliff-edge failure is now gone.

### Fixed — Providers skip structured-output parse on tool-calling turns

`AnthropicModel`, `OpenAIModel`, and `GeminiModel` now guard their
`chat(messages, tools, outputSchema)` parse on
`response.toolCalls().isEmpty()`. When the model chose to call a tool,
structured output isn't the deliverable of that turn — the loop will
dispatch and iterate. Pre-fix the prose preamble before a `tool_use`
block (e.g. *"I'll work through this carefully."*) would fail the JSON
parser and surface as a terminal `AnthropicException` / `OpenAIException`
/ `GeminiException`. Post-fix the response carries `toolCalls` non-empty
with `parsed` null, and the session loop continues normally.

`Response.parsed()` had zero main-code readers; tests are the only
observers, so returning null on tool-calling turns is a safe semantic
tightening.

### Fixed — Anthropic schema instruction is turn-aware when tools present

`AnthropicModel.buildRequest` now emits two phrasings of the structured-
output system instruction. Tool-less sessions keep the existing bare
"You must respond with valid JSON matching this schema: …". Tool-using
sessions get a turn-aware variant: *"You may call the available tools
to gather information. When you are ready to emit your final answer
(not a tool call), it must be valid JSON matching this schema: …"*.
This stops the schema instruction from fighting the deployer's "use
tools first, then emit JSON" guidance every turn, which on Light Grid's
canary was causing Opus 4.7 to skip tool dispatch on turn 0 and emit
prose preambles that then failed downstream parse gating.

OpenAI and Gemini providers transmit the schema via their native
channels (`text.format=json_schema` and `response_format.schema`
respectively), so they don't carry this fix — only Anthropic relies on
system-prompt injection for structured output.

### Added — Session loop self-correction on `StructuredOutputParseException`

`TurnRunner.trySelfCorrectSchema` pattern-matches
`StructuredOutputParseException` on `subscriber.error()` after
`awaitDone()`. When fired:

1. The model's wrong attempt is appended to history as an assistant
   message (so it sees its own attempt through conversation context on
   the retry).
2. `StructuredOutputParseException.correctionMessage()` — the field-
   level diff only — is enqueued to the steering queue as a synthetic
   user turn. The exception's `rawContent` is intentionally NOT echoed
   into the correction text per the class's own cost-policy note (the
   model already has it via the history-appended assistant message).
3. The turn returns `FinishReason.TOOL_CALLS` so the loop iterates
   regardless of steering-queue / classifier state.

Retries are bounded by `SessionLimits.maxTurns()` — no dedicated
parse-retry ceiling was added.

Replaces the aspirational CLAUDE.md "Structured Output Resilience" row
that described this path as existing in v2; it didn't until now.
Other failure modes (syntactic JSON failure, provider IO errors) still
terminate via `FinishReason.ERROR` as before — only the recoverable
"parseable JSON, wrong shape" signal triggers self-correction.

### Deprecated workarounds (informational)

Deployers who dropped `SessionOptions.withOutputSchema(...)` and added
post-hoc terminal parsing as a workaround (e.g. Light Grid's
matchmaking agent) can now re-enable `withOutputSchema(...)` directly
and remove their local `parseTerminal(...)` helpers — the session loop
handles both the tool-calling and schema-mismatch paths natively.

## [2.3.2] — 2026-05-22 — ReadTool guardrails, multimodal Read, kb_glob Unix semantics

Three layers of work bringing Helios's file-tool surface to Claude
Code-grade production quality. Verified end-to-end against live Gemini
3.5 Flash and Claude Sonnet 4.6 — 10 live agent-session integration
tests, ~37 s total spend, two real bugs caught and fixed by the live
tests that pure unit testing would have missed.

### Added — `ToolResult.attachments` for multimodal tool returns

Tools that surface a file the model should consume natively (a PNG for
the vision channel, a PDF for the document channel) previously had
nowhere to put the bytes. `ToolResult` was text-only.

`ToolResult` record gains a 4th component `attachments: List<InlineFile>`
with a canonical constructor that defensively copies and normalises
null to `List.of()`. New `successWithAttachments(text, files)` factory
and a `hasAttachments()` predicate. Existing `success(...)` /
`failure(...)` factories preserved — all in-tree call sites stay
source-compatible.

The agent loop splices any returned attachments into a synthetic user
follow-up message after the tool-result text, so the next round-trip
carries the `InlineFiles` through every provider's existing
`Message.user(text, inlineFiles)` plumbing. Zero per-provider adapter
changes — Anthropic / OpenAI / Gemini all already know how to encode
inline files in a user message.

Breaking only for direct canonical-constructor callers of
`ToolResult`; none exist outside `ToolResult` itself. `Tool` executors
that have always used `ToolResult.success(...)` keep working without
modification.

### Changed — `ReadTool` rewritten with streaming, byte budgets, MIME dispatch

Pre-2.3.2 `ReadTool` called `Files.readString(...)` on the entire file
then trimmed to 2000 lines — a 500 MB log would buffer the lot before
being chopped, no binary detection, PDFs decoded as UTF-8 into garbage,
images had no native path at all.

Now `ReadTool` streams via `BufferedReader` and stops at the first cap
hit:

- `MAX_FILE_SIZE_BYTES = 25 MB` on the source file, pre-checked via
  `Files.size` before any I/O.
- `MAX_LINE_BYTES = 1 MB` per emitted line, with inline truncation
  marker for the minified-JSON-on-one-line case.
- `MAX_OUTPUT_BYTES = 4 MB` total output cap.
- Truncation messages teach the next move: `[truncated at line N;
  M lines emitted. Use offset=N+1 to continue, or Grep for a narrower
  target.]`
- Binary detection (NUL-byte sniff in first 8 KB) fails fast with the
  detected MIME named.

`Files.probeContentType` with extension fallback drives a three-way
dispatch:

- `text/*` or no-MIME-with-clean-sniff → bounded text path.
- `image/*` or `application/pdf` → returned as `InlineFile` attachment
  through the new `ToolResult.attachments` channel.
- Other binary → clean refusal with the detected MIME.

Per-format caps tuned to real provider limits (verified against live
docs during the audit):

- `MAX_IMAGE_BYTES = 5 MB` matches Anthropic's published per-image
  floor — the strictest of the three providers. Gemini and OpenAI
  permit larger but failing here with a clear message beats racing
  the API to a cryptic 400.
- `MAX_PDF_BYTES = 20 MB` stays inside every provider's document
  channel (Anthropic 32 MB request budget; Gemini 50 MB per document).

Failure messages name the provider constraint and the recovery move
(downsample for images, file-upload host tool for oversize PDFs).

### Fixed — `kb_glob` matches root files with `**/*.md` (Unix glob semantics)

Caught by the live agent-session tests added in this release. A fresh
Gemini and a fresh Claude session, asked to list markdown files in a
corpus with `intro.md` + `guide.md` at the root, both reached for
`**/*.md` (the natural pattern, also the one our own `kb_glob` tool
description uses as the example). Both got back "no markdown files."

Java's NIO `PathMatcher` treats `**` as "zero or more characters
including the separator," but requires the `/` after `**` to match a
literal separator — so `**/*.md` against the relative path `intro.md`
(zero separators) fails. Every other glob system agents have ever seen
— ripgrep, fd, gitignore, Python `pathlib`, Go `filepath.Match` —
treats `**/*.md` as "any markdown anywhere including root." Our own
tool description tells agents to use it that way.

Fix: `FilesystemKnowledge.compileGlob(pattern)` — when a pattern starts
with `**/`, also compiles the pattern with that prefix stripped; a
file matches if either form accepts it. Semantics preserved for
non-`**/`-leading patterns. Applied to both `kb_glob` and `kb_grep`'s
optional `glob` argument.

### Live agent-session integration coverage

Four new test files in `examples/session-demo`, all gated behind
provider API keys so the suite stays runnable offline:

- `ReadToolMultimodalThroughLoopIntegrationTest` (Gemini, 2 tests):
  agent reads a real PNG → vision model describes it; agent reads a
  real PDF → document channel extracts "Hello, world!"
- `ReadToolMultimodalAnthropicIntegrationTest` (Claude Sonnet 4.6, 2
  tests): same scenarios. Uses Anthropic's published vision-cookbook
  PNG bytes after the hand-rolled grayscale PNG was rejected with
  HTTP 400 "Could not process image" (real cross-provider PNG-variant
  strictness divergence).
- `FilesystemKnowledgeAgentSessionIntegrationTest` (Gemini, 3 tests):
  agent uses `kb_glob` to list `**/*.md`, `kb_grep` to find "reactor",
  `kb_read` on a config containing a registered secret — assistant's
  reply must NOT contain raw secret bytes (proves end-to-end
  redaction).
- `FilesystemKnowledgeAgentSessionAnthropicIntegrationTest` (Claude,
  3 tests): mirror of the Gemini suite, proves the redaction contract
  holds provider-agnostically.

10 live tests total, ~37 s spend on both providers combined. The PDF
`/Length 51` vs actual-46-bytes mismatch and the Anthropic PNG
strictness were both caught by these tests on the first run — the
kind of failure modes only live integration testing surfaces.

`examples/session-demo/pom.xml` gains `helios-anthropic` as a
test-scope dependency so the Claude integration tests can construct an
`AnthropicProvider`. The module's runtime classpath stays Gemini-only.

### Test coverage totals

- 45 new tests across 8 files (4 unit-test additions, 4 new
  integration test files)
- `helios-session` 1147 tests passing
- `helios-core` 1289 tests passing (+9 across ToolResult and
  FilesystemKnowledge)
- Session + runtime coverage gates (>=95% instruction, >=90% branch)
  intact
- All 9 published-module javadoc clean

## [2.3.1] — 2026-05-22 — JShell sandbox filesystem isolation

Two layers of defense closing the "can a JShell snippet read /etc/passwd?"
gap surfaced while auditing the post-2.3.0 sandbox surface. Defaults are
now safe out of the box; deployers can opt out per layer when their
workload genuinely needs raw filesystem access.

### Added — `JvmSandboxConfig.withWorkingDirectory(Path)` + ephemeral default

`ProcessBuilder` previously launched the subprocess with no `.directory()`
call, so the JShell JVM inherited the host JVM's cwd. Relative-path file
ops (`new File("data.csv")`) resolved into whatever workspace the
deployer's service happened to be running from, and there was no API to
configure where the subprocess ran.

Now `JvmSandbox.create` resolves an effective working directory:

- **Default (config.workingDirectory() == null)**: creates a private
  per-session `helios-sandbox-cwd-*` temp dir (mode 0700 on POSIX), sets
  it on `ProcessBuilder.directory()`, and recursively deletes it (with
  every file the snippet wrote into it) on `close()`. Symlinks are
  deleted as links, not followed — a snippet that managed to symlink
  `/` from inside cannot trick host cleanup into walking the root
  filesystem.
- **Explicit caller-owned path**: the sandbox uses it verbatim and does
  NOT delete it on `close()`. Caller manages lifecycle (e.g. a
  customer's per-tenant scratch mount).

`JvmSandboxConfig` gains a `workingDirectory` component. Breaking for
direct canonical-constructor callers; Builder callers unaffected.

### Added — `SandboxPolicy.denyFileSystemAccess` categorical flag

cwd containment is necessary but not sufficient — absolute paths bypass
cwd entirely. The L2 verifier already denied `FileReader` / `FileWriter`
/ `File*Stream` / `RandomAccessFile` under `noEgress()`, but `java.io.File`
itself stayed callable: `new File("/etc/passwd").exists()` / `.length()`
/ `.listFiles()` / `.canRead()` all returned data via JNI without ever
opening a stream, leaking filesystem metadata. `java.nio.file.*` was
denied only via the allow-list default-deny under `noEgress`, not by a
categorical flag.

New `denyFileSystemAccess` boolean on `SandboxPolicy` (8th record
component, behind the existing `denyReflection` / `denyNativeAccess` /
`denyDynamicClassDefinition` pattern). `PolicyBytecodeVerifier`'s new
`isFileSystemAccess` rule denies:

- `java.nio.file.*` by prefix (`Files`, `Paths`, `Path`, `FileSystems`,
  `WatchService`, every member of the package)
- `java.nio.channels.FileChannel` and `AsynchronousFileChannel` by
  whole-class
- `java.io.FileInputStream` / `FileOutputStream` / `FileReader` /
  `FileWriter` / `RandomAccessFile` / `FileDescriptor` by whole-class
- `java.io.File`'s filesystem-touching members enumerated explicitly
  (`list`, `listFiles`, `listRoots`, `exists`, `length`, `lastModified`,
  `isDirectory`, `isFile`, `isHidden`, `canRead`, `canWrite`,
  `canExecute`, `getCanonicalPath`, `getCanonicalFile`, `getFreeSpace`,
  `getTotalSpace`, `getUsableSpace`, `createNewFile`, `delete`,
  `deleteOnExit`, `mkdir`, `mkdirs`, `renameTo`, `setReadable`,
  `setWritable`, `setExecutable`, `setReadOnly`, `setLastModified`,
  `toPath`)

Pure-string members on `File` (`getName`, `getPath`, `getAbsolutePath`,
`toString`, `equals`, `hashCode`, `compareTo`, `toURI`, `isAbsolute`)
stay callable so snippets can manipulate path strings for compute
purposes — they don't reach the kernel.

`SandboxPolicy` gains `denyFileSystemAccess` as the 8th record component.
Breaking for direct canonical-constructor callers; Builder callers
unaffected. `SandboxPolicySerialization` round-trips the new field;
older bootstraps reading newer policies default it to false (forward
compat).

### Changed — `SandboxPolicy.noEgress()` is now honestly no-egress

`noEgress()` previously enumerated `FileInputStream` / `FileOutputStream`
/ `FileReader` / `FileWriter` / `RandomAccessFile` in `deniedClasses`,
but the prior leak path (`java.io.File` metadata methods like
`list` / `exists` / `canRead`) survived because the `File` class itself
wasn't enumerated. Rolling them into `denyFileSystemAccess` closes that
gap and simplifies the `deniedClasses` set — file IO now travels through
one flag. Breaking for callers asserting the old `deniedClasses`
contents directly; the categorical flag carries the deny now.

### Test coverage

19 new tests across the touched files (816 repl tests passing, was 797):

- `PolicyBytecodeVerifierTest` (+8): `Files.readAllBytes`, `Paths.get`,
  `FileChannel.open`, `FileInputStream` ctor, `File.list` /
  `File.exists` metadata leaks; pure-string `File.getName` stays
  callable; permissive baseline leaves the surface open
- `NoEgressPresetTest` (+6): `denyFileSystemAccess` set on `noEgress`;
  redundant `deniedClasses` entries removed; `File` metadata leak path
  now closes; `Files.readAllBytes` / `FileChannel.open` / `FileReader`
  / `FileInputStream` snippets denied with the right rule label
- `SandboxPolicySerializationTest` (+3): full round-trip with the new
  flag; isolated flag round-trip; forward-compat default to false on
  absent field
- `JvmSandboxConfigTest` (+3): default null; explicit path round-trip;
  null-as-ephemeral-signal
- `SandboxWorkingDirectoryTest` (NEW, 6 end-to-end via real subprocess):
  default ephemeral cwd created and subprocess sees it as `user.dir`;
  not the host JVM's cwd; deleted on close; recursive cleanup of
  snippet-written nested files; caller-owned path used verbatim and
  survives close; `denyFileSystemAccess` blocks
  `Files.readAllBytes("/etc/passwd")` and
  `new File("/etc/passwd").exists()` end-to-end with the policy
  traceback reaching stderr

## [2.3.0] — 2026-05-21 — cross-provider prompt caching + agent-loop hardening

Closes the two issues reported in `reports/hv2-bug2.md` from the Light Grid
matchmaking baseline (24 viewers, claude-opus-4-7) plus the
`SessionOptions.outputSchema` transmission fix from the same bug report's
sibling, and rolls in the four prior Unreleased commits (SandboxPolicy +
PolicyBytecodeVerifier + L3 subprocess modules + audit follow-ups) into a
single release. Prompt caching is now wired end-to-end on Anthropic,
OpenAI, and Gemini; cost accounting reflects cache-read discounts; and the
`LoopEnded → result()` happens-before contract is now guaranteed.

### Fixed — `LoopEnded` delivery race between publisher and `result()` (hv2-bug2 Issue 2)

`AgentSessionImpl.runLoop` resolved `resultFuture` INSIDE its try block;
`closeRuntime()` (which drains the per-session publisher) ran in the
immediately-following finally. A subscriber that captured `LoopEnded` for
usage/cost observability could race the caller's read of `result().get()`
or `runBlocking(...)` and silently drop data. The matchmaking baseline
observed this on 3 of 24 viewers — `SessionUsageCapture.result()` returned
null, logged cost was `$0.0000`.

Fix: reorder so `closeRuntime()` runs BEFORE `resultFuture` settles. Every
responsive subscriber observes `LoopEnded` by the time
`result().get()` / `runBlocking(...)` unblocks. The 5-second
publisher-executor grace stays in place — the change is purely about
ordering. Simplified the `Throwable` rethrow path with a sneaky-throw
helper so JaCoCo branch coverage stays clean (the prior `instanceof`
cascade left unreachable `RuntimeException` / `IllegalStateException`
branches since `AgentLoop` catches `Exception`).

Three new tests (`subscriberObservesLoopEndedBeforeRunBlockingReturns`,
`...BeforeResultFutureCompletes`,
`multipleSlowSubscribersAllObserveLoopEndedBeforeRunBlockingReturns`)
reproduce the race deterministically with a slow subscriber and fail
pre-fix.

### Added — Anthropic prompt caching (hv2-bug2 Issue 1)

`AnthropicModel` now annotates outgoing requests with `cache_control`
breakpoints by default and decodes the cache-token billing fields that
the wire returns. The matchmaking baseline (12.3M input tokens) paid
`$235.54` flat against `claude-opus-4-7`'s no-cache rate card; post-fix
that same workload reuses the system + tools prefix across turns at the
0.10× cache-read rate.

Public surface:

- `CachePolicy` sealed type (`Disabled` / `ShortLived` / `LongLived`) on
  the `AnthropicModel(modelId, config, CachePolicy)` constructor. Default
  is `shortLived()` (5m TTL, 1.25× write); `longLived()` (1h TTL, 2.00×
  write) is opt-in for long-lived sessions where the cache prefix lives
  more than 5 minutes between turns; `disabled()` opts out entirely.
- `CacheControl` and `SystemContent` records for the wire representation.
- `ContentBlock.withCacheControl(CacheControl)`,
  `ToolDefinition.withCacheControl(...)`,
  `MessagesRequest.systemAsText()` projection.
- `MessagesRequest.system` widened from `String` to `Object` to carry
  either the legacy plain string or the cache-aware `List<SystemContent>`
  the API requires when `cache_control` annotations are present. Builder
  callers are unaffected; direct canonical-constructor callers must pass
  an `Object`. The `Builder.withSystem(List<SystemContent>)` overload is
  the cache-aware path.
- `ToolDefinition` record gained a `cacheControl` component; the prior
  3-arg form is preserved as a convenience constructor.
- `ContentBlock` record gained a `cacheControl` component; existing
  factories (`text`, `toolUse`, `toolResult`, `image`, `document`,
  `thinking`) keep their signatures and default the new field to null.

Breakpoint placement (the canonical agent-loop pattern documented at
`platform.claude.com/docs/en/build-with-claude/prompt-caching`):

- end of system prompt (1 breakpoint)
- end of tools array (1 breakpoint)
- tail block of penultimate message — rolling, only when there are >=2
  messages (1 breakpoint)
- tail block of last message — rolling (1 breakpoint)

The penultimate breakpoint is the critical addition for long agent loops:
without it, after ~5 tool-heavy turns the conversation grows past
Anthropic's 20-block lookback window and the system+tools cache becomes
unreachable from the single last-message breakpoint — every turn becomes
a full cache miss. The pair maintains rolling cache lookup at every turn
boundary.

`StreamingIterator` now captures `cache_creation_input_tokens` and
`cache_read_input_tokens` from the `message_start` event and surfaces
them through `Response.Usage` in the canonical disjoint form.

### Added — OpenAI cache-read token surfacing

`openai.api.ApiUsage` extended with `input_tokens_details.cached_tokens`
(the Responses API field for cache reads) and
`output_tokens_details.reasoning_tokens` (captured for trace fidelity).
`cachedTokensOrZero()` helper normalizes the absent-field path (older API
versions and OpenAI-compatible proxies). OpenAI's caching is automatic on
prompts ≥1024 tokens — no request-shape changes needed.

`OpenAIModel.StreamingIterator` extracts `cached_tokens` from
`response.completed` and re-projects to the canonical Helios disjoint
shape: wire `input_tokens` is the TOTAL with cached as a SUBSET, so the
provider subtracts before mapping to `Response.Usage.inputTokens`.
OpenAI does not premium cache writes, so `cacheCreationInputTokens` stays
zero. Defensive clamp at zero in the pathological cached > input case.

### Added — Gemini implicit-cache token surfacing (Interactions API v2)

`gemini.api.InteractionUsage` gained `total_cached_tokens` (the
Interactions API field, snake_case wire shape). Gemini 2.5+ models
enable implicit caching automatically on the Interactions API surface —
no request annotation needed; the server bills the cached portion at
the discounted rate and reports the count. Pre-2.5 models omit the
field; `cachedTokensOrZero()` normalizes both shapes.

`GeminiModel.buildDoneEvent` re-projects to the disjoint Helios shape
(same pattern as OpenAI: `total_input_tokens` is the TOTAL with
cached as a SUBSET; subtract on the way in). Explicit caching (the
`CachedContent` CRUD API) is intentionally NOT implemented — it's not
supported by the Interactions API that Helios uses, only by the legacy
`generateContent` endpoint.

### Changed — canonical `Response.Usage` is now four-class disjoint

`Response.Usage` extended from 3 to 5 fields:
`(inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens, totalTokens)`.
The four counts are disjoint — a token contributes to exactly one of them
— so `CostCalculator.Pricing` can apply per-class rates without
double-counting. The 2-arg `Usage.of(input, output)` factory is preserved
and defaults cache fields to zero; the new
`Usage.of(input, output, cacheCreation, cacheRead)` factory takes the full
breakdown. `totalTokens` sums all four classes.

Breaking for direct canonical-constructor callers
(`new Usage(int, int, int)` becomes `new Usage(int, int, int, int, int)`).
The framework's own `SessionState.accumulateUsage` was updated; one test
call site in `SessionStateTest` was updated. No other internal callers.

### Changed — `CostCalculator.Pricing` carries per-class rates

`Pricing` extended from 2 to 4 rate fields:
`(inputMicroUsdPerMillion, outputMicroUsdPerMillion, cacheWriteMicroUsdPerMillion, cacheReadMicroUsdPerMillion)`.
The 2-arg constructor is preserved as a convenience that defaults cache
rates to the base input rate — conservative, so a deployer who hasn't
configured cache rates pays the same per-token amount on cached tokens
as on uncached input (caching cannot make billing UNDER-report relative
to the no-cache run).

New factories:

- `Pricing.ofUsdPerMillion(input, output)` — 2-arg convenience (cache
  rates default to input rate)
- `Pricing.ofUsdPerMillion(input, output, cacheWrite, cacheRead)` —
  explicit 4-arg rates
- `Pricing.anthropicCaching(input, output)` — Anthropic's published
  multipliers (1.25× write for 5m, 0.10× read). For 1h TTL (2.00× write)
  build a `Pricing` explicitly with the 4-arg factory.

`Pricing.cost(Usage)` sums per-class contributions via integer
multiply-then-divide with `Math.multiplyExact` overflow checks.

### Fixed — `SessionOptions.outputSchema` now reaches the provider every turn

The agent loop dispatched the unconstrained `Model.chatStream` regardless
of whether a session-level `outputSchema` was configured. The schema
reached the provider's native structured-output channel only via the
unary `chat(messages, tools, outputSchema)` path the loop never took, so
callers who set `withOutputSchema(...)` and then ran a session got
freeform text loosely guided by the system prompt; the post-hoc
`StructuredContentParser` validator failed against any non-trivial
nested schema. Added
`Model.chatStream(messages, tools, outputSchema, cancellation)` and
wired `TurnRunner` to dispatch it whenever the session has an output
schema. `CodeActPreset.withSubLm` — whose terminal answer is the
in-sandbox `submit()` payload, not the assistant text — leaves the
session-level `outputSchema` empty so its sandbox protocol stays
load-bearing.

### Added — `SandboxPolicy` scaffolding for JShell L2 enforcement

New `ai.singlr.repl.sandbox.policy` package introduces the seam through which a bytecode verifier rejects snippet invocations of denied APIs.

Public surface:

- `SandboxPolicy` record + Builder with `deniedClasses`, `deniedPackages`, `denyReflection`, `denyNativeAccess`, `denyDynamicClassDefinition`, `onViolation`. `SandboxPolicy.permissive()` is the default — denies nothing, equivalent to no policy layer.
- `JvmSandboxConfig.withSandboxPolicy(SandboxPolicy)` Builder option. Permissive default preserves existing behaviour. Non-permissive policies travel via `--sandbox-policy=<base64>` argv encoded by `SandboxPolicySerialization`.
- `GuardedExecutionControl` (subclasses `LocalExecutionControl`) and `GuardedExecutionControlProvider` replace JShell's stock `"local"` engine in `JvmSandboxBootstrap`. Every snippet class flows through `BytecodeVerifier.verify(...)` before `super.load(...)`.
- `SandboxPolicyException` carries `deniedOwner` / `deniedMember` / `rule` for fault-localised tracebacks to the model.

Why now: `SecurityManager` was finalised for removal by JEP 486 in JDK 24 and is permanently disabled on JDK 25. The only honest in-JVM enforcement is bytecode-level via JShell's `ExecutionControl` SPI. This remains defense-in-depth — the only authoritative isolation boundary is the host OS.

Breaking for direct canonical-constructor callers of `JvmSandboxConfig` (record gained a fifth component, `sandboxPolicy`). Builder callers are unaffected.

### Added — `PolicyBytecodeVerifier` real enforcement

`BytecodeVerifier.forPolicy(SandboxPolicy)` now returns a real scanner (`PolicyBytecodeVerifier`) for non-permissive policies; permissive policies still get the `NO_OP` fast path. The scanner is built on the JDK Classfile API (JEP 484, finalised in JDK 24).

Scope:

- **Instruction families scanned**: `INVOKE*` (virtual/special/static/interface), `NEW`, `GET*`/`PUT*` fields, and `LDC` of `Class<?>` constants. `INVOKEDYNAMIC` is deliberately skipped — its bootstrap method reference points to platform code (`LambdaMetafactory`, `StringConcatFactory`, `ObjectMethods`); scanning it would reject every lambda and string concatenation without security benefit, while the dangerous capability (explicit `MethodHandles.lookup()`) is caught at the `INVOKESTATIC` site.
- **Rule order on match**: explicit `deniedClasses` → `deniedPackages` → categorical `denyReflection` / `denyNativeAccess` / `denyDynamicClassDefinition`. The exception's `rule` label names the most specific user-configured rule when overlap occurs.
- **Reflection rule**: denies entire `java/lang/reflect/*` and `java/lang/invoke/*` packages plus `Class.forName`, `Class.getMethod*`, `Class.getField*`, `Class.getConstructor*`, `Class.getDeclared*`. Non-reflective `Class` methods (`getName`, `cast`, `isInstance`, …) stay callable.
- **Native-access rule**: denies `java/lang/foreign/*` and `System.loadLibrary` / `System.load` / `Runtime.loadLibrary` / `Runtime.load`.
- **Dynamic-class-definition rule**: denies `MethodHandles.Lookup.defineClass`, `defineHiddenClass`, `defineHiddenClassWithClassData`, and `ClassLoader.defineClass`.

**Denial surfacing**: JShell silently swallows the `ClassInstallException` thrown when the verifier rejects load (snippet stays `VALID`, no exception attached to `SnippetEvent`). `GuardedExecutionControl.verifyAll` writes the policy message to `System.err` before rewrapping into `ClassInstallException`; the sandbox bootstrap captures stderr during `execute` and forwards it back to the host as the eval result's `stderr` so the model receives a clean policy traceback. The seam's javadoc documents this defensive contract.

### Fixed — L3 module restriction crashed classpath-launched subprocesses

Followup to the L3 landing below. Empirical testing exposed that `--limit-modules ai.singlr.repl,...` crashed the subprocess JVM at boot-layer init with "Module ai.singlr.repl not found" whenever the bootstrap was launched from classpath (the non-JPMS path) — classpath JARs are unnamed-module members, not observable named modules, so naming them in `--limit-modules` is fatal.

Fix: `SubprocessModules.REQUIRED_ROOTS` no longer includes `ai.singlr.repl`; the bootstrap module name is now `SubprocessModules.BOOTSTRAP_MODULE` and `JvmSandbox.buildLaunchCommand` appends it to `--limit-modules` only when `parentUsesModulePath(parentArgs)` is true. Classpath launches get the JDK-only baseline; the bootstrap loads via classpath into the unnamed module which can read every observable JDK module.

`SubprocessModules.limitModulesArg(boolean)` now takes a `modulepathLaunch` parameter from `JvmSandbox`. Breaking for direct callers of the previous zero-arg form, but the type is internal-facing and the JVM-launch logic is the only realistic consumer.

New `SubprocessModulesClasspathLaunchTest` (3 tests) empirically launches the bootstrap with a manually-constructed classpath-only command (no `--module-path`, no `--add-modules ai.singlr.repl`) and verifies:

- the subprocess starts (regression coverage for the pre-fix crash)
- `java.sql` is absent from the subprocess's boot layer
- `java.net.http` is also stripped under classpath launch — *stricter* than modulepath because module-info `requires` clauses are ignored when a JAR loads from classpath

`SubprocessModulesTest` updated to cover both `modulepathLaunch=true` and `modulepathLaunch=false` paths.

### Added — L3 subprocess module restriction (`SubprocessModules`)

New `ai.singlr.repl.sandbox.SubprocessModules` sealed interface controls the subprocess JVM's `--limit-modules` flag. Three factories:

- `unrestricted()` — default; no `--limit-modules`, all JDK modules observable
- `minimal()` — required roots only (`java.base`, `java.compiler`, `jdk.compiler`, `jdk.jshell`, `ai.singlr.repl`); strips `java.sql`, `java.naming`, `java.scripting`, `java.desktop`, `jdk.httpserver`, and most JDK modules the bootstrap doesn't transitively need
- `allowingExtras(String...)` — required + named extras

Wired through `JvmSandboxConfig.withSubprocessModules(...)`. Composes with L2 enforcement: limit-modules eliminates whole categories at compile time (snippets that `import java.sql.*` fail with a clean compiler diagnostic before the L2 verifier runs), the verifier catches the rest at load time.

**Bootstrap-transitive-closure limit** documented on `SubprocessModules` javadoc: modules transitively required by the bootstrap (today `java.net.http` via `ai.singlr.core`'s `HttpClientFactory`) stay observable under any L3 setting. Deployers who need to strip those modules use L2 `deniedPackages` instead.

`JvmSandbox.shouldPropagateJvmArg` extended to also filter `--add-modules` and `--limit-modules` from inherited parent args so Maven Surefire's propagated `--add-modules=ALL-MODULE-PATH` doesn't defeat the L3 restriction. Six new subprocess tests prove the round trip: `--limit-modules` reaches the subprocess JVM, target modules are absent from `ModuleLayer.boot()`, snippets fail to compile against them, and the `allowingExtras` path makes named modules observable again.

Breaking for direct canonical-constructor callers of `JvmSandboxConfig` (record gained a sixth component, `subprocessModules`). Builder callers unaffected.

### Added — allow-list policy mode + `SandboxPolicy.noEgress()` curated preset

`SandboxPolicy` gains an `allowedPackages` field. When non-empty, the verifier flips for JDK-scoped owners (under `java/`, `javax/`, `jdk/`, `sun/`, `com/sun/`): default-deny, allow only if the owner's package matches one of the allowed prefixes. Non-JDK owners (the snippet's own `REPL.$JShell$N` wrappers, user helper classes, third-party JARs) bypass the allow-list — they're the verified code. Deny rules layer on top as overrides; rule label on violation is `allowedPackages-default-deny`.

`SandboxPolicy.noEgress()` curated preset for the "compute with no egress" use case:

- **Allows**: `java.lang`, `java.util` + sub-packages (`stream`, `function`, `regex`, `concurrent.atomic`), `java.math`, `java.time` + sub-packages, `java.text`, `java.io`
- **Denies**: `java.lang.ProcessBuilder`, `java.lang.Runtime`, `java.lang.Thread`, `java.lang.ThreadGroup` (escape via `java.lang`); `java.io.FileReader`, `java.io.FileWriter`, `java.io.FileInputStream`, `java.io.FileOutputStream`, `java.io.RandomAccessFile`, `java.io.ObjectInputStream`, `java.io.ObjectOutputStream` (file IO + deserialization escapes via `java.io`)
- **Enables** `denyReflection`, `denyNativeAccess`, `denyDynamicClassDefinition`

Single API call for the most common enterprise posture; deployers customise from there.

Breaking for direct canonical-constructor callers of `SandboxPolicy` (record gained `allowedPackages` as the first component). Builder callers and `permissive()` callers unaffected.

### Added — policy verifier audit follow-ups

Closes three gaps identified in the PR 2 self-review:

- **Subprocess end-to-end enforcement test** (`SandboxPolicySubprocessEnforcementTest`). Launches a real `JvmSandbox` with a denying policy and asserts the policy message reaches the host as `ExecutionResult.stderr()`. Proves the full host→subprocess→verifier→stderr round trip beyond the in-process JShell tests.
- **Extended reflection rule** on `java.lang.Class`. Now also denies `newInstance`, `getRecordComponents`, `getEnclosingMethod`, and `getEnclosingConstructor` in addition to `forName`, `getMethod*`, `getField*`, `getConstructor*`, and `getDeclared*`. Unit tests cover each new denial.
- **Static-receiver-type limitation documented** on `PolicyBytecodeVerifier`'s class javadoc. `INVOKEVIRTUAL`/`INVOKEINTERFACE` carry the static compile-time owner, so a deny on a method declared on a non-final superclass can be bypassed by narrowing the receiver's static type to a subclass. Documents the practical mitigations: realistic class-load entry points (`MethodHandles.Lookup.defineClass`) are caught by direct name, `denyReflection` denies entire `java/lang/reflect/*` and `java/lang/invoke/*` packages, and OS-level isolation remains the authoritative perimeter.

## [2.2.0] — 2026-05-19 — production-hardening pass

Wide-ranging hardening pass driven by an independent review of the v2 surface. Eleven theme commits on the original `review/production-hardening` branch plus a back-fill pass (that caught a real descendant-kill ordering bug in the security tests) plus Path B (opt-in trace-side redaction `PgConfig.withRedactor` and the C1 sandbox-RPC-channel relocation closing the stdout-forgery vector) plus Path C (closing the two Criticals from the Path B audit and tightening the surrounding code per its Highs and Mediums).

### Security — redactor JSON-escape bypass (Path C Critical #2)

`PgToolCallJournal.start` previously serialised `record.args()` via Jackson then ran the byte-level `Redactor` over the resulting JSON string. `SecretRegistry.register` accepted any ASCII byte, including JSON metacharacters (`"`, `\`, control chars), so when those bytes appeared in a registered secret Jackson escaped them during serialisation and the byte-level scan no longer matched the raw secret bytes — the secret survived verbatim in `helios_tool_calls.args_json`. Closed two ways:

- `SecretRegistry.register()` now refuses control characters (`0x00–0x1F`, `0x7F`), double-quote, and backslash with a message naming the JSON-escape rationale. Registered secrets are now provably safe for byte-level redaction in both pre- and post-serialise call sites. Breaking for callers registering multi-line PEM keys or quote/backslash-containing secrets.
- `PgToolCallJournal.start` now walks `record.args()` and redacts each string leaf BEFORE Jackson serialises (mirrors the safe pattern `PgTraceStore.redactValues` already uses for span attributes).

Exploit test (`PgToolCallJournalTest.EXPLOIT_secretWithJsonSpecialBytesLeaksThroughPostSerialiseRedactor`) was committed first to prove the leak against unfixed code; reshaped into the registration-refusal tests in `SecretRegistryTest` and the nested-strings demonstration test in `PgToolCallJournalTest` after the fix.

### Security — C1 reframed honestly (Path C Critical #1)

The original C1 commit (Path B) claimed the stdout-RPC forgery vector was closed. It is — for the FileDescriptor.out path — but a JShell snippet can still reach `JvmSandboxBootstrap.realOut` (which now wraps the RPC socket directly) via `Class.forName` + `setAccessible(true)` and forge frames. The reflection access is gated by JPMS module accessibility:

- Clean modulepath launch: `ai.singlr.repl` module-info does not open `ai.singlr.repl.sandbox`, so `setAccessible(true)` throws. Reflection path closed.
- Classpath launch: bootstrap lives in the unnamed module, fully open to reflection. Forgery reproduces.
- Modulepath launch with `--add-opens=ai.singlr.repl/ai.singlr.repl.sandbox=...` inherited from the parent (common with test runners and JVM instrumentation): equivalent to classpath for this gap.

This release adds:

- `JvmSandboxBootstrap.main()` javadoc rewritten to enumerate exactly what C1 closes vs. what it doesn't, the three launch regimes above, and the deployer's responsibility to arrange OS-level isolation around the host process for untrusted workloads. The intra-JVM defenses raise the bar but are not a substitute for an external boundary.
- `warnIfReducedIsolation()` emits a one-line `WARNING` on stderr at bootstrap startup when the module is unnamed OR the sandbox package has been opened to unnamed modules — surfaces the reduced-isolation regime so deployers don't take a classpath launch by accident.
- Exploit test (`JvmSandboxTest.reflectionForgesAnRpcCallToHost`) committed as a regression artifact pinning the known limit.

### Security — same-UID bind/accept race documented (Path C High #3)

`JvmSandbox.create()` binds a Unix-domain RPC socket in a 0700 temp directory and forks the JShell subprocess to connect back. The 0700 directory blocks cross-UID attackers from enumerating the socket, but a same-UID attacker that polls `/tmp/helios-rpc-*` can race the subprocess for the single `backlog=1` slot. Documented in `JvmSandbox.create()`'s javadoc as an explicit assumption — deployers must arrange per-session UIDs (containers, namespaces, per-tenant service accounts) or accept the limitation. A future change will close the race authoritatively via Panama-based `SO_PEERCRED` (Linux) / `LOCAL_PEERCRED` (BSD/macOS) verification on accept.

### Added — `JvmSandboxConfig.withSubprocessStartupTimeout(Duration)` (Path C High #5)

The 10-second wait for the subprocess to connect back on its RPC socket was previously hardcoded. JVM cold-start under heavy I/O (slow NAS-mounted JDK, security software scanning, profilers attached to parent) can exceed it, producing a confusing permanent launch failure. New builder option raises (or lowers) it; the default stays 10 s; the existing timeout-exceeded error message already names the configured duration so the failure mode is debuggable. Breaking for direct canonical-constructor callers (`JvmSandboxConfig` record gained a fourth component); Builder callers are unaffected.

### Added — `JvmSandboxBootstrap.warnIfReducedIsolation`

See "C1 reframed honestly" above. Package-private; emits WARNING to the supplied PrintStream when the bootstrap module's isolation properties are weakened by an unnamed-module launch or by `--add-opens` propagated from the parent.

### Fixed — listener double-close on subprocess startup timeout (Path C Medium #6)

`JvmSandbox.acceptWithTimeout` now takes ownership of the listener and closes it on every exit path (success, timeout, interrupt, execution failure) via try/finally. `JvmSandbox.create` transfers ownership before the call and no longer closes the listener itself. Previously the timeout cleanup path closed the listener twice — idempotent and harmless, but misleading and a latent footgun for a future refactor. Test-first: `acceptWithTimeoutClosesListenerOnSuccess` was RED before the fix.

### Changed — `PgConfig.redact` and `redactValues` javadocs document reference semantics

The methods return either the original input reference (when no redactor is configured) or a freshly-allocated string/map (when one is). The return type doesn't distinguish — callers MUST treat the result as read-only. Documented explicitly; no behaviour change. Addresses Path C Medium #7.

### Changed — `JvmSandboxBootstrap.sendRpc` javadoc clarifies the `\0RPC:` prefix is required

The `\0RPC:` magic prefix on subprocess→host frames is required by the host-side `ProcessTransport.receive` parser to distinguish RPC frames from incidental subprocess writes — not vestigial decoration on the dedicated channel. Documented to prevent a future "the channel is dedicated, drop the prefix" cleanup that would silently route every RPC response into the stdout buffer. Addresses Path C Medium #8.

### Changed — `JvmSandbox.close()` javadoc documents two limits

Descendant kill races snippets forking new descendants in the microsecond window between snapshot and parent kill; the new descendants escape. Stdout reader join is best-effort and may leak the reader virtual thread if the subprocess is stuck in uninterruptible kernel sleep (D-state) — bounded to one orphan per stuck session. Both limits are deployer-arranged-isolation territory (cgroup pids.max, external supervisor). No behaviour change. Addresses Path C Mediums #9 and #10.

### Changed — `JvmSandbox.createPrivateSocketDir` javadoc is honest about Windows

Previously claimed the JDK's default temp-dir ACLs were "appropriate" on non-POSIX filesystems. Helios has not validated this on Windows; the javadoc now states it explicitly and tells Windows deployers to verify the ACL story for their JDK and filesystem. Addresses Path C High #4.



### Security — sandbox RPC moved off subprocess stdout (C1)

`JvmSandbox` ↔ host RPC previously rode on the subprocess's stdout with a `\0RPC:`-prefixed magic line discriminator. A JShell snippet could write a forged frame directly to `FileDescriptor.out` (bypassing the captured-output redirection) and reach the host's RPC dispatcher — invoking any registered `HostFunction` out-of-band. Pre-fix behavioural test (`JvmSandboxTest.rawStdoutWriteDoesNotForgeAnRpcCallToHost`) confirmed the forgery worked.

Now: per-session Unix domain socket bound in a private temp directory (mode 0700 on POSIX). Host accepts exactly one connection from the subprocess on startup, closes the listener, deletes the socket file. Subprocess stdout stays for captured-output only and is never parsed as RPC. The same behavioural test now confirms forged frames on stdout are inert. The vestigial `\0RPC:` prefix on subprocess→host frames is retained because it costs nothing and keeps the `ProcessTransport` test surface stable.

Bootstrap now requires `--rpc-socket=<path>` as a launch argument and connects on startup; absence is a fatal error (exit code 2). Subprocess stdin is closed by the host post-spawn since the socket carries both directions of RPC.

### Added — opt-in `PgConfig.Builder.withRedactor(Redactor)`

Persistence layer can now apply a `Redactor` to trace and journal text fields before persisting. Default unset = verbatim capture (current behaviour, the documented evals / debug surface). Set the redactor and `PgTraceStore.store` / `PgToolCallJournal.start|complete|fail` route every text field through it; trace and span attribute maps are redacted per-value before `JsonbMapper.toJsonb`. Tool-call `args` is serialised first then passed through the redactor (byte-level scrubbing catches registered secrets in any JSON-string position).

### Added — `SerializedError.withoutStackTrace()` + `ResultMessage.withoutStackTraces()`

Library-provided utility methods for deployers who want to strip stack frames from terminals before forwarding to downstream clients. Helios itself does NOT auto-apply these — diagnostic info flows to the deployer by default; redaction is the deployer's call at their HTTP layer.

### Added — `JShellExecutionProvider.redactingBindingsListener(registry, delegate)`

Wraps a `SandboxBindingsListener` so operator telemetry of sandbox working memory automatically redacts against the configured `SecretRegistry`. Applied transparently when a registry is configured on the provider; `var apiKey = "sk-..."` in JShell working memory now reaches the listener as `var apiKey = "<redacted:NAME>"`.

### Added — `CancellationToken.activeCallbackCountForTests()`

Visible-for-testing accessor exposing the count of currently-attached `onCancel(...)` callbacks. Used to prove per-call sites correctly invoke `Registration#remove()` so callbacks don't accumulate on long-lived per-session tokens.

### Changed — `RuntimeServer.Builder` defaults to `127.0.0.1`

Fail-secure: the `POST /sessions` route is unauthenticated and creates real model-spending sessions; previously binding `0.0.0.0` by default exposed that surface to anything that could route to the host. Deployers who want external traffic opt in explicitly via `withHost("0.0.0.0")` or a specific interface.

### Changed — `FinishReason.LENGTH` now terminates the loop

When the provider truncates output at its `max_output_tokens` cap, `StopClassifier` now returns `ResultMessage.ErrorDuringExecution(kind="max-tokens")`. The previous behaviour mapped `LENGTH` to `Optional.empty()` (continue) — the loop would re-issue the same turn, the model would hit the same cap again, and `maxTurns` (default 100) silently burned through. Deployers that need higher per-turn ceilings should raise `ModelConfig.maxOutputTokens` instead of relying on the loop to retry. Breaking for callers that relied on the silent re-issue.

### Changed — SSE event emit is bounded, no longer blocks indefinitely

`AgentSession`'s event publisher previously used `SubmissionPublisher.submit(...)` which blocks the producer when any subscriber fills its 256-item buffer — a paused SSE client could pin the agent loop forever. The loop now emits via `offer(...)` with bounded timeouts: 1s for routine events (`AssistantText`, `ToolUse`, `ToolResult`, etc.) which may drop on a sustained slow consumer with a FINE log; 30s for control events (`LoopEnded`, `QuestionAsked`, `Error`) which log WARNING on the rare drop. Callers that depended on guaranteed delivery for routine events lose that guarantee.

### Changed — `ModelConfig.toString()` redacts the API key and header values

Records' default `toString()` includes every component; `apiKey` and every `headers` entry-value used to land verbatim in any `logger.info("config={}", cfg)` callsite. The override emits `apiKey=<redacted>` and `headers={Authorization=<redacted>, …}` — header names are preserved for diagnostic context.

### Changed — Gemini API records renamed `is*()` → `hasType*()`

Three records exposed boolean accessors (record components or helper methods) that Jackson 3.x's `AUTO_DETECT_IS_GETTERS` treats as virtual properties: `StreamingEvent.isInteractionCreated()` etc. → `hasType*()`; `InteractionResponse.isCompleted()` / `.isFailed()` / `.requiresAction()` → `hasStatus*()`; `Step.isError` record component → `errorFlag` (with `@JsonProperty("is_error")` preserving the wire format). Source-incompatible for direct callers of those methods.

### Fixed — `JvmSandbox.close()` and shutdown hook reap subprocess descendants

Snippets that call `Runtime.getRuntime().exec(...)` previously orphaned their grandchildren past sandbox lifetime — `process.destroyForcibly()` killed the parent but the descendant walk happened after, and once the parent dies the OS reparents orphans to init so `process.descendants()` returns empty. Both call sites now snapshot descendants BEFORE killing the parent, then forcibly destroy the snapshot. (The original fix in the branch had the ordering wrong; the back-fill subprocess test caught it.)

### Fixed — Uninterruptible JShell snippets escalate via `jshell.stop()`

A CPU-bound snippet that ignores `Thread.interrupt()` previously persisted as a zombie thread after `evalThread.join(1s)` returned with the thread still alive. The timeout ladder now goes `Thread.interrupt` → 1s join → `jshell.stop()` → 1s join, and the same sandbox is verified usable for a follow-up execute after the timeout.

### Fixed — `JvmSandboxBootstrap.collectBindings` catches `Throwable`, not just `Exception`

A binding whose `toString()` throws `StackOverflowError` / `OutOfMemoryError` previously escaped the per-binding `catch (Exception ...)`, aborting the snapshot and propagating into the virtual thread's uncaught handler. Now catches `Throwable` and renders `<error: …>` per binding.

### Fixed — `RetryPolicy.execute` catches `Exception`, not `Throwable`

`Error` subtypes (OOM, StackOverflow, LinkageError) previously got wrapped in `RetryExhaustedException` and propagated as a runtime exception, defeating the documented "Loop crash semantics" invariant where Errors escape so the host thread dies cleanly. Now narrowed to `Exception`.

### Fixed — `Tool.execute` preserves interrupt status

When the executor's exception chain carries an `InterruptedException` (typical for tools that wrap blocking calls), the calling thread's interrupt flag is now re-set before returning the failure `ToolResult`. Subsequent blocking calls observe the interrupt and the session loop can cancel promptly.

### Fixed — `TraceBuilder.computeTotalTokens` soft-fails malformed token attributes

A custom span emitter writing a non-integer `inputTokens`/`outputTokens` attribute previously threw `NumberFormatException` at `end()` time, abandoning the entire trace artifact. Malformed values now contribute 0 to the total; the trace builds with the rest of the spans intact.

### Fixed — `CommandGrant` timeout kill ordering

The descendant kill happened before the parent kill, leaving a window where the parent could fork new descendants during the sweep. Now kills the parent first (so it can't fork), then walks descendants.

### Fixed — `SessionQuestionGateway` captures the cancellation `Registration`

`ask(...)` registered an `onCancel(...)` callback and dropped the returned `Registration` on the floor; every `AskUserQuestion` call accumulated one inert closure pinning the completed future on the long-lived per-session `CancellationToken`. Now captured and `remove()`d in `finally`.

### Fixed — `OnnxModelDownloader` rejects HuggingFace path traversal

The HF API's `path` field used to flow verbatim into `Files.copy(... REPLACE_EXISTING)` with only a whitelisted `modelName` as the prior defence. A compromised mirror, MITM, or future malicious model card returning `"../../tmp/pwn"` or an absolute path could overwrite any JVM-writable file. `OnnxModelDownloader.resolveLocalPath` now refuses absolute paths and lexically rejects traversal after normalisation.

### Fixed — `OnnxEmbeddingModel` tensor + tokenizer leaks

`embedInternal` had the `OnnxTensor` cleanup `finally` scoped to the `ortSession.run(inputs)` try — an `OrtException` thrown by the second or third `OnnxTensor.createTensor` leaked the already-built tensors. The cleanup now wraps the entire creation + run block. `close()` also closes the DJL `HuggingFaceTokenizer` (holds native Rust allocations), where it previously closed only the `OrtSession`.

### Fixed — `OnnxModelDownloader` idempotent finished-marker creation

`Files.createFile(FINISHED_MARKER)` previously threw `FileAlreadyExistsException` on a concurrent second downloader, surfacing as an opaque init failure after the model itself downloaded fine. Marker creation now exists-checks then narrowly catches `FileAlreadyExistsException`.

### Fixed — `PgConfig` rejects malformed schema names

`PgConfig#qualify(sql)` interpolates the schema name into SQL via `String.replace("%s", schema)`. Helidon DbClient cannot parameterise identifiers, so the compact-constructor validator is the only line of defence if the schema name ever flows from configuration or external input. Validates against Postgres' unquoted-identifier shape: `[A-Za-z_][A-Za-z0-9_]{0,62}`.

### Fixed — Gemini and Anthropic SSE `BufferedReader` use UTF-8 explicitly

Both providers used the platform-default charset; pre-JDK-18 container base images on Linux default to `ISO-8859-1` and silently mangle multi-byte content (CJK assistant text, emoji, grounding citation titles). OpenAI was already correct; the other two now match.

### Internal — convention sweep across the source tree

Inline FQNs (`new java.util.ArrayList<…>`, `java.util.Map.of()`, etc.) replaced with proper imports across `helios-core`, `helios-session`, `helios-runtime`, `helios-persistence`. Inline `s == null || s.isBlank()` patterns replaced with `Strings.isBlank(s)` across `helios-gemini`, `helios-anthropic`, `helios-openai`, `helios-onnx`. Per `CLAUDE.md` conventions.

### Path B closed all three deferred items from the original review

C1 (sandbox stdout-RPC forgery) — fixed; see "Security — sandbox RPC moved off subprocess stdout" above. Theme J (persistence redaction) — reframed as the deployer's responsibility (the persistence layer's job is faithful capture for evals and debug) with an opt-in `PgConfig.Builder.withRedactor` hook for deployers who want defense-in-depth at the persistence boundary; see "Added — opt-in `PgConfig.Builder.withRedactor(Redactor)`" above. M4 (provider error body in exception messages) — left as documented diagnostic behaviour; deployers catch the exception at their call site and decide what to log.

## [2.1.2] — 2026-05-18

Re-cut of 2.1.1 plus a fix for an SSE-subscribe race that surfaced during the 2.1.1 release workflow. **2.1.1 never reached Maven Central** — the release-action test phase tripped over the race and skipped the deploy. 2.1.2 is the first publicly-published version with the Phase 6c P0 fixes from 2.1.1 (see entry below) plus this fix.

### Fixed — SSE subscription race in `helios-runtime`

`GET /sessions/{id}/events` writes the HTTP 200 response before the SSE handler's `Flow.Subscriber.onSubscribe` registers with the session's `SubmissionPublisher`. Clients that issued `POST /sessions/{id}/messages` immediately after seeing the 200 could lose the early events submitted in that window (`UserMessageReceived` most commonly). `AgentHttpService.eventsHandler` now emits a synthetic `Ready` SSE event from inside `onSubscribe(...)` so clients can synchronously confirm subscription is live before triggering work. Backwards-compatible — clients that ignore unknown event types see no behavior change beyond the new event.

### Cleaned up — top-level `README.md`

Removed v1 cruft (the v1 `Agent` / `Workflow` / `Team` / `RlmHarness` / `CodeActHarness` / `Memory` / `core.eval` surface was deleted in the v2.0.0 cut and the README still described it). Rewritten around the actual v2 shape: direct `Model` use, `AgentSession` + `SessionPresets`, the `helios-runtime` HTTP surface, the `helios-repl` substrate + `CodeActPreset`, durability primitives (with the note that session integration is pending), `helios-persistence` Pg* impls (no `PgMemory`).

## [2.1.1] — 2026-05-18 (failed release — never published)

P0 robustness fixes for the context compaction pipeline shipped in 2.1.0. The default compactor could split tool-call/tool-result pairs across slice boundaries (provider rejection), a hung summary call could block the entire session loop, summary-call spend was invisible to the cost gate, and the watermark only fired AFTER a turn — so a previous turn's huge tool result could overflow the next request before we ever compacted. All four are addressed here.

### Changed — `ContextCompactor.compact(...)` now returns `CompactionResult` (history + usage)

Breaking change to the freshly-minted 2.1.0 SPI: implementations return a `CompactionResult(history, usage)` record so the agent loop can accumulate the compaction step's `Usage` into the session totals and apply the configured `CostCalculator`. Without this, summary-call spend was invisible to `SessionLimits.maxBudgetMicroUsd` gating. Per Helios's [clean-slate policy for internal users][cleanslate], breaking a 1-day-old SPI is preferable to carrying the wart. Custom compactors update by wrapping their return: `CompactionResult.noOp(history)` for pure trim, `new CompactionResult(history, summaryUsage)` for compactors that call a model.

[cleanslate]: https://github.com/anthropic/helios/blob/main/CLAUDE.md

### Fixed — `DropMiddleToolResultsCompactor` now boundary-aligns tool-call pairs

Naive `subList(0, head) | subList(head, n-tail) | subList(n-tail, n)` slicing could split assistant `tool_call` messages from their matching `TOOL` responses — providers reject the resulting history with `tool_use without matching tool_result`. The compactor now walks the head boundary forward and the tail boundary backward (`adjustHead`, `adjustTailStart`) until both slices are self-consistent wrt pending tool-call ids. When no safe cut exists, the compactor returns the original history via `CompactionResult.noOp(...)`.

### Fixed — Summary call timeout

The summary model call previously ran synchronously on the loop's virtual thread; a hung provider call hung the entire session. The default compactor now runs the call on a dedicated virtual thread under a configurable timeout (`withSummaryTimeout(Duration)`, default 60s). On timeout the worker is interrupted (best effort), the compaction returns no-op, and the watermark will re-fire next turn.

### Fixed — Summary call spend accumulates into session totals

The summary call's `Response.usage()` is now reported on `CompactionResult.usage()`. The agent loop accumulates it via a new `TurnRunner.accumulateUsageAndCost(state, usage)` helper, applying the configured `CostCalculator` against the session's model id — so `SessionLimits.maxBudgetMicroUsd` correctly gates compaction spend.

### Fixed — Pre-turn watermark check

The watermark check ran ONLY after the assistant's response was appended. If a previous turn's huge tool result already pushed history past 0.95, the next request was sent BEFORE compaction had a chance to fire. The loop now checks the watermark immediately after `state.beginTurn()` (pre-turn) AND after `turnRunner.runTurn` (post-turn). Pre-turn compaction rewrites the history the model receives.

### Added — `CompactionResult` record + `noOp(history)` factory

New record `ai.singlr.session.CompactionResult(List<Message> history, Response.Usage usage)`. `CompactionResult.noOp(history)` is the canonical no-op factory.

### Added — `SessionState.replaceHistory(List<Message>)` defensive copy

Already in 2.1.0 but now explicitly documented: defensively copies the supplied list and rejects null elements. Used by both compaction and `PreModelTurnHook.MutateInput`.

## [2.1.0] — 2026-05-18

Context compaction lands. Long-running sessions (e.g. a 30-turn SDTM mapping with lots of `csvSample` peeking and `kb_read` calls) previously crashed against the provider's hard context window — the loop appended turns, the next request overflowed, and the user saw an ungraceful `ErrorDuringExecution`. The v2.0.0 cut declared the surface (`QueryEvent.ContextWarning`, `QueryEvent.ContextEdited`, `SessionLimits.maxContextTokens` with `"soft trigger for compaction"` in Javadoc) but nothing read it. 2.1.0 wires it end-to-end.

### Added — `core.context.TokenCounter` (new public API in `helios-core`)

Pluggable estimator for conversation token fill. The agent loop calls it after every model turn to detect the watermarks. Default `TokenCounter.charBased()` is a cheap ~4-chars-per-token heuristic plus a small per-message overhead, conservative for typical English text. Provider-specific tokenizers (Anthropic's, OpenAI tiktoken, etc.) can be wired via `SessionOptions.Builder.withTokenCounter(...)` when accuracy matters more than per-turn cost.

### Added — `ai.singlr.session.ContextCompactor` SPI + `DropMiddleToolResultsCompactor`

`ContextCompactor.compact(history, state) → history`. The default `DropMiddleToolResultsCompactor.newBuilder(summaryModel).withHeadPreserved(3).withTailPreserved(20).build()` preserves the head (system prompt + opening user turn) and the tail (recent trajectory), replacing the middle with a single user-role summary produced by one call against the supplied summary model. Throwing or blank summaries return the history unchanged — compaction failure never crashes the loop. `ContextCompactor.disabled()` opts out wholesale for deployments that prefer fail-loud overflow.

### Added — Agent loop wires the two watermarks

After each model turn, the loop counts tokens, then:

- At `tokens/maxContextTokens >= 0.85`, emits `QueryEvent.ContextWarning(usagePct)` once. Sticky per session — the warning flag clears only on a successful compaction.
- At `tokens/maxContextTokens >= 0.95`, invokes the configured `ContextCompactor`. If the result is a real shrink, the loop swaps history, emits `QueryEvent.ContextEdited(removedBlocks, tokensBefore, tokensAfter)`, and clears the warning flag so a future re-climb re-fires.

A no-shrink result (identity reference or same-size list) is treated as a no-op — `ContextEdited` is not emitted and the warning flag stays set.

### Added — `PreModelTurnHook.MutateInput` wired (was previously treated as `Continue`)

For library users who'd rather write a hook than implement the full SPI: return `HookOutcome.mutate(Map.of("history", rewrittenHistory))` from a `PreModelTurnHook`. The loop swaps in the rewritten history, clears the warning flag, and emits `HookFired` with `outcomeKind=MutateInput`. Malformed payloads (missing `"history"` key, wrong type, non-`Message` elements) fall back to `Continue` rather than crashing.

### Added — `SessionState.replaceHistory(List<Message>)`

Used by both compaction and `PreModelTurnHook.MutateInput`. Defensive copy, rejects null elements. Plus `tryFireContextWarning()` / `resetContextWarningFlag()` / `contextWarningFired()` for first-wins watermark gating.

### Changed — `SessionOptions` adds two record components

`tokenCounter` and `contextCompactor` are now part of the canonical record. The Builder API is additive (`withTokenCounter(...)`, `withContextCompactor(...)`); defaults apply if you don't set them. Direct canonical-ctor callers must update — there are none in published Helios consumers since all use the Builder.

### Note on design

The Phase 6c spec suggested the default compactor would read `state.model()`; the impl binds the summary `Model` at compactor construction instead. `SessionState` does not carry a `Model`. This keeps `SessionState` decoupled from provider state and lets deployers wire a cheaper summary model (Haiku/Flash) than the conversation's primary model — a common production pattern.

## [2.0.0] — 2026-05-17

**Major release. Breaking — no v1 compatibility shim.** v2 reframes the SDK around a long-lived agent loop (`AgentSession`) rather than v1's one-shot `Agent.run(...)`. The v1 surfaces (`core.agent.Agent`, `core.workflow`, `core.memory`, `core.eval`, the `RlmHarness`/`CodeActHarness` family) are deleted. The v1 line stays buildable via the `main-v1` branch for a few months.

### Added — `helios-session` (new published artifact)

The v2 SDK surface. `AgentSession.open(SessionOptions)` returns a session that runs an agent loop on a virtual thread; `send(UserMessage)` queues steering input; `events()` is a `Flow.Publisher<QueryEvent>`; `result()` resolves to a typed terminal `ResultMessage`. Curated builder factories ship as `SessionPresets.minimal(...)` / `.readOnly(...)` / `.workspace(...)` / `.openEnded(...)`. Hook surface is seven phase-specific interfaces (`OnUserMessageHook`, `PreModelTurnHook`, `PostModelTurnHook`, `PreToolUseHook`, `PostToolUseHook`, `PreStopHook`, `OnStreamEventHook`) with five outcomes (`Continue` / `MutateInput` / `Block` / `Inject` / `Stop`). Declarative permission system (`Permission.defaultInWorkspace()` / `.lockedDown()` / `.planMode()`), file tools rooted in `WorkspaceRoot`, `FileSystemMemoryBackend`, fault-tolerant tool dispatch, currency in integer micro-USD.

### Added — `helios-runtime` (new published artifact)

Helidon SE HTTP/SSE surface for session: `POST /sessions`, `POST /sessions/{id}/messages`, `POST /sessions/{id}/interrupt`, `GET /sessions/{id}/events` (SSE), `GET /sessions/{id}/result?timeout=<s>` (long-poll), `DELETE /sessions/{id}`. `SessionRegistry` tracks live sessions with eviction policies.

### Added — `CodeActPreset` in `helios-repl`

CodeAct becomes a frozen, restricted preset of the agent loop rather than a separate harness. `CodeActPreset.typed(I, O, input)` for typed structured-compute. `CodeActPreset.withSubLm(I, O, input, subModel)` for RLM-style fan-out via in-sandbox `predict()` / `submit()` host functions. Both apply `Permission.lockedDown()` + `MemoryBackend.disabled()` so the JShell sandbox is the world.

### Added — Gemini Interactions API May 2026 schema migration

Request body migrated to the `step_list` shape (`Step` items with `type` discriminator replacing `Turn` items with `role`). Captures the nested `arguments_delta` deltas the live server ships inside `step.delta.delta` rather than the doc-promised top-level field. Tested against gemini-3-flash-preview and gemini-3.1-pro-preview.

### Added — Example modules

`examples/session-demo` (workspace + file tools + memory + attachments), `examples/codeact-demo` (CodeActPreset.typed against Gemini Pro), `examples/rlm-demo` (CodeActPreset.withSubLm with Pro + Flash sub-LM). All gated on `GEMINI_API_KEY` for live smoke; deploy-skipped so they stay off Maven Central.

### Removed

- `core.agent.Agent`, `core.agent.AgentConfig`, `core.workflow`, `core.memory`, `core.eval`
- `RlmHarness`, `CodeActHarness`, prompt-frozen harness families
- `examples/autoresearch-prompt`, `examples/autoresearch-code`, `examples/rlm-demo-jpms`, `examples/gepa-prompt` (v1 example surface)
- `PgMemoryStore`, archive/coreblock persistence

### Migration

v1 → v2 is a rewrite, not a port. The `main-v1` branch carries the v1 codebase if you need it. New code should pick `SessionPresets.openEnded(...)` (read/write workspace + Execute) for general agents, `CodeActPreset.typed(...)` for structured-compute APIs, `CodeActPreset.withSubLm(...)` for RLM fan-out.

## [1.5.4] — 2026-05-14

No breaking changes. Two real bug fixes plus one prompt-shape improvement, all surfaced by running the workload-fixtures suite (Spec 06) across Gemini, Anthropic, and OpenAI for the first time. The suite caught two issues that would otherwise have hit individual library users.

### Fixed — OpenAI structured output rejects schemas with open Maps (HTTP 400)

`OpenAIModel` previously hardcoded `strict: true` on every structured output request. OpenAI's strict-mode validator rejects any schema containing an open-keyed object (i.e. a `Map<String, X>` with an unbounded key set) because strict mode requires every `object` to declare `additionalProperties: false` AND list every property in `required`. Open Maps violate both.

Symptom for library users: any structured output type containing a `Map<String, X>` field (e.g. `record Out(Map<String, List<String>> targetToSources)`) failed immediately with:

```
API error (status 400): "Invalid schema for response_format 'output':
In context=(), 'required' is required to be supplied and to be an array
including every key in properties."
```

Fix: `OpenAIModel.hasOpenMapShape(schema)` recursively detects open-keyed objects. When present, the schema ships with `strict: false` — preserving structured output via OpenAI's `json_schema` mode without engaging the strict validator. Flat-record schemas (no Maps) still use `strict: true` as before. New `TextFormatConfig.jsonSchema(name, schema, strict)` overload exposes the toggle.

### Fixed — Anthropic structured output fails when the model wraps JSON in prose

Claude Sonnet 4.6 has a habit of returning JSON answers prefixed with a one-sentence explanation:

```
The map is built correctly. Here is the final answer:

{"targetToSources":{"AESEV":["severity"], ...}}
```

The JSON is structurally correct — but `StructuredContentParser` could only strip markdown fences (` ```json `), not arbitrary prose before the object. Library users saw `Failed to parse structured output: ...` with the model's correct answer right there in the error message.

Fix: `StructuredContentParser.extractFirstJsonObject(content)` walks the content tracking brace depth (and JSON string-literal escapes, so braces inside string values don't terminate the scan early) and returns the first balanced `{...}` substring. Added as a third retry pass after markdown-stripping, so the parser handles `pure JSON`, ` ```json fenced JSON ``` `, and `prose: {JSON}` shapes uniformly.

### Improved — CodeAct system prompt: positive framing, no `submit()` mention

`CodeActSystemPrompt.build` previously contained the line "...there is no submit() call." Mentioning a function only to negate it primed models to reach for it anyway, then burn iterations on JShell parse errors (`illegal start of expression` on `submit({"k": v})` map-literal syntax) before falling back to emitting the structured answer as the assistant message.

The prompt now affirmatively describes the runtime (JDK 25 JShell, JDK standard library + listed host functions, no third-party libs) instead of describing what isn't there. No mention of `submit()` anywhere. The "How to finish" step explicitly tells the model that emitting the structured JSON as its assistant message — without any tool call — is the deliverable.

### Added — `SuiteRunner` cross-provider support

`examples/workload-fixtures` now supports Anthropic and OpenAI alongside Gemini. New CLI flags: `--anthropic-model <id>` and `--openai-model <id>` (defaults: `claude-sonnet-4-6`, `gpt-5.4`). The `--providers` flag now accepts any comma-separated subset of `gemini,anthropic,openai`. The suite is what surfaced both bugs above; running it across providers is now a one-liner.

### Adjusted — `NumericStatsFixture` maxIterations 5 → 6

Matched the codeact-demo integration test cap. With 5 the simple "sum a list of doubles" task occasionally failed on the last iteration after the model spent extra turns recovering from `submit()` guesses; 6 gives one iteration of headroom. With the CodeAct prompt improvement above this hits 100% across all three providers regardless.

## [1.5.3] — 2026-05-14

No breaking changes. Unblocks Azure OpenAI / OpenAI-compatible proxies / Vertex / Bedrock by letting library users override the provider's base URL and HTTP headers via `ModelConfig`. Symmetric across all three providers (OpenAI, Anthropic, Gemini).

### Added — `ModelConfig.withBaseUrl(String)` and `ModelConfig.withHeader(...)` / `withHeaders(Map)`

`ModelConfig` gains two new fields and three new builders:

- `withBaseUrl(String)` — overrides the provider's hardcoded API endpoint. `null` (default) keeps the canonical URL: `https://api.openai.com/v1/responses`, `https://api.anthropic.com/v1/messages`, `https://generativelanguage.googleapis.com/v1beta`.
- `withHeader(String name, String value)` — adds one extra HTTP header to every request.
- `withHeaders(Map<String,String>)` — bulk variant; `null` clears.

Header names match **case-insensitively** against the provider's built-in headers (`Authorization`, `x-api-key`, `x-goog-api-key`). When a user header's name matches a built-in, the user value replaces the built-in entirely — that's what makes Azure work, where the auth header is `api-key` rather than `Authorization: Bearer`.

Two new public helpers on `ModelConfig` expose the merge logic for downstream provider implementations: `effectiveBaseUrl(String providerDefault)` and `effectiveHeaders(Map<String,String> defaults)`.

### Relaxed — `apiKey` is optional when `baseUrl` is set

Provider constructors (`OpenAIModel`, `AnthropicModel`, `GeminiModel`) previously rejected any `ModelConfig` whose `apiKey` was null/blank. They now accept blank-or-null `apiKey` as long as `baseUrl` is configured. In that mode the provider's default auth header (`Authorization: Bearer ...`, `x-api-key: ...`, `x-goog-api-key: ...`) is **omitted entirely** — the user is expected to supply their own via `withHeader(...)`. When `baseUrl` is null the original check still fires.

This is what makes the Azure path clean: a deployment URL + `api-key` header, with no leftover `Authorization` header in the wire request.

### Azure OpenAI usage

```java
var config = ModelConfig.newBuilder()
    .withBaseUrl("https://my-resource.openai.azure.com/openai/deployments/my-deployment/responses?api-version=2024-08-01-preview")
    .withHeader("api-key", System.getenv("AZURE_OPENAI_KEY"))
    .build();
var model = new OpenAIProvider().create(OpenAIModelId.GPT_4O.id(), config);
```

Same pattern works for AWS Bedrock with the Anthropic provider, Vertex AI with the Gemini provider, or any OpenAI-/Anthropic-/Gemini-compatible reverse proxy (LiteLLM, vLLM, Ollama).

### Why this shape

Reported by a library user — `OpenAIModel` hardcoded the OpenAI endpoint and `ModelConfig` had no escape hatch. The narrowest fix would have been an OpenAI-only `withBaseUrl`. We applied it symmetrically across all three providers because Anthropic-via-Bedrock and Gemini-via-Vertex are real cases and asymmetric now is asymmetric forever. The deliberately-rejected alternative — a dedicated `OpenAIAzureProvider` with deployment-name/api-version awareness — felt premature for one report; `withBaseUrl` + `withHeaders` is the generic seam that subsumes Azure plus a long tail of compatible endpoints.

## [1.5.2] — 2026-05-13

No breaking changes. Bundles everything from 1.5.1 (autoresearch optimizer primitives, GepaPromptOptimizer reference example, OpenAI 5.x model value corrections) plus one input-binding fix that motivated the dot-release. **Library users on 1.5.0 should jump directly to 1.5.2** — v1.5.1 was tagged but not deployed to Maven Central, so the public upgrade path is 1.5.0 → 1.5.2.

### Fixed — Hybrid input binding for user-typed collections (Spec 05)

`InputBindings` no longer falls back to raw `Object` for `List<UserType>` / `Map<String, UserType>` / `Set<UserType>` and their nested forms. The container shape is preserved; only the type arguments erase to `java.lang.Object`. The model gets `.size()`, `.get()`, iteration without a manual cast.

```
List<UserType>            → List<Object>
Map<String, UserType>     → Map<String, Object>
Set<UserType>             → Set<Object>
Map<String, List<UserType>> → Map<String, List<Object>>
UserType[]                → Object[]
UserType (top-level)      → raw Object (unchanged — rare in practice)
List<Integer> etc.        → unchanged (still typed)
```

Concrete user impact: trajectories on user-typed inputs (e.g. SDTM mapping) save 2 iterations + ~10K tokens that were previously spent recovering from `cannot find symbol: method size()` errors when the model tried the natural `.size()` / `.get(0)` on what looked like a `List`.

Design rationale and rejected alternatives (full drop to `Map<String,Object>`, mini-OSGi user-type exposure) captured in `docs/specs/05-input-binding-design.md` (in-repo, gitignored).

### Carried forward from 1.5.1 (not deployed to Central)

For reference — these landed on `main` under the v1.5.1 tag but were never published. They ship to Central as part of 1.5.2:

- **Spec 03** autoresearch optimizer primitives (`ParetoFrontier`, `ReflectiveMutator`, `LlmReflectiveMutator`, `FeedbackMetric`, `TraceFeedback`, `TraceSampler`, `ReflectionFailedException`). `Evaluator.withFeedbackMetric(...)`. New `EvalResult.feedback()` / `perExampleScores()` helpers. New `ExampleResult.feedback` field (backwards-compatible canonical constructor preserved).
- **Spec 04** `examples/gepa-prompt` reference optimizer with `AutoBudget`, `CandidateLineage`, `GepaResult.applyTo(AgentConfig)`. Live-Gemini integration test lifts a 3-class sentiment classifier from ~33% baseline to ≥70% accuracy via `AutoBudget.LIGHT`.
- **OpenAI 5.x fixes**. Every 5.x entry had wrong `maxOutputTokens` (whole family is 128K, was 32K/16K); 5.4 family also had wrong context window:

| Model | context (was → is) | max output (was → is) |
|---|---|---|
| `gpt-5.5` | 1,050,000 ✓ | 32,000 → **128,000** |
| `gpt-5.4` | 1,000,000 → **1,050,000** | 32,000 → **128,000** |
| `gpt-5.4-mini` | 1,000,000 → **400,000** | 32,000 → **128,000** |
| `gpt-5.4-nano` | 1,000,000 → **400,000** | 16,000 → **128,000** |

## [1.5.1] — 2026-05-13 (tagged, not deployed)

Tagged on `main` and pushed to `release/1.5.1` but **not deployed to Maven Central**. The 1.5.2 release supersedes it; the content below was the planned scope before the input-binding fix bumped us to 1.5.2.

### Added — Autoresearch optimization primitives

No breaking changes. Additive autoresearch primitives + worked optimizer example + OpenAI 5.x model value corrections.

### Added — Autoresearch optimization primitives

New types in `ai.singlr.core.eval` for GEPA-shaped optimizers:

- **`ParetoFrontier<C>`** — tracks candidates by per-instance validation scores and maintains the Pareto-non-dominated set. Coverage-weighted `sampleByCoverage(Random)`, `bestSingle()`, `aggregateScore(C)`, `envelope()`, `snapshot()` / `restore()`. Thread-safe via `ReentrantReadWriteLock`. NaN scores rejected at the boundary.
- **`ReflectiveMutator<C>`** — functional interface: `propose(parent, traces) → new candidate`. `LlmReflectiveMutator` is the reference implementation for `C = String` prompts, decomposed across `ReflectionPromptTemplate` (prompt assembly), `ReflectionResponseParser` (post-process + acceptance test), and `TraceSampler` (which traces the reflection LM sees). Schema-constrained retry on malformed responses; `ReflectionFailedException` when both attempts fail.
- **`FeedbackMetric<E, A>`** — sibling to `Metric` returning `{score, feedback}` for feedback-aware optimizers. `.asScalar()` adapts cleanly back to `Metric` when only a number is needed.
- **`TraceFeedback`** record — one `(input, expected, actual, score, feedback, trace)` tuple, the natural input to `ReflectiveMutator.propose`.

`Evaluator.Builder.withFeedbackMetric(FeedbackMetric)` is mutually exclusive with `withMetric(Metric)`. `ExampleResult` gained a `feedback` field (backwards-compatible canonical constructor preserved). `EvalResult.feedback()` re-shapes per-example results as `List<TraceFeedback>` for `ReflectiveMutator.propose` input; `EvalResult.perExampleScores()` returns the natural `double[]` shape for `ParetoFrontier.add`.

### Added — `examples/gepa-prompt` reference optimizer

New (unpublished) example module composes the primitives above into a working GEPA-shaped prompt optimizer:

- `GepaPromptOptimizer<I, O>` + Builder (~450 LoC driver)
- `AutoBudget.LIGHT / MEDIUM / HEAVY` budget presets (6 / 12 / 24 iterations; linear scaling)
- `CandidateLineage` parent → child graph
- `GepaResult` with `applyTo(AgentConfig)` helper

Live-Gemini integration test lifts a deliberately-weak 3-class sentiment classifier from ~33% baseline to ≥70% accuracy via `AutoBudget.LIGHT` (~6 iterations). The example is the proof-of-design for the primitives — if it were awkward, the primitives would be wrong.

### Fixed — OpenAI 5.x model context windows and max output tokens

Verified against `developers.openai.com/api/docs/models/gpt-5.4` (and corresponding mini/nano/5.5 pages). Every 5.x entry had the wrong `maxOutputTokens` (the whole family is 128K, not 32K/16K). The 5.4 family also had the wrong context window.

| Model | context (was → is) | max output (was → is) |
|---|---|---|
| `gpt-5.5` | 1,050,000 ✓ | 32,000 → **128,000** |
| `gpt-5.4` | 1,000,000 → **1,050,000** | 32,000 → **128,000** |
| `gpt-5.4-mini` | 1,000,000 → **400,000** | 32,000 → **128,000** |
| `gpt-5.4-nano` | 1,000,000 → **400,000** | 16,000 → **128,000** |

Added 4 new `OpenAIModelIdTest` assertions for the previously-uncovered fields (the coverage gap is why the wrong values went unnoticed). GPT-4.1 family and GPT-4o not re-audited in this release — file separately if you want a broader audit.

## [1.5.0] — 2026-05-13

### Breaking — Unified event stream replaces three legacy SPIs

The observability surface collapses from three separate listener interfaces into a single sealed event stream. This is the load-bearing change in 1.5.0 and every library user has to act on it.

**Removed (no compat shim):**

- `ai.singlr.core.trace.TraceListener`
- `ai.singlr.core.trace.SpanListener`
- `ai.singlr.core.trace.SpanStart`
- `ai.singlr.core.trace.CollectingTraceListener` (use `ai.singlr.core.events.CollectingEventSink` instead)
- `ai.singlr.core.memory.MemoryListener`
- `ai.singlr.core.memory.MemoryEvent`
- `AgentConfig.Builder.withTraceListener(...)` / `withSpanListener(...)` / `withMemoryListener(...)` and their list variants
- `Memory.addListener(...)` / `removeListener(...)`

**Added:**

- `ai.singlr.core.events.HeliosEvent` — sealed interface with 26 variants covering the agent loop, iteration boundaries, assistant text/thinking, tool calls, memory reads/writes, span open/close, sub-agent delegation, compaction, and optimizer events.
- `ai.singlr.core.events.EventSink` — functional interface, the single observability seam.
- `ai.singlr.core.events.CollectingEventSink` — thread-safe `List<HeliosEvent>` accumulator for tests.
- `ai.singlr.core.events.JsonlEventSink` — append-only JSON-Lines sink for live UIs and post-hoc audit.
- `ai.singlr.core.events.EventSinkPolicy` — backpressure / overflow policy.
- `AgentConfig.Builder.withEventSink(...)` / `withEventSinks(...)`.
- `Memory.addEventSink(...)` — memory-write events flow through the same stream.

**Migration path:** wrap your old listener logic in an `EventSink` lambda and pattern-match on the sealed `HeliosEvent` hierarchy. `TraceListener#onTraceClose` corresponds to `HeliosEvent.RunCompleted` (carrying the complete `Trace`). `SpanListener#onSpanStart/onSpanEnd` correspond to `HeliosEvent.SpanOpened` / `SpanClosed`. `MemoryListener#onMemoryWrite` corresponds to `HeliosEvent.MemoryWritten`.

### Added — Provider thinking-delta streaming

Anthropic, Gemini, and OpenAI now surface model reasoning through `StreamEvent.ThinkingDelta` and `StreamEvent.ThinkingComplete(fullText, signature)` during `runStream(...)`. Verified end-to-end against live APIs for all three providers.

### Added — `CodeActHarness` (Spec 02)

New one-line typed entrypoint at `ai.singlr.repl.CodeActHarness` for CodeAct flows (REPL without sub-LM). Composes the same substrate as `RlmHarness` (`ReplSession` + `CodeExecutionTool` + `InputBindings`) but with no `predict()`, no `submit()`, and no extract-fallback — the model writes Java in a sandboxed JShell REPL across turns and returns its structured answer as the final assistant message, captured via the Agent's `OutputSchema` path. `CodeActResult.Status` is `SUCCEEDED` or `FAILED`.

### Added — REPL substrate seams

- `ReplConfig.Builder.withAutoRegisterSubmit(boolean)` — controls whether `ReplSession.create(...)` auto-installs the `submit` host function. Defaults to `true` (RlmHarness-compatible). `CodeActHarness` flips it to `false`.
- `ai.singlr.repl.InputBindings` promoted from package-private to public so both harnesses share the typed-input JShell-binding utility.
- `ai.singlr.repl.PromptRendering` (package-private) — shared rendering helpers between `RlmSystemPrompt` and `CodeActSystemPrompt`.

### Fixed — Gemini v2 wire-format drift on thought signatures

The May 2026 Gemini Interactions API (`Api-Revision: 2026-05-20`) delivers thought signatures as a `step.delta` whose `delta.type == "thought_signature"`, not on `step.start` as the migration guide showed. `GeminiModel.StreamingIterator` now recognises that shape; the legacy `step.start`-carries-signature path is also still covered for fixture compat. Symptom before fix: `gemini.thoughtSignatures` missing from response metadata in thinking mode.

### Fixed — Gemini streaming function-call arguments

Added `ArgumentsDeserializer` for the JSON-encoded-string shape of `arguments` that the streaming Gemini Interactions API ships in some `interaction.step_*` events. Normalises both shapes (Map or string) to an internal `Map<String,Object>`.

### Internal

- `EventEmitter` extracted from `Agent` as a top-level package-private helper — reduces Agent file size and isolates the per-run fan-out logic.
- `Workflow` non-durable runs use a synthetic `runId` via `Ids.newId()` so they participate in the unified event stream.
