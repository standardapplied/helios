# Sandboxed execution and embeddings

Two optional modules. `helios-repl` runs Java snippets the model writes in a JShell sandbox, a
separate JVM per session, behind the session's `Execute` tool; the sandbox reaches your application
only through host functions you register. `helios-onnx` computes vector embeddings locally with
ONNX Runtime. The sandbox's threat model and a least-privilege deployment recipe are in the
[security guide](security.md#sandbox-security-model). Back to the [README](../README.md).

- [Sandboxed code execution](#sandboxed-code-execution)
- [The scripting prelude](#the-scripting-prelude)
- [Sandbox lifecycle](#sandbox-lifecycle)
- [Embeddings](#embeddings)

## Sandboxed code execution

A session reaches `helios-repl` through the `Execute` tool with `runtime: JSHELL`: wire
`JShellExecutionProvider` as the session's execution provider, which registers `Execute` over it.
Each session gets its own sandbox, so variables, imports and classes persist across its `Execute`
calls. The sandbox has no built-in host functions; it reaches the host only through the
`HostFunction`s you register on `ReplConfig`:

<!-- snippet: SandboxSnippets#hostFunction -->
```java
var quote =
    new HostFunction(
        "marketQuote",
        "Current price of a ticker",
        List.of(HostParameter.required("ticker", ParameterType.STRING, "Ticker symbol")),
        params -> prices.latest((String) params.get("ticker")));

try (var executionProvider =
        JShellExecutionProvider.create(
            ReplConfig.newBuilder().withHostFunction(quote).build());
    var session =
        AgentSession.open(
            SessionOptions.newBuilder()
                .withModel(model)
                .withExecutionProvider(executionProvider)
                .withPermission(Permission.lockedDown())
                .build())) {
  session.runBlocking(UserMessage.text("What is AAPL trading at?"));
}
```

The model calls `Execute` with `{"runtime": "JSHELL", "script": "println(marketQuote(\"AAPL\"))"}`
and gets back the snippet's exit code, stdout and stderr. Tell the model what the sandbox offers in
your system prompt: `SandboxPrelude.modelFacingSummary()` describes the built-in helpers, and
`SandboxPrelude.formatSignature(quote)` renders a host function's signature as the model calls it.
Credentials never enter the sandbox: a host function runs in the host process and returns only what
it chooses to.

A snippet runs under `ReplConfig`'s execution timeout (30 s by default). The subprocess is
configured by a `JvmSandboxConfig`, passed as `ReplConfig.newBuilder().withSandboxFactory(JvmSandbox.factory(config))`:
its heap (`maxHeapMb`, 256 MB by default), its bytecode `SandboxPolicy` (`SandboxPolicy.noEgress()`
is the preset for compute with no egress) and its `stopGrace` (5 s by default). A snippet that
outlives its timeout is stopped through `jshell.stop()`, repeated until it and every thread it
started have ended; one still running after `stopGrace` cannot be reached inside the JVM, so the
sandbox answers the execute and exits with code 3.

## The scripting prelude

Every sandbox boots with a JShell preamble that adds standard imports (`java.util.*`,
`java.util.stream.*`, `Collectors`, `java.util.function.*`, `java.util.regex.*`, `java.io.*`,
`java.math.*`, `java.time.*`), free `print`, `println` and `printf` (no `System.out`), and ten
script-style helpers: `sum`, `sumInts`, `mean`, `max`, `min`, `join`, `filter`, `map`, `sorted` and
`countBy`. So `println(sumInts(numbers))` replaces
`System.out.println(numbers.stream().mapToLong(Number::longValue).sum())`.

Each host function you register gets a typed static JShell wrapper in the prelude: the
`marketQuote` function above, with its one `STRING` parameter, is callable as
`marketQuote("AAPL")` from the snippet. The one reserved name is `__call`.

## Sandbox lifecycle

`ReplSession` is `AutoCloseable` and owns its sandbox subprocess; `JShellExecutionProvider` creates
one per session, and its own `close()` destroys every one still live. `JvmSandbox` also installs a
JVM shutdown hook, so a
leaked sandbox is killed when the host JVM exits. Both the explicit close and the shutdown hook
snapshot the subprocess's descendants and forcibly destroy them before the parent, so snippets that
call `Runtime.exec(...)` cannot leave orphaned grandchildren past the sandbox's lifetime.

## Embeddings

`helios-onnx` computes vector embeddings with ONNX Runtime. Models download from Hugging Face on
first use and are cached locally (`EmbeddingConfig.defaults()` caches under `~/.helios/models/`).

<!-- snippet: SandboxSnippets#embeddings -->
```java
try (var model =
    EmbeddingProvider.resolve(OnnxModelId.NOMIC_EMBED_V1_5.id(), EmbeddingConfig.defaults())) {
  float[] vector = model.embed("A man is eating food.").getOrThrow();
}
```

| `OnnxModelId` | Dimensions | Architecture | Max tokens |
|---|---|---|---|
| `NOMIC_EMBED_V1_5` | 768 | encoder | 8,192 |
| `EMBEDDING_GEMMA_300M` | 768 | decoder | 2,048 |
| `HARRIER_OSS_V1_270M` | 640 | multilingual decoder | 32,768 |
| `HARRIER_OSS_V1_0_6B` | 1,024 | multilingual decoder | 32,768 |

Each model ships with default query and document prefixes; pass a custom prefix per call when a
different task needs different instructions, or `null` for the model's default:

<!-- snippet: SandboxSnippets#embeddingPrefixes -->
```java
model.embedQuery(
    query, "Instruct: Given a brief professional summary, find the matching profile\nQuery: ");
model.embedDocument(doc, null);
```
