# Singular Agentic Framework

Production-grade agentic framework for Java. Simple, explicit, no magic.

## Project Info

- **Java**: 25 | **Build**: Maven | **License**: MIT
- **Group ID**: `ai.singlr`

## Philosophy

1. **No magic** - Explicit wiring, no annotation-driven DI
2. **Idiomatic Java** - Records, sealed types, pattern matching
3. **Production from day 1** - Fault tolerance, observability, 100% test coverage

## Libraries

- **Jackson 3.x** for JSON ([migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md))
- **JUnit 6.x** for testing ([docs](https://docs.junit.org/6.0.2/overview.html))
- **Helidon SE 4.4.x** for HTTP / persistence modules ([DbClient docs](https://helidon.io/docs/v4/se/dbclient))

## Coding Conventions

- **JDK records and classes, immutability first.** Records for all value types; mutable state only in clearly-named stateful classes. Records hold `List`/`Map`/`Set` from `.of()` factories, never exposed mutable collections.
- Records with static Builder class, `with` prefix for builder methods
- No `get` prefix for accessors
- 2-space indent, 4-space continuation
- No inline comments; use Javadocs for public API
- Use `var`, `List.of()`, `Map.of()`
- DO NOT use wildcard imports, ever!
- Copyright header: `/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */`
- **SOLID and DRY are non-negotiable.** Single Responsibility (one paragraph of Javadoc per class), Open/Closed (extension via interfaces, not modification), Liskov (enforced by sealed hierarchies), Interface Segregation (no omni-interfaces with optional methods), Dependency Inversion (external collaborators are interfaces). Don't copy-paste — extract a helper.
- **No God classes.** Hard rule: no class exceeds **1000 lines, excluding Javadoc comments and imports**. Hitting the budget is the signal to extract, not to ask for an exception.
- **Currency is integer micro-USD (long), never BigDecimal on the hot path.** Stripe-style fixed-precision. See `CostEstimate` + `CostCalculator`.

**CRITICAL** Talk to me before making design decisions

## Architecture

```
helios/
├── core/                           # Zero deps - Model + tool + common + fault + schema + trace + runtime + knowledge + prompt + embedding interfaces. CostEstimate + CostCalculator.
├── session/                        # v2 SDK - AgentSession, SessionPresets, hooks, permissions, file tools, memory backend, agent loop
├── runtime/                        # Helidon HTTP/SSE surface for session — POST /sessions, SSE /events, long-poll /result
├── gemini/                         # Gemini Interactions API + Jackson 3.x
├── anthropic/                      # Claude Messages API + Jackson 3.x
├── openai/                         # OpenAI Responses API + Jackson 3.x
├── repl/                           # Sandboxed JShell substrate (JvmSandbox, ReplSession, CodeExecutionTool, InputBindings, HostFunction infrastructure)
├── onnx/                           # Local embeddings via ONNX Runtime
├── persistence/                    # PostgreSQL persistence — PgTraceStore + PgDurability via Helidon DbClient
└── examples/
    ├── session-demo/               # Reference: full session run against Gemini with a real workspace
    ├── codeact-demo/               # AgentSession + CodeActPreset — Java-as-action loop against Gemini
    └── rlm-demo/                   # AgentSession + CodeActPreset — nested "leader+worker" pattern on v2 primitives
```

The v1 surface (`core.agent.Agent`, `core.workflow`, `core.memory`, `core.eval`, the
`RlmHarness` / `CodeActHarness` family) was deleted in the v2 cut per spec
§3.5. The v2 paradigm is a long-lived `AgentSession` that runs an agent loop on a virtual
thread; v1's one-shot `Agent.run(...)` shape no longer exists.

### JPMS Modules

Core exports public API; providers register via ServiceLoader SPI.

## v2 Session SDK (`helios-session` + `helios-runtime`)

| Pattern | Description |
|---------|-------------|
| **AgentSession** | Sealed-interface entrypoint in `helios-session`. `AgentSession.create(SessionOptions)` returns a session that runs an agent loop on a virtual thread the first time you `send`. `send(UserMessage)` queues a message (steering queue, drained per-iteration); `events()` returns a `Flow.Publisher<QueryEvent>` for streaming subscribers; `result()` is a `CompletableFuture<ResultMessage>` for blocking await; `runBlocking(msg)` and `runBlocking(msg, OutputSchema)` are sync convenience; `interrupt(reason)` queues a synthetic mid-run user message; `answer(qid, response)` resolves a pending `AskUserQuestion`; `close()` is `AutoCloseable`. Use `try-with-resources`; `close()` cancels the loop, settles the future, and shuts down the per-session publisher executor with a 5s grace period |
| **SessionPresets** | Three curated `SessionOptions.Builder` factories: `minimal(model)` (just the model, defaults everywhere), `readOnly(model, path\|root)` (Read/Glob/Grep/LS rooted at workspace + `Permission.planMode()`), `workspace(model, path\|root)` (same read tools + `MemoryWrite` against `FileSystemMemoryBackend` + `Permission.defaultInWorkspace()`). Each returns the Builder so callers chain `.withCostCalculator(...)` / `.withHook(...)` / `.withLimits(...)` before `.build()`. `openEnded(...)` is intentionally absent until Phase 5 ships the Execute tool. Two-level subrecord extraction (`ToolingOptions`, `ObservabilityOptions`) was explicitly rejected — it would trade one ergonomic pain for a worse one. `SessionOptions.Builder` is declared canonical entrypoint; the public canonical record constructor stays only for serialization |
| **QueryEvent (sealed, 15 subtypes)** | Streamed lifecycle event: `AssistantText`, `AssistantThinking`, `UserMessageReceived`, `MessageBlocked`, `ContextWarning`, `ContextEdited`, `ToolUse`, `ToolResult`, `ToolBlocked`, `ToolMutated`, `HookFired`, `QuestionAsked`, `TurnEnded`, `LoopEnded`, `Error`. Adding a subtype is a deliberate breaking change for `switch` consumers (compiler flags missing branches). `MessageBlocked` fires when an `OnUserMessageHook.Block` drops a user message so UIs/audit can surface the drop — without it the message vanishes invisibly |
| **Hooks + HookOutcome** | Nine phase-specific non-sealed sub-interfaces of `Hook` (`OnUserMessageHook`, `PreModelTurnHook`, `PostModelTurnHook`, `PreToolUseHook`, `PostToolUseHook`, `PreStopHook`, `PreCompactHook`, `PostCompactHook`, `OnStreamEventHook`) returning a `HookOutcome` (`Continue` / `MutateArgs` / `MutateHistory` / `MutateText` / `MutateResult` / `Block` / `Inject` / `Stop`). Each mutation variant is typed to its phase: `MutateArgs(Map)` at PreToolUse, `MutateHistory(List<Message>)` at PreModelTurn/PreCompact, `MutateText(String)` at OnUserMessage, `MutateResult(String)` at PostToolUse. Returning a mutation variant at the wrong phase is harmless (treated as Continue). `HookRegistry` catches `RuntimeException` from hooks (logs WARNING, treats as Continue), but `Error` subtypes escape — see the Loop crash semantics row |
| **Permission system** | `Permission(mode, allow, ask, deny)` declarative policy gated as a `PreToolUseHook` at priority 50 by `DefaultPermissionEvaluator`. Evaluation order per spec §12.4: deny → BYPASS shortcut → allow → ask → default-by-category. ASK routes through the session's `QuestionGateway`; with no gateway wired, ASK falls back to Block. `DefaultPermissionEvaluator` collapsed to 1 simple constructor + `newBuilder(perm, tools).withPriority(int).withQuestionGateway(gw).build()`. Curated policies on `Permission`: `defaultInWorkspace()`, `planMode()`, `empty(mode)` |
| **CostEstimate + CostCalculator (long microUSD)** | Currency is integer micro-USD (Stripe-style fixed-precision), never `BigDecimal` on the hot path. `CostEstimate(long microUsd)` in `core.common`; `CostCalculator.cost(modelId, Usage) -> CostEstimate` is the per-turn lookup. `CostCalculator.ZERO` is the opt-in default (no cost tracking); `CostCalculator.staticTable(Map<String, Pricing>)` builds from a deployer-supplied rate card. `Pricing(long inputMicroUsdPerMillion, long outputMicroUsdPerMillion)` uses `Math.multiplyExact` / `addExact` for overflow safety. `SessionLimits.maxBudgetMicroUsd` is `OptionalLong`; when set, `StopClassifier` produces `ResultMessage.ErrorMaxBudgetUsd(microUsdSpent)` when cost exceeds the cap. Long max ≈ $9.2T headroom |
| **SessionLimits (record + Builder)** | Five per-session ceilings, each mapping to its own terminal: `maxTurns` → `ErrorMaxTurns`, `maxBudgetMicroUsd` → `ErrorMaxBudgetUsd`, `maxWallClock` → `ErrorMaxWallClock`, `toolTimeoutDefault` → per-tool dispatch timeout, `maxContextTokens` → compaction trigger. `SessionLimits.newBuilder()` exposes `.withMaxTurns(int)` / `.withMaxBudgetMicroUsd(long)` / `.withoutMaxBudget()` / `.withMaxWallClock(Duration)` / `.withToolTimeoutDefault(Duration)` / `.withMaxContextTokens(long)`. Production defaults: 100 turns, no budget, 1 h wall-clock, 2 min tool timeout, 180_000 context tokens |
| **Loop crash semantics** | `AgentLoop.run` catches `Exception` (not `Throwable`) and produces a `Refusal`/`ErrorDuringExecution` terminal; `Error` subtypes escape so OOM / StackOverflow / LinkageError kill the host thread cleanly. `AgentSessionImpl.runLoop` wraps `loop.run` in `catch (Throwable)` that settles `resultFuture` exceptionally before re-throwing — without that, `result().join()` would hang forever on any escaping Error. See defense-in-depth test `errorEscapingAgentLoopSettlesFutureExceptionallyInsteadOfHanging`. Hooks throwing AssertionError are the test-fixture path that exercises this |
| **SessionRegistry (helios-runtime)** | In-memory registry of live sessions backing the HTTP routes. `SessionRegistry.newBuilder().withFactory(...).withClock(...).withMaxSessions(int).build()`; `inMemory()` / `withFactory(...)` are convenience factories. Sessions stay registered after terminal so late SSE subscribers see `LoopEnded`. Eviction: `purgeTerminalOlderThan(Duration)` sweeps terminal sessions by termination instant (timestamp captured via `session.result().whenComplete`); `withMaxSessions(int)` evicts oldest terminal on `create()` overflow, throws `IllegalStateException` if none available — live sessions never evicted |
| **AgentHttpService (helios-runtime)** | `Helidon HttpService` exposing six routes per spec §15.1: `POST /sessions`, `POST /sessions/{id}/messages`, `POST /sessions/{id}/interrupt`, `GET /sessions/{id}/events` (SSE), `GET /sessions/{id}/result?timeout=<s>` (long-poll, default 60 s, clamped to `[0, 300]`), `DELETE /sessions/{id}`. SSE disconnect detection uses Helidon's typed `CloseConnectionException` (not exception-message strings). Result long-poll wraps the typed terminal as `{"type": "<SubtypeName>", "result": <record-fields>}` so consumers get the discriminator without coupling session to Jackson annotations |
| **Coverage gate** | `helios-session` and `helios-runtime` enforce JaCoCo `INSTRUCTION ≥ 95% / BRANCH ≥ 90%` via per-pom `coverage-gate` check-execution bound to the `verify` phase. CI runs `mvn -B clean verify javadoc:jar`. Provider modules (gemini/anthropic/openai/persistence/onnx/repl) are deliberately ungated — their tests require API keys that CI doesn't have, so a global gate would false-positive |

## Cross-cutting framework primitives (`helios-core`)

| Pattern | Description |
|---------|-------------|
| **Result<T>** | Sealed interface: Success/Failure with pattern matching |
| **Fault Tolerance** | Zero-deps: Backoff, RetryPolicy, CircuitBreaker, FaultTolerance. `FaultTolerance.withoutRetry()` returns a sibling envelope with retry stripped (CB + timeout retained) — used by the session loop's `ToolDispatch` to dispatch non-idempotent tools through a no-retry path so a side-effecting call never replays |
| **Durable Runs** | `Durability` record bundles `RunStore` + `ToolCallJournal` + `UnsafeResumePolicy` + idempotency overrides; `Durability.newBuilder()` for custom config. **Operations:** `RunStore.purgeOlderThan(Duration)` cascading retention; `DurableResumeScanner.builder(durability).register(...).build().scan()` resumes stale runs (use with `io.helidon.scheduling`). Postgres impls in `helios-persistence`: `PgRunStore` (`helios_agent_runs`) and `PgToolCallJournal` (`helios_tool_calls`); InMemory impls in `core/runtime`. Schema additive-friendly for v2 distributed columns (`worker_id`, `lease_until`). v2 session integration lands alongside the first durable AgentSession example |
| **Secret Redaction** | `core.common.SecretRegistry` is a thread-safe registry of secret values; `Redactor` (built via `registry.redactor()`) is an immutable Aho-Corasick byte-level scrubber that replaces every contiguous occurrence of a registered secret with `<redacted:NAME>`. Operates on raw bytes BEFORE UTF-8 decode so encoding mangling cannot bypass it. Validation: secrets must be ≥8 chars and pure ASCII. Overlap policy: leftmost-longest. Same byte value under two names attributes to the first registered. Zero deps |
| **Provenance (Basis-style structured output)** | `core.common.Confidence` (LOW/MEDIUM/HIGH ordinal), `core.common.Source` (title?, url, excerpts), `core.common.FieldProvenance` (field, sources, reasoning, confidence), `core.common.Provenanced<T>` ({output, provenance:[...]} sidecar pattern). `OutputSchema.provenancedOf(MyOutput.class)` returns an `OutputSchema<Provenanced<MyOutput>>` whose JSON schema asks the model for `{output, provenance}`. `ProvenanceValidator.DEFAULT` rejects `MEDIUM`/`HIGH` entries with no sources — the calibration mechanism that prevents the model from rubber-stamping HIGH on every field. Custom validators via `OutputSchema.provenancedOf(MyOutput.class, validator)`; `ProvenanceValidator.excerptLengthCap(int)` and `andThen(...)` compose. Mirrors parallel.ai's Basis framework with the family renamed to "provenance" |
| **CommandGrant (host-owned CLI)** | `core.tool.CommandGrant.builder("gh").withSecretRegistry(reg).withEnv("GH_TOKEN", t).build().toTool()` produces a Tool that lets the model invoke a single CLI binary under tight controls. Hardening, all on by default: binary path pinned at build time (no PATH-shadow surprises), argv-only never shell, `ProcessBuilder.environment().clear()` then injects only granted env (no JVM env leak), argv pre-scan refuses any registered secret value (forces env-only secret transport), stdin = closed, per-call temp cwd by default, stdout+stderr capped + redacted, descendants killed on timeout via `ProcessHandle.descendants()`, stderr hidden from model unless `withStderrToModel(true)`. Concurrency limited per grant (default 4, immediate refuse on overflow). `InvocationResult.redactionCounts()` exposes per-secret hit counts for telemetry |
| **Workspace file tools (`Read` / `Grep` / `Glob`)** | One v2 file-tool family in `session.files`. `ReadTool.binding(workspace, tracker)` + `ReadTool.binding(workspace, tracker, Redactor)`, `GrepTool.binding(workspace)` + `GrepTool.binding(workspace, Redactor)`, `GlobTool.binding(workspace)` (no Redactor — output is paths only). Path-jail via `WorkspaceRoot`: lexical normalize + `startsWith(root)` refuses `..`/absolute, then `toRealPath` refuses symlink escapes; symlinks during walk are skipped; hidden directories pruned. Per-file size caps, per-line byte caps, per-output byte caps, match/result count caps all enforced. Read's text-body output and Grep's match content flow through the optional `Redactor` (path prefixes left alone — they are structural, not secret). Wire by passing `registry.redactor()` from your session-level `SecretRegistry` so a token written by `CommandGrant("gh")` stays redacted when the agent later reads the file. **Curated-corpus use case:** point a `WorkspaceRoot` at any directory (not just the session's working dir) and the same tools cover what the removed `FilesystemKnowledge` / `kb_*` family did pre-2.4.0. Read-only enforced via `Permission.planMode()` or a curated `Permission` policy — there is no Write tool to disable |
| **Grounding Citations in Traces** | When a model returns `Response.citations()`, the `model.chat` span records `groundingCitationCount` and `groundingSources` (deduplicated, `www.`-stripped, comma-separated domains). Cheap — no flag required |
| **Structured Output Resilience** | Three layers, each independently tested. **(1)** `StructuredContentParser.parse()` reads to `Map` first, runs `core.schema.SchemaValidator`, and surfaces all parse failures — both schema-validation mismatches and JSON-syntax errors — as `StructuredOutputParseException` (e.g. `provenance[3].sources[0].title is required but missing` for schema errors, or `JSON syntax error: ...` for malformed JSON). **(2)** Provider `chat(messages, tools, outputSchema)` skips parse entirely when `response.toolCalls()` is non-empty — tool-calling turns are intermediate, structured output is the deliverable of a later text-only turn. Anthropic's `buildRequest` also emits a turn-aware schema instruction when tools are present ("you may call tools; when ready to emit final answer, it must be valid JSON…") so the system prompt stops fighting the deployer's "use tools first" guidance. **(3)** Session loop self-correction in `TurnRunner.trySelfCorrectSchema` pattern-matches `StructuredOutputParseException` after `subscriber.awaitDone()`, appends the model's wrong attempt to history as an assistant message, injects `StructuredOutputParseException.correctionMessage()` (field-level diff only — rawContent is intentionally not echoed per the exception's class-level cost note) as a synthetic user turn, returns `TOOL_CALLS` so the loop iterates. Bounded by `SessionLimits.maxTurns()`. `SchemaValidator` is public in `core/schema` and reused by typed `runBlocking` post-hoc parsing |
| **CollectingTraceListener** | Thread-safe `TraceListener` in `core/trace` that accumulates fired traces into a `List<Trace>` |
| **Annotation (v2 structured note)** | `core.trace.Annotation` attaches a structured judgment to a real trace/span `subjectId` (opaque consumer subjects were rejected — an annotation always targets something Helios observed). `facet` (nullable) is a named sub-coordinate within the subject so one author holds many judgments per subject without synthetic ids; `authorKind` (`HUMAN\|MODEL\|SYSTEM` enum) types the author; `metadata` is a first-class `Map<String,Object>` → `jsonb`, always non-null/immutable/null-tolerant. `PgTraceStore.upsertAnnotation` persists the full record, idempotent on `(subjectId, facet, label, authorId)` via a partial unique index with `COALESCE(facet,'')` (author-less rows always insert, preserving pre-v2 behavior; `createdAt` preserved + `updatedAt` advanced on conflict). Reads: `findAnnotationsBySubject` / `findAnnotationsBySubjects(batch)` / `listAnnotations(Paginate, scimFilter)` over first-class columns. `facet`/`label`/`metadata` are opaque — stored and returned uninterpreted. DDL delta in CHANGELOG |
| **SpanListener (live observability)** | `core.trace.SpanListener` fires `onSpanStart(SpanStart)` and `onSpanEnd(Span)` as spans open/close — parallel SPI to `TraceListener` which fires only at trace close |

## Resource Lifecycle

`Model` is `AutoCloseable` and holds long-lived OS resources (HttpClient connection pools, file descriptors). Per-provider `close()` calls `httpClient.shutdown()` with a 5s grace period before `shutdownNow()`.

**Ownership rule:** the component that constructs a `Model` owns its lifecycle. A session does NOT close its Model — closing a Model while sibling sessions reference it would fail their subsequent requests. Build the Model once at app startup, share it across many sessions, and `close()` it once at shutdown.

`AgentSession` is `AutoCloseable` and owns its loop, per-session publisher executor, and any pending tool/question state. Always use `try-with-resources`; `close()` cancels the loop, settles the result future, and waits up to 5 s for the publisher executor to drain before forcing shutdown.

`ReplSession` and `Sandbox` are `AutoCloseable` and own their subprocess. `JvmSandbox` also installs a JVM shutdown hook so a leaked sandbox is force-killed on host JVM exit.

On JDK 25 the JDK `HttpClient`'s selector and default executor are daemon threads, so a leaked Model does not prevent JVM exit. The leak is OS-level (FDs, sockets, pooled connections), not threads. Closing remains the right thing for any long-running service.

## Review False Flags

When critically reviewing this codebase, do NOT flag the following — they have been investigated and are not issues:

- **PgPromptRegistry.register() TOCTOU version race** — The database `UNIQUE (name, version)` constraint catches concurrent duplicate inserts. The transaction will fail and the caller retries. This is by design.
- **SCIM filter SQL injection** — The `scim-sql` library produces parameterized clauses with bind variables. It is our own OSS library and is safe.
- **FaultTolerance virtual executor leak** — `newVirtualThreadPerTaskExecutor()` uses JVM-managed virtual threads. `cancel(true)` interrupts the thread; it gets GC'd. No leak.
- **Streaming InputStream not closed** — `BufferedReader.close()` cascades through `InputStreamReader.close()` to `InputStream.close()`. Java decorator pattern handles this correctly.
- **Silent tool failure in session loop** — By design. The loop sends the failure back to the model so it can self-correct. `maxTurns` guards against infinite loops.
- **parseStreamEvent returns null for thought events** — By design. Thoughts accumulate internally and surface in the `Done` event.
- **Jackson exception = silent data loss in persistence** — Exceptions are caught and wrapped in `PgException`, which propagates to the caller. Not silent.
- **Parallel tool execution swallows exceptions** — By design. Each tool catches its own FT exceptions and returns `ToolResult.failure()`. One tool timing out doesn't abort others. The model sees all results and self-corrects.

### Schema migrations

User-visible release-by-release breaking changes — including DDL deltas for `helios_agent_runs`, `helios_tool_calls`, `helios_messages`, `helios_sessions` — live in [`CHANGELOG.md`](CHANGELOG.md). Older migrations (pre-1.5) are recoverable from git history.

## REPL Module (substrate only — v2)

Sandboxed JShell execution that the future CodeAct preset will assemble onto. The v1 RLM/CodeAct
harnesses were removed in the v2 cut (spec §3.5). What survives is the substrate: `JvmSandbox`,
`JvmSandboxBootstrap`, `ReplSession`, `ReplConfig`, `CodeExecutionTool`, `InputBindings`,
`SandboxPrelude`, and the `HostFunction` infrastructure (no `predict` / `submit` / `fetch` /
`query` built-in host functions — those are v2 session-level Tools).

### Key Design Decisions

**Protocol.** JSON-RPC 2.0 over NDJSON; `\0RPC:` magic prefix distinguishes RPC lines from regular stdout. Sandbox exceptions returned as `ToolResult.success()` so the model sees tracebacks and self-corrects.

**Single-execute semantics.** `JvmSandboxBootstrap` enforces single-execute with `Semaphore(1)` — `System.setOut` / `setErr` are JVM-global; concurrent evals would corrupt streams. `ReplSession` uses `Semaphore.tryAcquire()` for max-concurrent-sessions bounding. `HostFunctionRegistry.freeze()` prevents modifications after sandbox startup.

**`CodeExecutionTool` output truncation.** Default 5000-char cap on the formatted text shown to the model, plus an explicit marker stating "Variables in the sandbox retain their full values." Load-bearing context-rot fix; full untruncated text stays in `ReplSession.history()`. `ReplConfig.withMaxOutputCharsToModel(int)` tunes it.

**`InputBindings`.** Generates a JShell snippet that calls `HostBridge.getInput()` to retrieve the input fields as a `Map<String, Object>` and casts each top-level record field to its declared generic type rendered as Java source. **No Jackson reference appears in the snippet** — JSON conversion happens host-side, and JShell only sees `java.*` types plus `HostBridge`. This is what makes the binding work uniformly under both classpath and JPMS launches: under JPMS the parent's modulepath modules are invisible to JShell's internal javac, so any reference to `tools.jackson.*` would fail to compile.

**`SandboxPrelude`.** Installs a curated JShell preamble at sandbox boot: standard imports (`java.util.*`, `java.util.stream.*`, `java.util.function.*`, `java.io.*`, `java.math.*`, `java.time.*`, `Collectors`), free `print` / `println` / `printf` (PRINTING-equivalent), and ten script-style helpers (`sum`, `sumInts`, `mean`, `max`, `min`, `join`, `filter`, `map`, `sorted`, `countBy`).

**Custom host functions are typed and JShell-callable.** Every non-reserved `HostFunction` registered before sandbox boot gets a typed JShell static wrapper synthesized into the prelude. `HostFunction("marketQuote", [HostParameter.required("ticker", STRING, ...)], handler)` becomes callable as `marketQuote("AAPL")` from emitted Java code. The wrapper packs args into a `LinkedHashMap` keyed by parameter name and dispatches via `HostBridge.__call`. Reserved names (`getInput`, `__getInput`, `__call`) are skipped — `HostBridge` static methods own those signatures.

**`CodeActStrategy.buildSystemPrompt` / `RlmStrategy.buildSystemPrompt`** return the assembled system-prompt `String` directly. Earlier versions wrapped the result in a `Skill` record (carrying `name` + `instructions` + `envTips` + `tools`), but the wrapping was decorative — only `.instructions()` was ever read, the `merge()` composition story was unused outside tests, and the type collided with the [agentskills.io](https://agentskills.io) open-standard meaning of "Skill". Both retired on 2026-05-18. The `Skill` namespace under `ai.singlr.repl` is now reserved for a future agentskills.io-compatible primitive (on-disk SKILL.md folders + progressive disclosure); build when a customer asks.

**`SandboxBindingsListener`.** `ReplConfig.Builder.withSandboxBindingsListener(...)` observes the sandbox's working memory after each `execute_code`. Listener receives `(Map<String,String>, ExecutionResult)` where the Map carries every user-declared `var` (excluding `__`-prefixed harness internals), each value's `toString` capped per-value (default 200 chars) and per-snapshot (default 16 KB). Default off — opt-in. Use case: live "user watches the agent think" UI; also useful for post-mortem debugging when truncated stdout isn't enough.

**`ExecutionResult.executedCode`.** Every result carries the source code that ran (captured parent-side from `ExecutionRequest.code()`; no protocol change). Per-call cap via `ReplConfig.withMaxExecutedCodeChars(int)` (default 5000); truncation appends `... (len=N)`. Combined with the `bindings` field, gives live observers the *what reasoning produced this state* alongside *what state exists*.

**Rejected design — typed positional `submit` codegen.** Generating `static void submit(int x, String y)` from the output schema looked ergonomic but cut integration determinism from 10/10 to 7/10 in testing — Java's positional overloading lets the LLM put values in the wrong slots. Map-based `submit(Map.of("field", value, ...))` (when a preset re-introduces submit) stays. Rule: **LLM-facing API design — keys must be explicit, ambiguity is fatal.**

**Sandbox policy seam (`ai.singlr.repl.sandbox.policy`).** Layer-2 enforcement point that wraps JShell's `LocalExecutionControl` via `GuardedExecutionControl` (subclass) + `GuardedExecutionControlProvider`. The bootstrap installs the provider via `JShell.builder().executionEngine(new GuardedExecutionControlProvider(policy), Map.of())` instead of the stock `"local"` engine. Every snippet class, JShell wrapper, and `var` declaration flows through `load(ClassBytecodes[])` as raw bytes before any classloader sees them — that's the chokepoint `BytecodeVerifier` scans against the active `SandboxPolicy`. `SandboxPolicy` (record + Builder, `permissive()` preset) carries `deniedClasses`, `deniedPackages`, `denyReflection`, `denyNativeAccess`, `denyDynamicClassDefinition`, and `onViolation`. Non-permissive policies travel host→subprocess via `--sandbox-policy=<base64>` argv (encoded by `SandboxPolicySerialization`); permissive is the bootstrap's own default and isn't propagated. SecurityManager is gone in JDK 25 (JEP 411/486 finalized), so the only honest in-JVM enforcement is bytecode-level — and even that is defense-in-depth, not the perimeter (OS-level isolation remains the only authoritative boundary).

**Sandbox policy enforcement (`PolicyBytecodeVerifier`).** Real verifier built on the JDK Classfile API (JEP 484, finalised in JDK 24). Scans every method's code stream for five instruction families: `InvokeInstruction` (INVOKE*), `NewObjectInstruction` (NEW), `FieldInstruction` (GET*/PUT*), and `LoadConstantInstruction` carrying a `ClassDesc` (LDC of `Class<?>`). `INVOKEDYNAMIC` is deliberately NOT scanned — its bootstrap-method reference points to platform code (`LambdaMetafactory.metafactory`, `StringConcatFactory.makeConcat*`, `ObjectMethods.bootstrap`); scanning it would reject every lambda and string concatenation without any security benefit, because the dangerous capability is an explicit `INVOKESTATIC java/lang/invoke/MethodHandles.lookup` that the `InvokeInstruction` scan catches. Rule order on match: explicit `deniedClasses` → `deniedPackages` → categorical `denyReflection` / `denyNativeAccess` / `denyDynamicClassDefinition` → allow-list default-deny. Reflection rule denies entire `java/lang/reflect/*` and `java/lang/invoke/*` plus the reflective entry points on `java/lang/Class` (`forName`, `newInstance`, `getMethod*`, `getField*`, `getConstructor*`, `getDeclared*`, `getRecordComponents`, `getEnclosingMethod`, `getEnclosingConstructor`) — non-reflective `Class` methods (`getName`, `cast`, `isInstance`, etc.) stay callable. **Denial signal**: JShell silently swallows the `ClassInstallException` thrown when the verifier rejects load — the snippet's `SnippetEvent` comes back with status `VALID`, no value, no exception attached. `GuardedExecutionControl.verifyAll` writes the policy message to `System.err` before rewrapping into `ClassInstallException`; the sandbox bootstrap captures stderr during `execute` and forwards it back to the host as the eval result's `stderr`, so the model receives a clean policy traceback through the standard channel.

**Allow-list mode (`SandboxPolicy.allowedPackages`).** Opt-in inverse of the deny-list. When non-empty, the verifier flips for JDK-scoped owners (under `java/`, `javax/`, `jdk/`, `sun/`, `com/sun/`): default-deny, allow only if the owner's package matches one of the allowed prefixes. Non-JDK owners (snippet's own `REPL.$JShell$N` wrappers, user helper classes, third-party JARs on the snippet's classpath) bypass the allow-list and are always permitted — they're the very code being verified. Deny rules layer on top as overrides ("deny wins"). The rule label on violation is `allowedPackages-default-deny`.

**`SandboxPolicy.noEgress()` curated preset.** "Compute with no egress" — allows `java.lang`, `java.util` (+ stream/function/regex/concurrent.atomic), `java.math`, `java.time`, `java.text`, `java.io` (for stdio + in-memory streams); denies the dangerous classes inside those packages (`ProcessBuilder`, `Runtime`, `Thread`, `ThreadGroup`, `File*Stream`, `FileReader`, `FileWriter`, `RandomAccessFile`, `ObjectInputStream`, `ObjectOutputStream`); enables all three categorical denies. Composes allow-list mode with explicit denies and categorical flags — single API call for the most common enterprise posture.

**Subprocess module restriction (`SubprocessModules`, L3).** Sealed interface (`Unrestricted` / `Restricted` records) controlling the subprocess JVM's `--limit-modules` flag. Three factories: `unrestricted()` (default — all JDK modules observable), `minimal()` (required roots only — `java.base`, `java.compiler`, `jdk.compiler`, `jdk.jshell`; under modulepath launch `ai.singlr.repl` is also added; strips `java.sql`, `java.naming`, `java.scripting`, `java.desktop`, `jdk.httpserver`, etc.), `allowingExtras(String...)` (required + named extras). Wired through `JvmSandboxConfig.withSubprocessModules(...)`. **Works under classpath AND modulepath launches.** The bootstrap module name (`ai.singlr.repl`) is conditionally appended to `--limit-modules` by `JvmSandbox.buildLaunchCommand` only when the parent uses `--module-path` — naming an unobservable module under classpath launch would crash the subprocess with "Module not found". Classpath launches get the JDK-only baseline; the bootstrap loads via classpath into the unnamed module which can read all observable JDK modules. **Bootstrap-transitive-closure limit (modulepath only)**: under modulepath launch the bootstrap's transitive `requires` clauses keep their target modules observable — `ai.singlr.core` requires `java.net.http` via `HttpClientFactory`, so http stays observable under any L3 setting; under classpath launch module-info `requires` are ignored (classpath JARs are unnamed-module members), so classpath gets *stricter* L3 enforcement. Deployers on modulepath who need to deny those modules reach for L2 `deniedPackages` instead. Composes with L2 enforcement — limit-modules eliminates whole categories at compile time, the verifier catches the rest at load time. `JvmSandbox.shouldPropagateJvmArg` filters `--add-modules` and `--limit-modules` from inherited parent args so Maven Surefire's `--add-modules=ALL-MODULE-PATH` doesn't defeat the restriction. Empirical verification: `SubprocessModulesClasspathLaunchTest` manually launches the bootstrap in classpath-only mode and proves modules are stripped from the subprocess's boot layer.

## Example modules

- **`examples/session-demo`** — end-to-end `AgentSession` integration against Gemini with a real workspace. Validates the v2 session shape including `SessionPresets.workspace(...)`, the streaming `QueryEvent` flow, and the typed `runBlocking` path.
- **`examples/codeact-demo`** — Java-as-action loop on `AgentSession` + `CodeActPreset`. The preset assembles the REPL substrate (JvmSandbox + ReplSession + CodeExecutionTool) onto a session and adds the CodeAct system prompt. Replaces the deleted v1 `CodeActHarness`.
- **`examples/rlm-demo`** — leader-agent + worker-agent pattern rebuilt on v2 primitives (workers are Tools wrapping nested AgentSessions). Replaces the deleted v1 `RlmHarness`. Not "RLM" in the original RLM sense — kept the name for continuity, drop it when a better label sticks.

The other v1 example modules (`autoresearch-prompt`, `autoresearch-code`, `gepa-prompt`, `rlm-demo-jpms`, `workload-fixtures`) were deleted with the v1 core surface and have no v2 replacement yet.

**Roadmap items tracked in `docs/specs/`** rather than in this file — keeps this README a description of what *is*, not a wish list.
