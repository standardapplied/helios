# Sessions

`helios-session` runs an agent as an `AgentSession`: a conversation the model and its tools work
in, run after run, on virtual threads. You send messages into it, read its event stream, interrupt
the run in progress and close it when you are done. This guide covers the session's surface, how a
run ends, its events, storing a conversation, context compaction, failures and resource
ownership. Cost tracking and budgets are in the [providers guide](providers.md#cost-and-budgets);
tools, permissions and hooks have [their own guide](tools-and-permissions.md), and so do
[subagents and skills](subagents-and-skills.md). Back to the [README](../README.md).

- [Sessions, runs and turns](#sessions-runs-and-turns)
- [Presets and options](#presets-and-options)
- [How a run ends](#how-a-run-ends)
- [Events](#events)
- [Storing a conversation](#storing-a-conversation)
- [Context compaction](#context-compaction)
- [Failures](#failures)
- [Resource lifecycle](#resource-lifecycle)

## Sessions, runs and turns

A *session* is a conversation; a *run* is one execution, from the message that starts it to its
terminal `ResultMessage`; a *turn* is one model call and its tool round. `AgentSession.open(SessionOptions)`
opens a session with no run. A session runs again after any ending (success, a limit, a cancel, an
error) until it is closed, and every run sees the whole conversation. It is built for tool use
across many turns, a user who steers a run while it goes, a UI that watches it live and permission
gates around tool calls.

- `send(UserMessage)` (or `send(String)`) steers the run in progress, or starts the next run when
  none is in progress, and returns that run's `CompletableFuture<ResultMessage>`.
- `events()` opens a new `EventStream`: an `Iterable<QueryEvent>` that is also `AutoCloseable`,
  read once with for-each, across every run until the session closes. See [Events](#events).
- `runBlocking(msg)` is `send(msg).join()`; `runBlocking(msg, OutputSchema)` returns the run's
  answer parsed as the schema's type.
- `interrupt()` cancels the run in progress (`Cancelled`, reason `"interrupted"`); the session
  stays open, and the next `send` starts a new run on the same conversation.
- `answer(questionId, response)` resolves a pending `AskUserQuestion` or permission approval;
  `pendingQuestions()` lists the ones still open. A run's end cancels its unanswered questions.
- `close()` is `AutoCloseable`'s and never throws; see [Resource lifecycle](#resource-lifecycle).

<!-- snippet: QuickStarts#chat -->
```java
try (var session = AgentSession.open(SessionPresets.workspace(model, repo).build())) {
  var answer = session.send("Which classes form the session's public API?").join();
  var followUp = session.send("Which of them does a chat application need?").join();
  System.out.println(answer + "\n" + followUp);
}
```

`maxTurns`, `maxBudgetMicroUsd` and `maxWallClock` count per run. A run that ends with tool calls
unanswered (a hook's `Stop`, an interrupt, an error) answers each with a "not run" tool message, so
the next run's history is one a provider accepts. An execution provider that refuses at `open`
makes `open` throw `SessionUnavailableException`.

The smallest session is a model and the defaults:

<!-- snippet: QuickStarts#minimalSession -->
```java
var model =
    new AnthropicProvider()
        .create(
            AnthropicModelId.CLAUDE_SONNET_5_5.id(),
            ModelConfig.of(System.getenv("ANTHROPIC_API_KEY")));

try (var session = AgentSession.open(SessionPresets.minimal(model).build())) {
  var terminal = session.runBlocking(UserMessage.text("What is the capital of France?"));
  switch (terminal) {
    case ResultMessage.Success s -> System.out.println(s.result());
    case ResultMessage other -> System.err.println("non-Success: " + other);
  }
}
```

## Presets and options

`SessionPresets` returns a `SessionOptions.Builder` that already holds the model; chain any other
`with*` call before `build()`. `SessionOptions.newBuilder().withModel(model)` starts from nothing.

- `minimal(model)`: the model and the defaults for everything else.
- `readOnly(model, root)`: the `Read`, `Glob`, `Grep` and `LS` tools rooted at the workspace,
  under `Permission.planMode()`.
- `workspace(model, root)`: the same read tools, a `FileSystemMemoryBackend` under the workspace
  (the session registers `MemoryRead` and `MemoryWrite` for it) and `Permission.defaultInWorkspace()`,
  which allows reads and memory writes and asks before writes and execution.

Further tools append with `withTool(binding)` (`withTools(list)` replaces the list), as hooks do
with `withHook`. Built-in tools come with what they serve: a memory backend adds `MemoryRead` and
`MemoryWrite`; `withExecutionProvider(provider)` adds `Execute` and starts and ends the provider
with the session (`workspace(model, root).withExecutionProvider(provider)` is an agent that runs
code, and `defaultInWorkspace()` asks before each execution); `withAskUserQuestion()` adds
`AskUserQuestion`; `withSubagent(subagent)` adds `Agent` and `withSkills(skills)` adds `Skill`
(see [Subagents and skills](subagents-and-skills.md)). Two tools with one name fail
`AgentSession.open`. Every session has a permission policy; see
[Permissions](tools-and-permissions.md#permissions).

## How a run ends

A run ends with one `ResultMessage`. The model's `FinishReason` decides most endings:

- `STOP` ends the run in `Success`, unless a message sent while the turn ran is waiting, in which
  case the run goes on with it.
- `TOOL_CALLS` runs the tools and goes on to the next turn.
- `REFUSAL` and `CONTENT_FILTER` end it in `Refusal`.
- `ERROR` ends it in `ErrorDuringExecution`, or in `ErrorTransientStream` when the failure was
  transient and its retries ran out.
- `LENGTH`, a response truncated at the provider's output cap, ends it in `ErrorDuringExecution`
  of kind `max-tokens` rather than re-issuing the turn. Raise `ModelConfig.maxOutputTokens` when a
  turn needs more room; re-issuing would spend `maxTurns` without progress.

Each limit has its own terminal, and the next `send` starts a new run with fresh limits.
`SessionLimits.newBuilder()` sets them:

| Builder method | Limits | Default |
|---|---|---|
| `withMaxTurns(int)` | turns per run, then `ErrorMaxTurns` | 100 |
| `withMaxBudgetMicroUsd(long)` | spend per run, then `ErrorMaxBudgetUsd` | unset |
| `withMaxWallClock(Duration)` | a run's time, then `ErrorMaxWallClock` | 1 h |
| `withToolTimeoutDefault(Duration)` | one tool call | 2 min |
| `withStopGrace(Duration)` | how long anything the session cancels is given to stop | 5 s |
| `withMaxContextTokens(long)` | the compaction trigger; `0` is the model's window minus an output reserve of 20,000 tokens or a quarter of the window, whichever is smaller | 0 |
| `withStreamRetryPolicy(StreamRetryPolicy)` | retries of a turn that failed with a `TransientStreamException`, then `ErrorTransientStream` | 3 attempts in all, back-off from 1 s, ×4, capped at 30 s, ±25 % jitter |
| `withStartTimeout(Duration)` | the execution provider's `onSessionStart` | 60 s |
| `withMaxToolResultChars(int)` | characters of one tool result that enter history | 65,536 |

## Events

`events()` opens a new `EventStream` per call, spanning every run until the session closes, with
its own 256-event buffer. Open it before the first `send` and read it on a thread of its own;
`RunStarted` and `RunEnded` bracket each run, and every event names its `runId`:

<!-- snippet: QuickStarts#liveEvents -->
```java
var events = session.events();
var printer =
    Thread.ofVirtual()
        .start(
            () -> {
              try (events) {
                for (var event : events) {
                  switch (event) {
                    case QueryEvent.AssistantText t -> System.out.print(t.text());
                    case QueryEvent.ToolUse u ->
                        System.out.println("\n[tool: " + u.call().name() + "]");
                    case QueryEvent.ToolResult r ->
                        System.out.println("[result: " + r.result().output() + "]");
                    case QueryEvent.MessageBlocked b ->
                        System.err.println("[blocked: " + b.reason() + "]");
                    case QueryEvent.RunEnded e -> System.out.println("\n— run done —");
                    default -> {}
                  }
                }
              }
            });
var terminal = session.send("Refactor the loop module's package-info comments.").join();
session.close();
printer.join();
```

The sealed `QueryEvent` interface covers everything the loop emits: `AssistantText`,
`AssistantThinking`, `AssistantCitations`, `UserMessageReceived`, `MessageBlocked`,
`ContextWarning`, `ContextEdited`, `TurnEnded`, `TurnRetried`, `ModelCallEnded`, `RunStarted`,
`RunEnded`, `Error`, `ToolUse`, `ToolResult`, `ToolBlocked`, `ToolMutated`, `HookFired`,
`QuestionAsked` and `EventsDropped`; see the [`QueryEvent`](../session/src/main/java/com/standardapplied/helios/session/QueryEvent.java)
Javadoc.

**Delivery.** The loop never waits for a reader on a routine event: a stream whose buffer is full
misses it at once, so a slow or paused reader never stalls the loop. `RunEnded`, `QuestionAsked`
and `Error` wait up to 30 s for room, then are missed too; a missed terminal stays readable through
the future `send` returned, a missed question through `pendingQuestions()`. The events a stream
misses in a row are marked by one `QueryEvent.EventsDropped`, read where they would have been
(before the next event, or before the iteration ends) and carrying their `count` and the first
one's run, turn and timestamp. A stream's first miss is logged as a WARNING, later ones at FINE,
and a missed control event always as a WARNING. A reader that keeps up never reads a marker. The
session never waits for readers, so a reader that must have handled `RunEnded` before that future
completes synchronises with its own thread. Details: `EventStream` Javadoc.

A host that forwards the stream to a browser forwards the marker too (the SSE route names it
`event: EventsDropped`). On `EventsDropped`, the turn's partial text is incomplete: replace it with
the run's result, from the future `send` returned, the `/result` long poll or the stored session.

## Storing a conversation

`withStore(store)` makes a session durable: `open` loads the conversation stored under the session
id, or starts it when there is none, and each turn appends what it added, so a handle opened days
later, in any process, carries on. Helios ships `InMemorySessionStore`, `FileSessionStore` (one
JSON-lines file per session) and, in `helios-persistence`, `PgSessionStore` (one Postgres row per
entry); the system prompt is never stored and always comes from the options. A session id is 1 to
128 characters of `[A-Za-z0-9._:-]`, not starting with `.`.

<!-- snippet: QuickStarts#storedChat -->
```java
var store = FileSessionStore.of(Path.of("conversations"));
try (var session =
    AgentSession.open(
        options.toBuilder().withSessionId("member-42").withStore(store).build())) {
  session.send("Where did we leave off?").join();
}
```

One writer per session id at a time is the caller's job: a second writer's run ends
`ErrorDuringExecution` of kind `SessionConflict`, and its session closes. A run's last turn is
stored with a `SessionEntry.RunEnded` naming the run and its terminal, in one batch, so after a
crash `store.load(id).lastRunEnded()` tells whether the run that wrote last ended. A `Success`
run's answer is `lastRunEnded().orElseThrow().result()`, whether the model's reply, a hook's `Stop`
text or empty. The messages the session writes itself (a rejected structured output, its
correction, a hook's `Inject`, a partial turn and a skill put back after a compaction) carry a
`MessageOrigin` tag in their metadata under `helios.origin`.

**Partial turns.** A model call cut short, by `interrupt()`, `close()`, the wall clock, a stream
failure (for a transient one, the last attempt's text once retries run out, or the failed attempt's
when the run is cut during its retry back-off) or the stream idle timeout, keeps the text it
streamed as the run's last assistant message, tagged `MessageOrigin.PARTIAL_TURN`
(`helios.origin`: `partial-turn`); its tool calls, its reasoning and its provider metadata are
dropped, and blank text keeps nothing. A store holds it with the run's `RunEnded`, and the next
run, in the same session or one reopened under its id, sends it as an ordinary text-only assistant
turn before the new user message, so the model can carry on. It is the text the run's
`AssistantText` events carried: to show it as an unfinished reply, or to leave it out of a
transcript, check `MessageOrigin.of(message)` for `PARTIAL_TURN`.

**Restarting after a crash.** A run the process lost leaves a stored history that ends after a
whole turn, never with a tool call that has no result, so the session reopens and takes a new
message. Load the session, see that `lastRunEnded()` is empty, reopen it under the same id, then
`send` a continuation of your choosing, or record the run as abandoned and send nothing.

<!-- snippet: QuickStarts#restart -->
```java
var ended = store.load("member-42").lastRunEnded();
try (var session =
    AgentSession.open(
        options.toBuilder().withSessionId("member-42").withStore(store).build())) {
  if (ended.isEmpty()) {
    session.send("Your last answer was cut off; pick up where you left off.").join();
  }
}
```

## Context compaction

Long sessions outgrow the model's context window. The loop counts context fill from the input size
the provider reported for the last request, system prompt and tool definitions included, plus a
pluggable `TokenCounter`'s estimate (default `TokenCounter.charBased()`, about four characters per
token) of the messages added since; before the first report, after a history replacement and after
a turn with no report, the estimate covers the whole history. It checks before every model turn
and after every turn the run goes on from, never after the turn that ends a run, so a run's final
answer is stored before any compaction summarises it. It reacts at two watermarks of
`SessionLimits.maxContextTokens()`:

- **0.85** emits `QueryEvent.ContextWarning(usagePct)` once.
- **0.95** invokes the configured `ContextCompactor`, swaps the returned history in if the
  `TokenCounter` estimates it below the history it was given, and emits
  `QueryEvent.ContextEdited(removedBlocks, tokensBefore, tokensAfter)`, both token counts being
  those estimates; the warning then fires again on a future climb. The compactor is skipped while
  what the estimate cannot see, such as inline files and tool definitions, reaches 0.95 by itself:
  a request that truly overflows ends with the provider's error.

The default `SummarizingCompactor`, bound to the session's model, keeps every leading system
message and the pending user turn (the user messages after the last assistant turn) and replaces
the rest with one user message, prefixed `SummarizingCompactor.SUMMARY_PREFIX`, holding a summary
from one model call. No earlier assistant turn survives, so no reasoning a provider signed against
the old conversation (Claude Fable 5.2, Mythos 5.2, Fable 5.1, Mythos 5.1, Opus 5.5, Sonnet 5.5 and
Haiku 5.5) is replayed and no tool call or tool result is left to pair: the result is valid for
every provider. The default prompt asks for the user's goal, the facts found, the decisions made,
the open questions and the work left. No file survives either: the summary model sees each
summarised file as a `{file <mimeType>}` or `{file <mimeType> <uri>}` marker, never its bytes, and
the summary ends with a list of them, in history order, as no longer available unless sent again.
It never compacts in the middle of a tool round. The summary call runs on a virtual thread under a
timeout (default 60 s) and ends when the run is cancelled; when it throws, times out, is cancelled
or returns blank, the history stays unchanged.

Pick the summary model, prompt and timeout, swap the whole compactor, or turn compaction off
(`ContextCompactor.disabled()` leaves the history unchanged, so a long session overflows and the
provider's error ends the run):

<!-- snippet: QuickStarts#compaction -->
```java
// 1) Default — SummarizingCompactor bound to the session's model
var session = AgentSession.open(SessionPresets.workspace(model, repo).build());

// 2) BYO compactor — a cheaper summary model or prompt, or any ContextCompactor of your own
var custom =
    SummarizingCompactor.newBuilder(cheapHaikuModel)
        .withSummaryPrompt("Summarise focusing on file edits and unresolved questions.")
        .withSummaryTimeout(Duration.ofSeconds(30)) // bound the summary call
        .build();
var session2 =
    AgentSession.open(
        SessionPresets.workspace(model, repo).withContextCompactor(custom).build());

// 3) Opt out wholesale — long sessions overflow naturally with an ErrorDuringExecution
var session3 =
    AgentSession.open(
        SessionPresets.workspace(model, repo)
            .withContextCompactor(ContextCompactor.disabled())
            .build());
```

For ad-hoc rewrites, a `PreModelTurnHook` returns `HookOutcome.mutateHistory(rewritten)`: the loop
swaps the history and emits `HookFired` with phase `PRE_MODEL_TURN` and that `MutateHistory` as its
outcome. To see the history on both sides of a compaction, wrap the default compactor;
`QueryEvent.ContextEdited` carries the counts, and a `PreModelTurnHook` returning `Stop` ends the
run after one:

<!-- snippet: QuickStarts#archivingCompaction -->
```java
ContextCompactor archiving =
    (history, context) -> {
      archive.save(history);
      var result = defaultCompactor.compact(history, context);
      archive.saveCompacted(result.history());
      return result;
    };
var options = SessionPresets.workspace(model, repo).withContextCompactor(archiving).build();
```

A session with skills puts back what a compaction removed of the skills the model loaded, whatever
the compactor, after any wrapper of yours has seen the result and before the shrink check; see
[Skills](subagents-and-skills.md#skills-in-a-session). The default compactor's result is then
`[system, skills..., summary, pending user turn...]`. A `PreModelTurnHook`'s `MutateHistory` gets
nothing put back.

`withTokenCounter(...)` swaps the estimator, for example for a provider's own tokenizer. The
summary call's `Usage` flows into the run's usage and its `CostCalculator`, so
`maxBudgetMicroUsd` gates compaction spend with turn spend. A compactor of your own reports it
through `CompactionResult.usage()`; one that only trims returns `CompactionResult.noOp(history)`.

## Failures

The session loop and `RetryPolicy` catch `Exception`, not `Throwable`. An `Error` (out of memory,
a stack overflow, a `LinkageError`, an `AssertionError`) caught on any thread the session runs work
on (the loop, the model stream, a tool, a hook, a cancellation callback, the execution provider's
start and end) is never retried or turned into a tool result or terminal: it ends the session, no
further model or tool call is made, and the run's future completes exceptionally with the first
such `Error`, as the same instance, and the session closes (later ones are suppressed on it; one no
run carries is logged at `SEVERE`). The run's virtual thread then ends by throwing it to its
uncaught-exception handler. That ends the thread, not the JVM; what an `Error` means for the
process is the host's decision. A failure inside the sandbox child process stays contained and
comes back as tool output. `Tool.execute` preserves the calling thread's interrupt status when the
executor's exception chain carries an `InterruptedException`.

## Resource lifecycle

`Model` is `AutoCloseable` and holds long-lived resources (an HTTP connection pool, file
descriptors). Build it once at application start, share it across sessions and close it once at
shutdown. The component that constructs a `Model` owns it: a session does not close its model,
since that would break sibling sessions sharing it.

<!-- snippet: QuickStarts#sharedModel -->
```java
try (var model = new AnthropicProvider().create(modelId, config)) {
  for (var request : requests) {
    try (var session = AgentSession.open(SessionPresets.workspace(model, repo).build())) {
      session.runBlocking(UserMessage.text(request));
    }
  }
} // HttpClient and connection pool released here
```

`AgentSession` is `AutoCloseable` and owns its loop, its event streams and any pending tool or
question state; use try-with-resources. `close()` cancels the run in progress (`"session closed"`)
and waits for it, then tears the session down: `onSessionEnd` for a session with an execution
provider, then the event streams, without waiting for their readers. The run's end and the
teardown share one deadline, `SessionLimits.stopGrace` (5 s) from the start of `close()`; a step
after one that used it up still runs but is not waited for. A tool that ignores both its
cancellation token and interruption may still run, and its effects land, after the result settles;
it is logged as still running.
