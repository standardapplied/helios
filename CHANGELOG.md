# Changelog

All notable changes to Helios are documented here. Versions follow [SemVer](https://semver.org/).

## [Unreleased]

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
