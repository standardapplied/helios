# Observability and persistence

A session reports what it does in two ways: its event stream, read live by your code, and
OpenTelemetry spans that follow the GenAI semantic conventions. `helios-persistence` stores those
spans in PostgreSQL and reads them back as traces, alongside annotations, prompt versions and
session stores, all through Helidon DbClient. This guide covers both and what the stored rows hold.
Back to the [README](../README.md).

- [Events](#events)
- [Tracing](#tracing)
- [Host spans and labels](#host-spans-and-labels)
- [Persistence](#persistence)
- [Annotations](#annotations)
- [What the trace tables hold](#what-the-trace-tables-hold)

## Events

The session's `events()` stream is the primary observability surface. Read an `EventStream` on a
thread of your own (or register an `OnStreamEventHook` for in-loop observation) and pick the events
you care about from the sealed `QueryEvent` hierarchy; the delivery rules are in
[Events](sessions.md#events).

<!-- snippet: ObservabilitySnippets#auditEvents -->
```java
try (var events = session.events()) {
  for (var ev : events) {
    switch (ev) {
      case QueryEvent.ToolUse t ->
          log.info("tool: {} args={}", t.call().name(), t.call().arguments());
      case QueryEvent.ContextWarning w -> metrics.recordContextFill(w.usagePct());
      case QueryEvent.RunEnded e -> auditSink.flush(e.result());
      default -> {}
    }
  }
}
```

For HTTP clients, `helios-runtime` serves the same events as Server-Sent Events under
`GET /sessions/{id}/events`; see the [HTTP guide](http.md).

## Tracing

A session emits live OpenTelemetry spans through `withOpenTelemetry` (default
`GlobalOpenTelemetry`, a no-op without an SDK), following the GenAI semantic conventions: an
`invoke_agent` span per run, a child of the span current at `send`, and under it a `chat` span per
model attempt, an `execute_tool` span per tool call and a `hook` span per hook decision, each current
while its step runs.

- Each step span carries `helios.turn.index`, the `turnIndex` of the events it is built from (`1`
  for a run's first model turn, `0` for a hook that fires before it), so a reader joins events to
  spans and tells which model call asked for which tool.
- A `chat` span carries `gen_ai.response.model` when the provider reported the model that served the
  call, which `Response.metadata()` holds under `Response.RESPONSE_MODEL_KEY`, and
  `helios.model.first_chunk_ms` and `helios.model.first_text_ms`, whole milliseconds from the
  attempt's start to its first chunk of any kind and to its first text, each absent when the attempt
  had none; tokens per second is yours to derive.
- A `chat` span carries its attempt's usage when the provider reported tokens, and the run span the
  run's totals: `gen_ai.usage.input_tokens` (cache tokens included) and
  `gen_ai.usage.output_tokens`; `gen_ai.usage.cache_read.input_tokens`,
  `gen_ai.usage.cache_creation.input_tokens` and `gen_ai.usage.reasoning.output_tokens` when not
  zero (reasoning tokens are a part of the output tokens, `Response.Usage.reasoningTokens()`); and
  `helios.cost.micro_usd` when there is a cost.
- A compactor's summary call is a `chat` span with `helios.compaction` = `true` and no turn index.

`TraceDetail.STANDARD` (the default) records no content; `withTraceDetail(TraceDetail.VERBOSE)` adds
prompts, answers, tool arguments and results, redacted. A span call that throws a
`RuntimeException` is logged and changes no run. `PgSpanExporter` stores the spans for
`PgTraceStore`; your application adds `io.opentelemetry:opentelemetry-sdk`:

<!-- snippet: ObservabilitySnippets#tracing -->
```java
var tracing =
    SdkTracerProvider.builder()
        .addSpanProcessor(BatchSpanProcessor.builder(new PgSpanExporter(pgConfig)).build())
        .build();
options.withOpenTelemetry(OpenTelemetrySdk.builder().setTracerProvider(tracing).build());
```

## Host spans and labels

`withTraceAttributes(Map.of("app.eval.case_id", "c-17"))` labels every run span of the session;
each key starts with `app.` (any other key throws), so a label never replaces a `helios.*` or
`gen_ai.*` attribute. To record what your application made of a run's answer (what it rendered, a
fallback, a verification), end a span of your own that names the run: `PgSpanExporter` stores any
span carrying `helios.run.id` as one of that run's spans, of kind `host`, through the same redactor,
and ignores every other span.

<!-- snippet: ObservabilitySnippets#hostSpan -->
```java
var span = tracer.spanBuilder("answer").startSpan();
try (var ignored = span.makeCurrent()) {
  var result = session.send(message).join();
  span.setAttribute("helios.run.id", result.runId());
  span.setAttribute("app.reply.fallback", render(result));
} finally {
  span.end();
}
```

`traceStore.findById(runId).spans()` returns that span with the run's own, in start order, its
`kind()` `host` and its `scope()` your tracer's instrumentation scope.

`PgTraceStore.list` selects runs by label through SCIM's JSON containment,
`attributes eq "${\"app.eval.case_id\":\"c-17\"}"`: build the JSON object with a JSON library, then
escape each `\` and `"` in it for the filter text.

## Persistence

`helios-persistence` holds PostgreSQL-backed prompt versions, session stores and trace tables.
`PgSpanExporter` writes a session's spans as they end (a step's row before its run's),
`PgTraceStore` reads them back by run id (`findById`, `list`, and `summarize`, grouped by name or
model), `PgAnnotationStore` attaches notes to a run id or a span id, `PgPromptRegistry` versions
prompts and `PgSessionStore` stores conversations (see
[Storing a conversation](sessions.md#storing-a-conversation)). All share a `PgConfig` carrying the
`DbClient` and the schema name (default `public`). A run the process died in leaves its span rows
without a run row: `findById` returns null for it, and nothing cleans the rows up.

<!-- snippet: ObservabilitySnippets#persistence -->
```java
var pgConfig = PgConfig.newBuilder().withDbClient(dbClient).withSchema("helios").build();

var spanExporter = new PgSpanExporter(pgConfig);
var traceStore = new PgTraceStore(pgConfig);
var annotationStore = new PgAnnotationStore(pgConfig);
var promptRegistry = new PgPromptRegistry(pgConfig);
var sessionStore = new PgSessionStore(pgConfig);
```

The schema lives on the classpath at `com/standardapplied/helios/persistence/schema.sql`; run it
against your database to create the `helios_*` tables. It only creates tables that are missing, so
an upgrade also runs the release's DDL delta from [`CHANGELOG.md`](../CHANGELOG.md). The schema
name is applied to all generated SQL and validated against Postgres' unquoted-identifier shape
(`[A-Za-z_][A-Za-z0-9_]{0,62}`) when `PgConfig` is built.

**Prompt drafts.** `PromptRegistry.registerDraft(name, content)` creates the next version inactive,
leaving the active version live; `activate(name, version)` promotes or rolls back explicitly and
throws on an unknown target; `resolve(name, version)` pins a version.

## Annotations

`persistence.Annotation` is a structured note attached to a stored run or span (`subjectId`, the
run id or the span id). An optional `facet` addresses a named sub-coordinate within the subject, so
one author can hold several judgments about one subject (one rating per evaluation dimension, say)
without minting synthetic ids; `authorKind` (`HUMAN`, `MODEL` or `SYSTEM`) types the author and
`metadata` (a `jsonb` map) carries opaque consumer context. `PgAnnotationStore.upsertAnnotation`
persists the full record and is idempotent on `(subjectId, facet, label, authorId)`. Read through
the same store with `findAnnotationsBySubject(id)`, `findAnnotationsBySubjects(ids)` (batch) or
`listAnnotations(paginate, scimFilter)` (SCIM over the first-class columns). `facet`, `label` and
`metadata` are opaque to Helios: it stores and returns them without interpretation.

## What the trace tables hold

The trace payloads (`helios_traces.{input_text, output_text, attributes}`,
`helios_spans.attributes`) are stored exactly as given unless `PgConfig.Builder.withRedactor(registry.redactor())`
is set, in which case `PgSpanExporter` runs every span's name, error description, message text and
attribute value through the `Redactor` before it reaches the database. That faithful capture is
what makes the trail useful for evals, debugging and later analysis. A session's spans carry
content only under `TraceDetail.VERBOSE`, already redacted by the session against its
`SecretRegistry`; the exporter's redactor is a second pass, for values that registry does not hold.
`PgSessionStore` applies no redactor, the configured one included: it stores the conversation the
model received, which the session already redacted, and redacting it again would change replayed
messages that a provider's signed reasoning is bound to.
