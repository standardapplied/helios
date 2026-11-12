# Helios

Helios is an agentic framework for the JVM. It gives you a provider-agnostic `Model` interface with
Anthropic, Gemini and OpenAI providers, and `AgentSession`, a long-lived, streamable, steerable
conversation that runs the agent loop with tools, permissions, hooks, durable storage and
OpenTelemetry tracing. Optional modules serve sessions over HTTP, run model-written Java in a
sandbox, compute embeddings locally and store traces in PostgreSQL.

Explicit wiring, no annotation-driven dependency injection, no reflective surprises. Published to
[Maven Central](https://central.sonatype.com/namespace/com.standardapplied) under
`com.standardapplied`. MIT licensed.

## Requirements

- Java 25 or later.
- Building Helios itself needs Maven 3.9.14 or later.
- The strict workspace file tools and filesystem memory also need Linux x86-64 or AArch64 and a
  native-access grant; see [Workspace confinement](docs/security.md#workspace-confinement).

## Modules

Each jar is published on its own; most applications want `helios-session` and one provider.

| Artifact | What it gives you | External dependencies |
|---|---|---|
| `helios-core` | `Model` and its value types, `Tool` and `CommandGrant`, `OutputSchema` and `Provenanced<T>`, `TokenCounter`, `FaultTolerance`, `SecretRegistry` and `Redactor`, `CostEstimate` and `CostCalculator` | none |
| `helios-session` | `AgentSession`, `SessionPresets`, hooks, `Permission`, workspace file tools, memory, `ExecutionProvider`, `ContextCompactor`, session stores | Jackson 3, OpenTelemetry API |
| `helios-anthropic` | Anthropic Claude provider (Messages API) | Jackson 3 |
| `helios-gemini` | Google Gemini provider (Interactions API) | Jackson 3 |
| `helios-openai` | OpenAI provider (Responses API) | Jackson 3 |
| `helios-runtime` | HTTP and SSE surface for sessions | Helidon SE, Jackson 3 |
| `helios-repl` | Sandboxed JShell behind the `Execute` tool (`JShellExecutionProvider`, `JvmSandbox`, host functions) | Jackson 3 |
| `helios-onnx` | Local embeddings | ONNX Runtime, DJL Tokenizers |
| `helios-persistence` | PostgreSQL `PgSessionStore`, `PgTraceStore`, `PgSpanExporter`, `PgAnnotationStore`, `PgPromptRegistry` | Helidon DbClient, PostgreSQL JDBC, OpenTelemetry SDK |
| `helios-skills` | `Skills.discover` and `Skills.load`: Agent Skills folders (`SKILL.md`) into `Skill` values | Jackson 3 YAML (SnakeYAML Engine) |
| `helios-testing` | `ScriptedModel`, a deterministic `Model` for tests and evals | Jackson 3 |

## Installation

```xml
<properties>
  <helios.version>3.1.3</helios.version>
</properties>

<dependency>
  <groupId>com.standardapplied</groupId>
  <artifactId>helios-session</artifactId>
  <version>${helios.version}</version>
</dependency>
<dependency>
  <groupId>com.standardapplied</groupId>
  <artifactId>helios-anthropic</artifactId>  <!-- or helios-gemini, helios-openai -->
  <version>${helios.version}</version>
</dependency>
```

Add any other module from the table the same way, `helios-skills` for skills read from folders.
On the module path, `requires com.standardapplied.helios.session;` and the provider's module, such
as `requires com.standardapplied.helios.anthropic;` (`...helios.skills` for `helios-skills`).

## Quick start

### A model call

`Model` is the "send messages, get a response" shape: no session, no loop, no tools.

<!-- snippet: ProviderSnippets#modelCall -->
```java
try (var model =
    new AnthropicProvider()
        .create(
            AnthropicModelId.CLAUDE_SONNET_5_5.id(),
            ModelConfig.newBuilder().withApiKey(System.getenv("ANTHROPIC_API_KEY")).build())) {
  var response =
      model.chat(
          List.of(
              Message.system("You are a helpful assistant."),
              Message.user("What is the capital of France?")),
          List.of());
  System.out.println(response.content());
}
```

### A session with tools

`AgentSession` is a conversation that runs many times. The `workspace` preset gives the model the
`Read`, `Glob`, `Grep` and `LS` tools over a directory, a memory store, and a permission policy
that asks before writes and execution. `model` is any `Model`, such as the one above built outside
a try block, since the session uses it until it closes.

<!-- snippet: QuickStarts#workspaceSession -->
```java
try (var session =
    AgentSession.open(SessionPresets.workspace(model, Path.of("/path/to/repo")).build())) {
  var terminal =
      session.runBlocking(UserMessage.text("Summarise the public API of the session module."));
  System.out.println(((ResultMessage.Success) terminal).result());
}
```

### The HTTP surface

`helios-runtime` serves sessions to clients in any language. This server listens on
`127.0.0.1:8080` without authentication, which it allows only on a loopback address:

<!-- snippet: HttpSnippets#localServer -->
```java
try (var model =
        new AnthropicProvider()
            .create(
                AnthropicModelId.CLAUDE_SONNET_5_5.id(),
                ModelConfig.of(System.getenv("ANTHROPIC_API_KEY")));
    var server =
        RuntimeServer.builder()
            .withRegistry(SessionRegistry.inMemory())
            .withOptionsFactory(
                request ->
                    SessionPresets.minimal(model).withSessionId(request.sessionId()).build())
            .withoutAuthentication()
            .withPort(8080)
            .build()) {
  System.in.read();
}
```

```bash
curl -s -X POST 127.0.0.1:8080/v1/sessions
# {"sessionId":"sess-…","eventsUrl":"/v1/sessions/sess-…/events"}
curl -sN 127.0.0.1:8080/v1/sessions/sess-…/events &
curl -s -X POST 127.0.0.1:8080/v1/sessions/sess-…/messages -d '{"text": "What is the capital of France?"}'
curl -s '127.0.0.1:8080/v1/sessions/sess-…/result?timeout=60'
```

## What's in the box

- [Sessions](docs/sessions.md): runs and their endings, limits, events, durable conversations,
  partial turns, context compaction, failures, resource lifecycle.
- [Tools, permissions and hooks](docs/tools-and-permissions.md): writing tools, `CommandGrant`,
  the workspace file tools on any corpus, permission rules and modes, hook phases.
- [Subagents and skills](docs/subagents-and-skills.md): the `Agent` tool, Agent Skills in a session
  and `helios-skills`.
- [Providers and models](docs/providers.md): reasoning, Anthropic prompt caching and list prices,
  Gemini files, video and privacy controls, cost and budgets, structured output, streaming, fault
  tolerance.
- [Observability and persistence](docs/observability-and-persistence.md): event-based observation,
  OpenTelemetry spans and their attributes, PostgreSQL trace, session, prompt and annotation stores.
- [Sandboxed execution and embeddings](docs/sandbox-and-embeddings.md): `helios-repl` and
  `helios-onnx`.
- [HTTP surface](docs/http.md): routes, questions and approvals, authentication and limits.
- [Security](docs/security.md): secret redaction, the sandbox's layers and deployment, workspace
  confinement.

## Production readiness

Helios runs in production. What is hardened:

- **Fail-secure HTTP.** `RuntimeServer` binds `127.0.0.1` by default, authenticates every request
  and serves a principal only its own sessions; unauthenticated serving is refused off loopback.
  Every request is admitted against finite bounds. See [Authentication and limits](docs/http.md#authentication-and-limits).
- **One secret registry per session**, applied where tool output enters it, before events, hooks
  and history. See [Secret redaction](docs/security.md#secret-redaction).
- **A layered code sandbox**: a separate JVM per session with a private RPC socket, a bytecode
  policy and descendant reaping, under an OS boundary you provide. See
  [Sandbox security model](docs/security.md#sandbox-security-model).
- **Confinement on every filesystem path** the framework opens: descriptor-based workspace I/O,
  checked model-download paths, a validated SQL schema name. See
  [Workspace confinement](docs/security.md#workspace-confinement).
- **Errors are not swallowed.** An `Error` on any thread a session runs work on ends the session
  and fails the run's future with it; it is never retried or turned into a tool result. See
  [Failures](docs/sessions.md#failures).
- **A slow reader never stalls the loop**: each event stream has its own bounded buffer and marks
  what it missed. See [Events](docs/sessions.md#events).
- **Test coverage.** One JaCoCo gate in the root `pom.xml` holds every library module at 95%
  instruction and 90% branch coverage, from the offline suite. Live provider tests run only when a
  release is cut, and a failing one stops the release.

### Known limitations

- **No compatibility guarantee.** A release, a patch release included, can change the public API
  (3.1.2 removed `Usage.of` with four and five counts and the four-rate `Pricing.ofUsdPerMillion`);
  each change is listed in [`CHANGELOG.md`](CHANGELOG.md). Pin a version.
- **Trace payloads are stored verbatim unless you set a redactor.** See
  [What the trace tables hold](docs/observability-and-persistence.md#what-the-trace-tables-hold).
- **A truncated response ends the run.** `FinishReason.LENGTH` ends it in `ErrorDuringExecution`
  of kind `max-tokens` rather than re-issuing the turn; raise `ModelConfig.maxOutputTokens` instead.
- **Strict file confinement is Linux-only**, and is not a snapshot: hard links, bind mounts and
  case-insensitive filesystems are out of scope. See [Workspace confinement](docs/security.md#workspace-confinement).
- **The code sandbox is not an OS boundary.** Its child JVM runs as your user, in your namespaces;
  untrusted snippets need a container or VM around it.
- **Live coverage is yours.** Provider tests with real keys run only at release; add live tests for
  the workloads that matter to you before relying on them in production-critical paths.

If one of these rules Helios out for you, open an issue.

## Design principles

- **No magic**: explicit wiring, no annotation-driven dependency injection.
- **Records and sealed types**: immutable data and pattern matching (`Result<T>`, `QueryEvent`,
  `ResultMessage`, `HookOutcome`).
- **Builders**: `with`-prefixed methods, from a static `newBuilder()` (or `builder()`).
- **JPMS modules**, with providers registered through the `ServiceLoader` SPI.
- **Currency is integer micro-USD** (`long`), `BigDecimal` only at the display boundary.

## Building

```bash
mvn package
mvn spotless:apply       # format (Google Java Format, 2-space indent)
./mvnw -B clean verify   # what CI runs: tests, coverage and the quality gate
```

### Contributing

`mvn verify` enforces a quality gate on main and test code: PMD complexity, size and coupling
limits, CPD duplication detection across modules, ArchUnit architecture rules and a Javadoc gate. A
change that breaks it is fixed in the code: exclusions are never added and rules are never
suppressed in source. The limits, the commands and the policy are in the
["Quality gate" section of `AGENTS.md`](AGENTS.md#quality-gate). Every Java block in this README and
in `docs/` is compiled by the build (see `QuickStartsDocsTest`), and every relative link is checked.

## License

[MIT](LICENSE)
