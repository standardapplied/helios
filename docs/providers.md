# Providers and models

Every provider module (`helios-anthropic`, `helios-gemini`, `helios-openai`) implements the same
`Model` interface from `helios-core`, so the rest of your code does not change when the provider
does. This guide covers what is common to all of them (reasoning, cost and budgets, structured
output, streaming, fault tolerance) and what is provider-specific: Anthropic's prompt caching and list prices, and
Gemini's Files API, video and privacy controls. The [README](../README.md#a-model-call) shows a
first model call; sessions, which drive a model for you, are in the [sessions guide](sessions.md).

- [Providers](#providers)
- [Reasoning](#reasoning)
- [Anthropic](#anthropic)
- [Gemini](#gemini)
- [Cost and budgets](#cost-and-budgets)
- [Structured output](#structured-output)
- [Streaming](#streaming)
- [Fault tolerance](#fault-tolerance)

## Providers

| Module | Provider class | API | Catalogue |
|---|---|---|---|
| `helios-anthropic` | `AnthropicProvider` | Messages API | `AnthropicModelId` |
| `helios-gemini` | `GeminiProvider` | Interactions API | `GeminiModelId` |
| `helios-openai` | `OpenAIProvider` | Responses API | `OpenAIModelId` |

`provider.create(modelId, config)` returns a `Model`; `ModelConfig.newBuilder()` (or
`ModelConfig.of(apiKey)`) holds the API key and the request settings. `ModelConfig.toString()`
elides the API key and header values. A model is `AutoCloseable`; see
[Resource lifecycle](sessions.md#resource-lifecycle) for who closes it.

## Reasoning

`ModelConfig.reasoning` says what a reasoning model should do; leave it unset and Helios sends no
thinking, reasoning or effort field, so the provider's defaults apply. `Reasoning.Off` stops
reasoning; `Reasoning.Effort(level, display)` reasons at `MINIMAL`..`MAX` and returns the reasoning
`HIDDEN`, as a `SUMMARY`, or only as the `PROGRESS` notes the model writes between tool calls.

<!-- snippet: ProviderSnippets#reasoning -->
```java
var config =
    ModelConfig.newBuilder()
        .withApiKey(System.getenv("ANTHROPIC_API_KEY"))
        .withReasoning(new Reasoning.Effort(Reasoning.Level.MEDIUM, Reasoning.Display.PROGRESS))
        .build();
var model = new AnthropicProvider().create(AnthropicModelId.CLAUDE_OPUS_5_5.id(), config);
```

Each provider's model catalogue declares what a model accepts, and the request is exactly what was
asked or `create` throws, naming the model, the rejected value and the accepted ones: `Off` on Opus
5.5 (which always thinks), `MAX` on gpt-5.5, `PROGRESS` on any model without progress notes,
`temperature` on any catalogued Claude model. Nothing is clamped or silently dropped. An Anthropic
or OpenAI model id the catalogue does not know is sent whatever the provider can spell, and the API
judges it.

## Anthropic

On Claude models that bind thinking to its prompt (Fable 5.2, Mythos 5.2, Fable 5.1, Mythos 5.1,
Opus 5.5, Sonnet 5.5, Haiku 5.5), a changed system prompt or tool set costs the earlier turns'
reasoning, and the prompt cache, once, never the request.

### Prompt caching and list prices

Anthropic models use five-minute prompt caching by default (`CachePolicy.shortLived()`). Select
the one-hour cache for conversations whose reusable prefixes must survive longer gaps, or disable
caching for one-shot workloads:

<!-- snippet: ProviderSnippets#cachePolicies -->
```java
var provider = new AnthropicProvider();
var config = ModelConfig.of(System.getenv("ANTHROPIC_API_KEY"));

Model longLived =
    provider.create(AnthropicModelId.CLAUDE_OPUS_5.id(), config, CachePolicy.longLived());

Model uncached =
    provider.create(AnthropicModelId.CLAUDE_OPUS_5.id(), config, CachePolicy.disabled());
```

For cost tracking, `AnthropicPricing.calculator(cachePolicy)` prices every catalogued Claude model
at Anthropic's list price, including each model's cache-read rate, the cache-write rate that
matches the policy's TTL, and Haiku 5.5's two prompt-length tiers (over 100,000 prompt tokens, every
rate is five times the shorter tier's):

<!-- snippet: ProviderSnippets#listPrices -->
```java
var options =
    SessionOptions.newBuilder()
        .withModel(longLived)
        .withCostCalculator(AnthropicPricing.calculator(CachePolicy.longLived()))
        .build();
```

`AnthropicPricing.AS_OF` records when the rates were last checked against the pricing page. For
negotiated or partner-platform rates, build a `CostCalculator.staticTable(...)` of rate cards built
with `Pricing.newBuilder()`, each rate in micro-USD per million tokens
(`withInputMicroUsdPerMillion`, `withOutputMicroUsdPerMillion`, `withCacheWriteMicroUsdPerMillion`,
`withCacheReadMicroUsdPerMillion`); input and output are required, and each cache rate defaults to
the input rate. `Pricing.ofUsdPerMillion(input, output)` takes two dollar rates. How a session
spends against a budget is in [Cost and budgets](#cost-and-budgets).

## Gemini

### Files and video

Gemini video understanding uses the Files API for large or reusable videos. Google accepts Files
API video references through the beta Interactions endpoint; the stable endpoint can reject the
same `video` content block. Select `v1beta` before constructing the model (the default is `v1`).
Helios never replays a rejected stable request against beta.

<!-- snippet: ProviderSnippets#geminiVideo -->
```java
var config =
    ModelConfig.newBuilder()
        .withApiKey(System.getenv("GEMINI_API_KEY"))
        .withApiVersion("v1beta")
        .build();

try (var files = new GeminiFilesClient(config);
    var video = files.upload(Path.of("meeting.mp4"), "video/mp4");
    var model = new GeminiProvider().create(GeminiModelId.GEMINI_3_8_FLASH.id(), config)) {
  var message =
      Message.newBuilder()
          .withRole(Role.USER)
          .withContent("Summarize this video and list the decisions.")
          .withFileReferences(List.of(video.awaitActive()))
          .build();

  System.out.println(model.chat(List.of(message), List.of()).content());
}
```

`upload` returns a `GeminiFilesClient.ManagedFile` as soon as the bytes land, before Gemini has
processed the file. `awaitActive()` waits for processing (10 minutes by default, or
`awaitActive(timeout)`) and returns the reference a message carries; it throws when processing
fails or runs out of time. The handle is the caller's: `close()` or `delete()` removes its
validated `files/{id}` resource, including after a failed wait and on exceptional exits from
try-with-resources, and Helios never deletes it on its own. For an `AgentSession`, attach the same
reference with `UserMessage.newBuilder().withFileReference(video.awaitActive())`. Inline media
remains available for small inputs; `GeminiFilesClient` streams uploads without loading video bytes
into the heap.

Keep video traffic on `v1beta` until the credential-gated `GeminiVideoIntegrationTest` also passes
when configured for `v1` with the same model, Files API upload and structured-output schema. Only
then change the configured version; endpoint selection is fixed for each model instance.

### Privacy controls

For privacy-sensitive calls, combine managed deletion, stateless interactions and disabled
raw-output capture:

<!-- snippet: ProviderSnippets#geminiPrivacy -->
```java
var config =
    ModelConfig.newBuilder()
        .withApiKey(System.getenv("GEMINI_API_KEY"))
        .withApiVersion("v1beta")
        .withProviderContinuation(false)
        .withRawOutputCapture(RawOutputCapturePolicy.DISABLED)
        .build();

try (var files = new GeminiFilesClient(config);
    var media = files.upload(Path.of("private-video.mp4"), "video/mp4");
    var model = new GeminiProvider().create(GeminiModelId.GEMINI_3_8_FLASH.id(), config)) {
  var input =
      Message.newBuilder()
          .withRole(Role.USER)
          .withContent("Analyze the video.")
          .withFileReferences(List.of(media.awaitActive()))
          .build();
  var response = model.chat(List.of(input), List.of());
}
```

`withProviderContinuation(false)` resends local history and neither sends nor returns Gemini
interaction IDs. With continuation on (the default), a changed system prompt or tool set, or an
interaction that expired or was deleted, costs one resend of the whole conversation, never the
request. `RawOutputCapturePolicy.DISABLED` keeps raw model text out of structured-output exceptions
while preserving actionable schema and submit-validator corrections. The effective Interactions
version is reported in response metadata under `gemini.apiVersion`; base URLs and credentials are
excluded from that diagnostic. When `withBaseUrl(...)` is also set, it supplies the complete
endpoint prefix and takes precedence over the version path.

A response Gemini blocks for recitation ends with `FinishReason.CONTENT_FILTER`; a session ends the
run in `Refusal` with Gemini's message.

The Gemini provider logs one line, at INFO, when it resends a conversation, naming the interaction
that was gone; it does not log request bodies, uploaded bytes, file URIs, API keys, prompts,
responses or thinking text. Application code must apply the same discipline to exceptions and model
responses. Managed deletion requests early removal, but provider-side processing and retention
remain controlled by Google; consult Google's current data and retention terms for the deployed
account and region.

## Cost and budgets

A session prices every model call with its `CostCalculator`, reports the cost on each
`ResultMessage`, and stops a run at its budget:

<!-- snippet: QuickStarts#budgetCap -->
```java
var pricing = Map.of("claude-sonnet-5-5", CostCalculator.Pricing.ofUsdPerMillion(2.0, 10.0));
var limits =
    SessionLimits.newBuilder()
        .withMaxBudgetMicroUsd(5 * CostEstimate.MICRO_USD_PER_USD) // $5
        .build();

try (var session =
    AgentSession.open(
        SessionPresets.workspace(model, root)
            .withCostCalculator(CostCalculator.staticTable(pricing))
            .withLimits(limits)
            .build())) {
  var terminal = session.runBlocking(UserMessage.text("..."));
  if (terminal instanceof ResultMessage.ErrorMaxBudgetUsd b) {
    System.out.println("Budget exceeded at " + (b.microUsdSpent() / 1_000_000.0) + " USD");
  }
}
```

Currency is integer micro-USD (Stripe-style fixed precision): no `BigDecimal` per turn, no
floating-point drift. `CostCalculator.ZERO` is the default, so cost tracking is opt-in, and a
budget needs a calculator: `build()` throws when `maxBudgetMicroUsd` is set without one.
`CostCalculator.staticTable(Map<String, Pricing>)` builds one from your rates, and
[`AnthropicPricing`](#prompt-caching-and-list-prices) prices every catalogued Claude
model. A price table never prices a model it does not hold at zero: the call throws, naming the
id, and the run ends in `ErrorDuringExecution`, so list every model the session calls, a compaction
summary model included.

Every model attempt is priced at the usage its provider reported, a failed or retried one
included; Helios estimates nothing. A transient failure is not retried once the run is over its
budget, so a run overshoots it by at most the attempt that crossed it. A call cut by `interrupt()`,
`close()` or the wall clock counts what its provider reported before the cut: on Anthropic the
input and cache tokens reported at the stream's start, and the output tokens it had reported, which
it usually reports only as the response ends; Gemini's usage if an event before the end carried it;
nothing for OpenAI, which reports usage only when the response ends.

## Structured output

`OutputSchema.of(MyRecord.class)` generates a JSON Schema from a Java record. The model returns a
typed value, validated against the schema; a shape mismatch surfaces as
`StructuredOutputParseException` with a per-field diff.

<!-- snippet: ProviderSnippets#structuredOutput -->
```java
record Sentiment(String label, double confidence) {}

var response = model.chat(messages, List.of(), OutputSchema.of(Sentiment.class));
Sentiment s = response.parsed();
```

For provenance-tagged output, where every field carries source citations and a confidence, wrap
with `OutputSchema.provenancedOf(MyOutput.class)`. `result.output()` is the typed output and
`result.provenance()` the per-field sources and confidence:

<!-- snippet: ProviderSnippets#provenanced -->
```java
var schema = OutputSchema.provenancedOf(MappingProposal.class);
var response = model.chat(messages, List.of(), schema);
Provenanced<MappingProposal> result = response.parsed();
```

`ProvenanceValidator.DEFAULT` rejects `MEDIUM` and `HIGH` confidence entries that have no sources,
so the model cannot mark every field `HIGH`. Custom validators go through
`OutputSchema.provenancedOf(MyOutput.class, validator)`.

Through `session.runBlocking(message, schema)`, the session loop catches
`StructuredOutputParseException`, adds a corrective user turn carrying the diff, and goes on, within
`maxTurns`.

## Streaming

A session's `events()` stream is the primary streaming surface; see [Events](sessions.md#events).
For direct model use without a session:

<!-- snippet: ProviderSnippets#streaming -->
```java
try (var stream = model.chatStream(messages, tools)) {
  while (stream.hasNext()) {
    switch (stream.next()) {
      case ModelChunk.TextDelta d -> System.out.print(d.text());
      case ModelChunk.ToolUseStop stop ->
          System.out.println("Called: " + stop.toolCall().name());
      case ModelChunk.MessageStop stop -> System.out.println("\n" + stop.finishReason());
      default -> {}
    }
  }
}
```

Nothing is sent before the first `hasNext()`; each chunk arrives as the provider streams it.
`close()`, from any thread, aborts the request and closes its connection without closing the
model. A failed turn throws its exception from `hasNext()`. `model.chat(messages, tools)` drains
the same stream (`ChunkDrain.drain`). A model you write that can only answer blockingly implements
`BlockingModel`.

## Fault tolerance

Composable retry, circuit breaker and timeout, with no dependencies:

<!-- snippet: ProviderSnippets#faultTolerance -->
```java
var ft =
    FaultTolerance.newBuilder()
        .withRetry(
            RetryPolicy.newBuilder()
                .withMaxAttempts(3)
                .withBackoff(Backoff.exponential(Duration.ofMillis(500), 2.0))
                .build())
        .withCircuitBreaker(
            CircuitBreaker.newBuilder()
                .withFailureThreshold(5)
                .withHalfOpenAfter(Duration.ofSeconds(30))
                .build())
        .withOperationTimeout(Duration.ofMinutes(5))
        .build();

var result = ft.execute(() -> model.chat(messages, List.of()));
```

`execute` declares the envelope's checked exceptions (`OperationTimeoutException`,
`CircuitBreakerOpenException`, `RetryExhaustedException`, `InterruptedException`). `ft.withoutRetry()`
returns a sibling envelope with the retry policy stripped and the circuit breaker and timeout kept,
for an operation that must not run twice. The session loop does not use it for tools: it runs every
tool call exactly once.
