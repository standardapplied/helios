# Changelog

All notable changes to Helios are documented here. Versions follow [SemVer](https://semver.org/).

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

The v2 SDK surface. `AgentSession.create(SessionOptions)` returns a session that runs an agent loop on a virtual thread; `send(UserMessage)` queues steering input; `events()` is a `Flow.Publisher<QueryEvent>`; `result()` resolves to a typed terminal `ResultMessage`. Curated builder factories ship as `SessionPresets.minimal(...)` / `.readOnly(...)` / `.workspace(...)` / `.openEnded(...)`. Hook surface is seven phase-specific interfaces (`OnUserMessageHook`, `PreModelTurnHook`, `PostModelTurnHook`, `PreToolUseHook`, `PostToolUseHook`, `PreStopHook`, `OnStreamEventHook`) with five outcomes (`Continue` / `MutateInput` / `Block` / `Inject` / `Stop`). Declarative permission system (`Permission.defaultInWorkspace()` / `.lockedDown()` / `.planMode()`), file tools rooted in `WorkspaceRoot`, `FileSystemMemoryBackend`, fault-tolerant tool dispatch, currency in integer micro-USD.

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
