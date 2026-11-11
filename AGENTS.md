# Helios

Production-grade agentic framework for Java. Simple, explicit, no magic.

## Project Info

- **Java**: 25 | **Build**: Maven | **License**: MIT
- **Group ID**: `com.standardapplied`

## Philosophy

1. **No magic** - Explicit wiring, no annotation-driven DI
2. **Idiomatic Java** - Records, sealed types, pattern matching
3. **Production from day 1** - Fault tolerance, observability, one coverage gate (95% instruction / 90% branch) on every library module

## Libraries

- **Jackson 3.x** for JSON ([migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md))
- **JUnit 6.x** for testing ([docs](https://docs.junit.org/6.0.2/overview.html))
- **Helidon SE 4.5.x** for HTTP / persistence modules ([DbClient docs](https://helidon.io/docs/v4/se/dbclient))

## Coding Conventions

- **JDK records and classes, immutability first.** Records for all value types; mutable state only in clearly-named stateful classes. Records hold `List`/`Map`/`Set` from `.of()` factories, never exposed mutable collections.
- Records with static Builder class, `with` prefix for builder methods
- No `get` prefix for accessors
- 2-space indent, 4-space continuation
- No inline comments; use Javadocs for public API
- Use `var`, `List.of()`, `Map.of()`
- DO NOT use wildcard imports, ever!
- Copyright header: `/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */`
- **SOLID and DRY are non-negotiable.** Single Responsibility (one paragraph of Javadoc per class), Open/Closed (extension via interfaces, not modification), Liskov (enforced by sealed hierarchies), Interface Segregation (no omni-interfaces with optional methods), Dependency Inversion (external collaborators are interfaces). Don't copy-paste — extract a helper.
- **No God classes.** Hard rule: no class exceeds **1000 lines, excluding Javadoc comments and imports**. Hitting the budget is the signal to extract, not to ask for an exception.
- **A time seam is a `java.time.InstantSource` set through `withClock(...)`, defaulting to `Clock.systemUTC()`.** Never a `Clock` field, parameter or return type, never a static `now()`. See `CircuitBreaker.Builder`.
- **Currency is integer micro-USD (long), never BigDecimal on the hot path.** Stripe-style fixed-precision. See `CostEstimate` + `CostCalculator`.

**CRITICAL** Talk to me before making design decisions

## Quality gate

`mvn verify` fails on complexity, duplication and architecture violations, for main and test
code, and on any Javadoc warning in the main code of a shipped module. The burn-down of what
violated when the gate was installed is complete: from here on a violation is fixed, never
excluded.

| Tool | Enforces | Where |
|---|---|---|
| PMD (`maven-pmd-plugin`, every module) | Per-method and per-class complexity, size and coupling limits; no wildcard imports; in test code, no sleep, piped stream, clock read or self-chosen wait limit | `config/quality/pmd-main.xml`, `config/quality/pmd-test.xml` |
| CPD (same plugin, aggregated at the reactor root) | No duplicated block of 100 tokens or more, main and test code, within or across modules | plugin configuration in the root `pom.xml` |
| ArchUnit (`architecture` module) | One way to do each thing: module dependencies, naming, single owners of a pattern | `architecture/src/test/java/.../ArchitectureRulesTest.java` |
| Javadoc doclint (`maven-javadoc-plugin`, every shipped module; not `architecture` or the examples) | Doclint `all` on main code: every public and protected type and member documented, every `@param`, `@return` and `@throws`, every `{@link}` resolving, well-formed HTML; a warning fails the build | plugin configuration in the root `pom.xml` |

### Limits

"Fails at" is the first measured value that breaks the build.

| PMD rule | Measures | Fails at |
|---|---|---|
| `CognitiveComplexity` | Cognitive complexity of a method | 15 |
| `CyclomaticComplexity` | Cyclomatic complexity of a method | 15 |
| `CyclomaticComplexity` | Summed cyclomatic complexity of a class; main code only | 80 |
| `NPathComplexity` | Acyclic execution paths through a method | 200 |
| `AvoidDeeplyNestedIfStmts` | Depth of nested `if` statements | 3 |
| `NcssCount` | Statements in a method | 60 |
| `NcssCount` | Statements in a class — the "no class over 1000 lines" rule, at about 0.6 statements per code line | 600 |
| `ExcessiveParameterList` | Parameters of a method or constructor | 10 |
| `TooManyFields` | Non-static, non-final fields of a class | 16 |
| `CouplingBetweenObjects` | Distinct types a class uses in fields, locals and return types | 21 |
| `SwitchDensity` | Statements per `switch` label | 10 |
| `GodClass` | Weighted methods (WMC ≥ 47) with foreign data access (ATFD > 5) and low cohesion (TCC < 1/3); main code only | all three |
| `TooManyMethods` | Methods of a class, not counting `get*`, `set*`, `is*` and `with*`; main code only | 26 |
| `OnDemandImport` | `import a.b.*` and `import static a.B.*` | 1 |

A test class is expected to have many methods and a large summed complexity, so the three "main
code only" rules are absent from `pmd-test.xml`; everything else applies to tests unchanged.
PMD resolves types against each module's classpath, and a file it cannot analyse fails the build.

Four more rules apply to test code only. Each fails at the first occurrence.

| PMD rule (tests) | Forbids | Do this instead |
|---|---|---|
| `SleepInTest` | `Thread.sleep`, `TimeUnit.sleep`, `LockSupport.parkNanos` / `parkUntil` | Wait for the event through `Await` |
| `PipedStreamInTest` | Constructing `PipedInputStream`, `PipedOutputStream`, `PipedReader`, `PipedWriter` | `LineSink` for what the code under test writes, `FeedableInputStream` for what it reads |
| `ClockReadInTest` | `System.nanoTime()`, `System.currentTimeMillis()` | Assert the outcome; a test does not measure elapsed time |
| `TimedWaitInTest` | Any call passing a `TimeUnit`; `Thread.join` or `Object.wait` with an argument; `assertTimeout`, `assertTimeoutPreemptively` | Wait through `Await`, which applies the one hang guard |

`Await` is exempt from the sleep, clock and timed-wait rules by package and class name
(`com.standardapplied.helios.core.test.Await`); a class named `Await` anywhere else is not. A
sleep inside a string of sandboxed code is not a Java call and is not flagged.

ArchUnit rules today: `core` depends on nothing outside the JDK; a provider module depends on
`core` only; a provider module serves its models as `core.provider.StreamingModel` (no provider
class implements `Model`), reads a response stream only through `core.provider.SseReader`
(no `BufferedReader` or `InputStreamReader`) and builds a Jackson mapper only in its
`api.<Provider>Json` holder; a model's reasoning declaration (`*ModelId.reasoning()`,
`ReasoningRules` and `core.provider.ReasoningSupport`) is read only by the provider's
`*RequestBuilder`; `core.provider` depends on no
provider module; `session` does not depend on a provider, `runtime` or `persistence`; the `session` module builds a Jackson mapper only
in `session.loop.SessionJson`; nothing depends on `java.util.concurrent.Flow` or
`SubmissionPublisher`, since a stream is pulled (`core.model.CloseableIterator`,
`session.EventStream`); in `session` outside `files`, `memory` and `execution`, no class
creates an executor (its loop, wall clock and bounded waits run on virtual threads); a thread
that reads or writes a child-process pipe is a platform daemon thread, since a pipe read or write
blocks its carrier and a few dozen on virtual threads starve the scheduler (in `core.process`,
`session.execution` and `repl.sandbox`, no method that starts a virtual thread reads or writes a
stream, in its own body or through a method of its class it calls); a
`java.net.http.HttpClient` is built only by `core.common.HttpClientFactory`, whose clients never
follow a redirect except the unauthenticated download client `createForDownloads()`, which only
the `onnx` module may call; `java.lang.ProcessBuilder` is used only by `core.process` (whose
`BoundedProcess` every other child process goes through) and `repl.sandbox.SandboxLauncher`;
`jdk.jshell` is used only in `repl.sandbox` and its sub-packages, the bootstrap side of the process
boundary; `System.setOut` and `System.setErr` are called only by `repl.sandbox.SnippetEvaluator`; `runtime.Authenticator.authenticate` is called only by `runtime.AuthenticationGate`, and only it accesses `io.helidon.http.Status.UNAUTHORIZED_401`, so every `401` carries the authenticator's challenge (no other runtime class names a status by number through `Status.create` or `ServerResponse.status(int)`, or throws Helidon's `UnauthorizedException`); a public
instance method on a `*Builder` type that returns that builder is named `with...`; no top-level type is
named `*Util`, `*Utils`, `*Helper`, `*Helpers` or `*Manager`; `System.out`, `System.err` and
`printStackTrace` are used only in `repl.sandbox` and the example modules; no class in `core` depends
on OpenTelemetry and no class outside `persistence` on its SDK (the session emits spans through the
API; only the Postgres exporter needs the SDK); nothing in main code
is `@Deprecated` (a superseded type or member is deleted and its replacement recorded under
Breaking in `CHANGELOG.md`). Three rules govern
time: the wall clock is not read statically (no `now()` on a `java.time` type, no
`System.currentTimeMillis()`); `Clock.system*` / `Clock.tick*` appear only as a field's initial
value, never inline in a method; and a time seam is an `InstantSource` (no `Clock` field,
parameter or return type). The one named exception to the first two is `core.common.Ids`,
burned down by `v3-injected-time`. `System.nanoTime()` for measuring a duration is correct and
stays.

### Time and waiting in tests

A test passes or fails for the same reason on a machine a hundred times slower, and on one that
stalls for several seconds at any statement. The fixtures live in
`com.standardapplied.helios.core.test` (core's test sources, shared through core's `test-jar`;
the package must never exist in core's main code; session's equivalent is
`com.standardapplied.helios.session.test`): `Await`, `Uninterruptible` (a hanging fixture's block until released), `PidFile` (a process a child recorded with `echo $$ > pid`), `LineSink`,
`FeedableInputStream`, `StreamRead` (a stream read to its end), and for HTTP clients `StubHttpServer`, `FakeHttpClient`, `HeldSseServer`
(a reply held open part by part, reporting when the client closes the connection) and `RedirectTrap`.

- **Wait for an event, never for time.** Wait on what the other thread produces: a latch, a
  future, a queue element, a state change. Never sleep to let it get somewhere.
- **Wait only through `Await`.** A wait ends with the event or with `Await.HANG_GUARD` (60 s),
  whose expiry is a failure. Never choose a timeout for a wait. `Await.until` (polling) is the
  last resort, for state that offers no event. If `Await` lacks a wait, add it there with a test.
- **Never assert how long something took.** To prove a timeout or a kill fired, make the awaited
  thing unable to finish on its own (block until released, `sleep 600` in a child, an unfed
  `FeedableInputStream`) and assert the outcome. Returning at all is the proof.
- **To test a timeout,** give the code under test a short one and an operation that blocks until
  interrupted or released, and set every other limit that could end the same wait above the hang
  guard.
- **To prove something does not happen,** wait for the event that closes the window in which it
  could have happened, then assert. A pause is not such an event.
- **Drive time-of-day and elapsed-time behaviour with a hand-advanced `InstantSource`.**
  `CircuitBreakerTest` is the model. A thread that sleeps against a monotonic deadline takes a
  package-private seam giving `nanoTime` and `sleep` instead, since an `InstantSource` is wall-clock
  time and cannot wake a sleeper; `IdleWatch` (`IdleWatch.Pace`) is the example.
- **Assert one outcome.** No "either is acceptable", no `if (outcome != null)` around
  assertions. Force the interleaving; where two interleavings are both correct, write two tests.
- **Force an interleaving without relying on scheduling:** a latch the other side waits on, a
  fake that blocks, a hook that runs the second caller at a chosen point.
- **Collect failures from every thread.** Await the `Future` of each submitted task; no empty
  `catch` around code under test.
- **Fakes may block without a limit** (`new CountDownLatch(1).await()`); the test's `Await`
  bounds the test, and the build-wide JUnit default timeout (10 minutes, root `pom.xml`) turns a
  deadlock into a failure.
- **Where a class gives a test nothing to wait on,** give it an event, in this order: an existing
  method completes its work before returning; a listener or future it already exposes; a
  package-private accessor.

### Live tests

A live test (gated by `@EnabledIfEnvironmentVariable` on a provider key) fails only when the
provider rejects a request Helios built, or when Helios mis-reads what the provider sent; never on
what the model chose to write. Helios behaviour that needs a model choice is proven by a recorded
test that runs on every build; the live test that needs that choice skips when the model makes a
different one.

1. **What a live test asserts.** Only facts that hold for any output the model may produce: the
   provider accepted every request (no exception, no `Error` event); Helios turned the response
   into its own types (a finish reason the request allows, non-null content, parsed usage, a
   computed cost); a replayed turn was accepted; a chunk stream completes and ends in exactly one
   `MessageStop`; a parsed value is present where the provider constrains the output (OpenAI
   strict `json_schema`, Gemini `response_format`). `core.test.Accepted` holds these checks.
2. **Force what can be forced.** Force tool use with `ModelConfig.Builder.withToolChoice`:
   on Anthropic only for a model whose `AnthropicModelId.acceptsForcedToolChoice()` is true and
   with no reasoning set; on OpenAI and Gemini with `ToolChoice.required(name)`. If the API rejects
   the forced choice with a test's thinking config, the step cannot be forced (rule 3). A session's
   tool choice applies on every turn, so a forced session ends in `ErrorMaxTurns`; a test that
   forces one accepts that terminal.
3. **Skip what cannot be forced.** A tool call on a model that rejects forced tool use, a progress
   note or thinking text, a citation or grounding, a web search, valid JSON where the provider
   does not constrain it, and the arguments the model passes: check the step with
   `Assumptions.assumeTrue(condition, message)`, whose message names the model, the step it
   skipped and the recorded test that covers it. Every assertion before the assumption is a
   rule-1 fact; assertions that need the step come after it.
4. **Every skip has a recorded counterpart** that runs in `mvn verify` without keys. Provider
   level: a fixture under `golden/<provider>/streams/` in that provider's `*StreamTranscriptTest`.
   Session level: the real provider client against `StubHttpServer` through `AgentSession`, as
   `RecordedSessionTest` does. A multi-turn fixture separates responses with
   `ModelHarness.NEXT_RESPONSE`. Each fixture comes from one live call: keep its event types,
   block order, block indices and field names as recorded; text may be shortened, ids and
   signatures replaced with stable placeholders; never a key or an `Authorization` header. A
   provider with no key on the devbox gets a fixture built from its documented stream shape. The
   test's javadoc states each fixture's model id and recording date in one line, and says when it
   is hand-built.
5. **No wording checks.** Never assert what the model said. Where a wording check stood in for a
   request-shape guarantee, assert the request Helios sent offline, through the requests
   `ModelHarness.exchange` returns (`ConversationRequestContract`).
6. **The provider's cache is not asserted live.** `ToolLoopReplayTest` proves offline that every
   request repeats the prefix the request before it marked for the cache.
7. **No turn bound fails a live test.** A live tool loop stops after the turn it tests (for a replay
   test, the first turn that sends the replayed block), and a session that hits `maxTurns` is an
   accepted terminal unless the test is about that terminal.

`.github/workflows/release.yml` runs every `*IntegrationTest` with real keys in its `live` job,
only when a release is cut (a push to `release/**`, or a manual dispatch), never on `main` or a
pull request: real calls cost money. The `deploy` job needs `live`, so a failing live test stops
the release before anything reaches Maven Central. `deploy` runs no tests: its first step asks the
GitHub API, with a token that reads Actions and contents and writes nothing, for a successful `CI`
run on the commit and refuses the commit without one, so a release branch is pushed at a `main`
commit CI passed. The `live` job deploys to the `live-providers` environment: no required
reviewers, deployment branches "Selected branches" with the rules `main` and `release/*`, and the secrets `ANTHROPIC_API_KEY` and `GEMINI_API_KEY`
(`OPENAI_API_KEY` once a key exists), set only in the "Live tests" step's `env:`. A missing
secret reaches the step empty, and its classes skip with a message naming the variable. Every
run uploads the surefire XML, each pass, failure and skip with its message, as the
`live-test-reports` artifact.

### Running it

```bash
mvn -B clean verify                                                                  # everything, as CI does
mvn -B -T 1 clean verify                                                             # the same, one module at a time, for debugging
mvn -B verify -Dtest=ArchitectureRulesTest -Dsurefire.failIfNoSpecifiedTests=false   # the gate, without the test suite
mvn -B verify -DskipTests                                                            # PMD and CPD only
mvn -B -fae -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage -DtargetClasses=<classes> -DfailWhenNoMutations=false -Dspotless.check.skip=true -Dpmd.skip=true -Dcpd.skip=true   # mutation score, by hand
```

Repl's sandbox code runs in a child JVM that loads the unmutated classes, so no mutant reaches it.

The reactor builds in parallel: `.mvn/maven.config` holds `-T1C`, one thread per core, so `mvn`
and `./mvnw` build modules that do not depend on each other side by side, and their logs
interleave. `-T 1` on the command line overrides it and builds one module at a time, for reading a
log or bisecting a failure. A test that fails only when modules build side by side shares
something with another module's tests (a port, a path, a cache, the machine's load) and is fixed
at that cause; a module is never serialised to hide it.

CI runs the same build through `./mvnw`, which downloads Maven 3.9.14 and checks its SHA-256. The
wrapper needs `unzip`: without it, the script fetches the `.tar.gz` and refuses it because the
checksum is the `.zip`'s. Where Maven 3.9.14 is installed, `mvn` is the same build, since the
enforcer refuses any other version. Never remove `distributionSha256Sum` to make `./mvnw` run.

Never add `-q`: the lines that say what to fix (`PMD Failure: <class>:<line> Rule:<rule> <message>`,
`CPD Failure: Found N lines of duplicated code at locations: ...`) are logged as warnings and
only the summary as an error. Reports land in `target/pmd.xml` (main), `target/pmd-test/pmd.xml`
(tests) and, at the root, `target/cpd/cpd.xml`. CPD is bound to the reactor root and reads the
sources of the modules in the reactor, so only a whole-reactor build checks duplication; a
`-pl <module>` or `-N` build does not.

### Rules of the gate

- **Fix the code, never the gate.** An exclusion is never added, and a limit never raised, to
  make a change pass.
- **Documentation is fixed, never suppressed.** A Javadoc warning is answered by writing the
  comment, never by disabling a doclint group, narrowing the run or skipping a shipped module.
- **No suppression in source.** Neither PMD's suppression comment marker nor a
  `@SuppressWarnings` annotation naming a PMD rule; `git grep` for both stays empty.
- **`config/quality/pmd-exclusions.properties`** (`fully.qualified.Class=Rule1,Rule2`; a nested
  class is `Outer.Nested`; one line per class, because a repeated key replaces the earlier one)
  holds only entries a spec names explicitly, each with a comment stating why.
- **No duplication is accepted.** CPD has no exclusion file; its executions clear the parameter,
  so no class is ever silenced.
- **Adding an ArchUnit rule.** A spec that establishes or consolidates a pattern adds one `@Test`
  method to `ArchitectureRulesTest`, named for the rule, with a `because(...)` clause that states
  the one allowed way. A new library module is added to `architecture/pom.xml` as a test
  dependency so the rules see it.

The rule sets and the exclusion file are resolved through `${maven.multiModuleProjectDirectory}`,
which Maven sets to the nearest ancestor directory holding `.mvn/`, where `.mvn/maven.config`
is checked in. The plugin wiring constraints are commented in the root
`pom.xml`.

## Supply-chain gate

CI inventories every dependency the build resolves and fails on a known advisory against one.

| Piece | Does | Where |
|---|---|---|
| Inventory (`cyclonedx-maven-plugin`, `verify` phase) | Writes two aggregate CycloneDX files at the reactor root, once per build (the executions are not inherited by the modules) | plugin configuration in the root `pom.xml` |
| Scan (OSV-Scanner, pinned and checksum-verified) | Looks every inventoried component up in osv.dev | `config/security/advisory_scan.py`, tested by `advisory_scan_test.py` |
| Exceptions | Silences one advisory until a date | `config/security/advisory-exceptions.toml` |

`target/helios-runtime.cdx.json` is what the deployed modules ship: compile- and runtime-scope
dependencies, direct and transitive, with hashes and licenses. `target/helios-build.cdx.json` adds
test scope and the undeployed `architecture` and example modules. A component that appears only
in the second is never shipped. CI uploads both as the `dependency-inventory-<os>` artifact.

### Policy

- **Severity: every advisory fails, whatever its score, scored or not.** There is no threshold to
  tune; an advisory that does not matter to Helios is recorded as an exception that says why.
- **Reachability: not analysed.** OSV-Scanner has no call analysis for Java, so an advisory counts
  whether or not Helios calls the affected code. Unreachability is an argument made in an
  exception's `reason`, reviewed in the pull request that adds it.
- **Scope: both inventories fail the build.** A finding in `helios-runtime` is shipped exposure
  and is fixed by upgrading. A finding only in `helios-build` is exposure of the build and of
  developer machines, fixed the same way or pinned in the module that resolves it.
- **Fix by upgrading to the lowest release outside the affected range**, in its own commit, with
  a row under Security in `CHANGELOG.md` naming the advisories and the resolved version.
- **Exceptions expire.** An entry needs an `id`, a `reason` and an `ignoreUntil` date at most 90
  days ahead; the scan rejects the file otherwise, and rejects anything but `IgnoredVulns` and any
  other key in an entry, however it is spelled (OSV-Scanner matches keys without regard to case,
  so `IgnoreUntil` would be a second, unchecked expiry), and the date `0001-01-01`, which
  OSV-Scanner reads as no expiry at all. After the date the advisory fails the scan again.
  Entries are removed, never extended without a new review.
- **A scan that could not run is not a clean scan.** `advisory_scan.py` exits 0 (clean), 1
  (advisories, each printed with package, version, severity, fixed versions and URL) or 2 (the
  result cannot be trusted: scanner, network, advisory data, inventory or exception file, or an
  unexpected failure inside the scan, which is printed with its traceback and never exits 1).
  Before every scan it scans `config/security/canary.cdx.json`, which lists pgJDBC 42.7.7, and
  exits 2 unless both of that version's advisories come back. The canary is a file, never a
  dependency.
- **Every inventoried component is looked up, or the scan is not clean.** OSV-Scanner skips a
  component whose package URL is missing, versionless or rejected by its parser without saying
  so in its result, and looks up whatever the package URL names. The scan therefore
  rejects an inventory unless every component carries a `pkg:maven` package URL equal to its own
  group, name and version, and nests no component; and after each scan it compares the packages
  the scanner reports having looked up (`--all-packages`) with the inventory's components, name
  and version, one for one. Any difference exits 2 and names the components not looked up, so two
  components with the same package URL are rejected too. The listing is not enough on its own:
  OSV-Scanner's filter drops a component it deems unscannable (no ecosystem, name or version, or
  a Maven artifact named `unknown`) before matching and then adds it back to the `--all-packages`
  listing, so the scan runs the scanner at `--verbosity info` and exits 2 when it reports having
  filtered any package. Both checks rely on the pinned 2.6.0 behaviour; a scanner upgrade is where
  they are re-verified.
- **A daily scheduled CI run** finds an advisory published against an unchanged dependency.

### Blind spots

The scan matches Maven coordinates against osv.dev. It does not prove the absence of
vulnerabilities, and it cannot see:

- **Native code inside a jar.** `onnxruntime`, DJL `tokenizers` and `jna` carry native libraries.
  The inventory records those jars and their hashes, but an advisory filed against the native
  project rather than the Maven coordinate is not matched.
- **Code shaded into a dependency**, such as the SCRAM client inside pgJDBC, unless the advisory
  is filed against the outer artifact.
- **The JDK** (including `jdk.jshell` and `jdk.compiler`, which the REPL sandbox runs on) and the
  host's libc, which `helios-session` calls through the foreign-function API.
- **Build tooling**: Maven plugins and their dependencies (the `mutation` profile's `pitest-maven`
  and `pitest-junit5-plugin` among them), GitHub Actions, the OSV-Scanner binary itself, and the
  container images tests start (`postgres:17-alpine`).
- **Embedding models** `helios-onnx` downloads at run time.
- **Advisories osv.dev does not hold yet.** The canary proves the service answers and knows two
  2026 advisories, not that it is complete.

### Running it

```bash
mvn -B verify -DskipTests                                                                                 # writes both inventories
python3 config/security/advisory_scan.py target/helios-runtime.cdx.json target/helios-build.cdx.json      # the scan, as CI runs it
python3 config/security/advisory_scan_test.py                                                             # the scan's own tests
```

Needs Python 3.11 or later and network access to `api.osv.dev`; the first run downloads the
scanner from the OSV-Scanner GitHub release into `~/.cache/helios` (Linux x86-64 and AArch64 are
pinned; elsewhere point `OSV_SCANNER` at a binary of the pinned version). Upgrading the scanner
means changing `SCANNER_VERSION` and both checksums in `advisory_scan.py` together.

## Architecture

```
helios/
├── core/                           # Zero deps - Model + Reasoning + tool + common + fault + process + provider + schema + prompt + embedding interfaces. CostEstimate + CostCalculator. Test fixtures (Await, LineSink, FeedableInputStream, FailingInputStream, StubHttpServer, FakeHttpClient, HeldSseServer, RedirectTrap, SseEvents, StreamRead, ExchangeTurns, ChildJvm, and for provider characterization ModelHarness, SseReplies, Transcript, Golden, ConversationFixture, DeclarationOrderFixture, ReasoningMatrix, and for live tests Accepted) and the abstract `*Contract` tests ship as its test-jar.
├── session/                        # v2 SDK - AgentSession, SessionPresets, hooks, permissions, file tools, memory backend, agent loop. Test fixtures (EventCollector, QuestionAnswers, HookInputs, SampleDocuments, SpanCapture in `session.test`) ship as its test-jar.
├── runtime/                        # Helidon HTTP/SSE surface for session — POST /sessions, SSE /events, long-poll /result
├── gemini/                         # Gemini Interactions API + Jackson 3.x
├── anthropic/                      # Claude Messages API + Jackson 3.x
├── openai/                         # OpenAI Responses API + Jackson 3.x
├── repl/                           # Sandboxed JShell behind Execute(JSHELL) (JShellExecutionProvider, JvmSandbox, ReplSession, HostFunction infrastructure)
├── onnx/                           # Local embeddings via ONNX Runtime
├── persistence/                    # PostgreSQL persistence — PgSpanExporter (OpenTelemetry) + PgTraceStore + PgAnnotationStore + PgSessionStore via Helidon DbClient
├── testing/                        # helios-testing — ScriptedModel test double (scripted text, tool-call, response and stream turns) + ModelStreams, for deterministic CI evals
├── architecture/                   # helios-architecture — ArchUnit rules over every library module; build-only, never deployed
├── config/quality/                 # PMD rule sets + PMD/CPD exclusion files shared by every module
├── config/security/                # Advisory scan (OSV-Scanner wrapper + tests), its canary inventory and the exception file
└── examples/
    └── session-demo/               # Reference: full session run against Gemini with a real workspace
```

The v1 surface (`core.agent.Agent`, `core.workflow`, `core.memory`, `core.eval` and the v1
harness family) was deleted in the v2 cut per spec §3.5. The v2 paradigm is a long-lived
`AgentSession` that runs an agent loop on a virtual thread; v1's one-shot `Agent.run(...)` shape no longer exists.

### JPMS Modules

Core exports public API; providers register via ServiceLoader SPI.

## v2 Session SDK (`helios-session` + `helios-runtime`)

| Pattern | Description |
|---------|-------------|
| **AgentSession** | `AgentSession.open(options)` opens a session (a conversation); `send` steers the run in progress or starts the next and returns its `ResultMessage` future; `interrupt()` cancels the run; `events()` reads every run's events; `close()` ends the session. A run is one execution, from the message that starts it to its terminal; `maxTurns`, `maxBudgetMicroUsd` and `maxWallClock` count per run, the history, hooks, permissions, secrets, execution provider and memory per session. A provider that refuses at `open` throws `SessionUnavailableException`. A run that ends with tool calls unanswered answers each with a "not run" tool message. `SessionRuns` holds the lock that `send` and a run's end decision (`loop.RunBoundary`) share |
| **SessionOptions / presets** | Start from `SessionPresets.minimal`/`readOnly`/`workspace(model, path)` or `SessionOptions.newBuilder().withModel(model)`, chain `with*`, `build()`. Tools append with `withTool`, hooks with `withHook`; a memory backend adds `MemoryRead`/`MemoryWrite`, an execution provider adds `Execute` and is started and ended with the session, `withAskUserQuestion()` adds `AskUserQuestion`; the permission policy defaults to asking before write, execute and network. |
| **ToolBinding** | `ToolBinding.newBuilder(tool).withCategory(c)` (READ, WRITE, EXECUTION, NETWORK, CONTROL; the category sets the permission default), optionally `.withPathArgument(...)` so the key is a canonical path, and `.withVisibility(ctx -> ...)` to advertise it only on some turns (a hidden tool still runs if named; refuse with a deny rule or a `PreToolUse` `Block`). Add it with `withTool`. |
| **QueryEvent (sealed)** | Streamed lifecycle event, each naming its `runId`: `RunStarted` first and `RunEnded` last in a run, between them text, thinking, citations, user-message, tool, hook, question, context and turn events, `ModelCallEnded` per model attempt, and `Error`. An `EventStream` spans every run until the session closes; its 256-event buffer misses a routine event at once when full, while `RunEnded`, `QuestionAsked` and `Error` wait up to 30 s for room. Adding a subtype is a deliberate breaking change for `switch` consumers |
| **Hooks + HookOutcome** | Seven phases: `OnUserMessage`, `PreModelTurn`, `PostModelTurn`, `PreToolUse`, `PostToolUse` and `PreStop`, each returning its phase's outcome type, and the observe-only `OnStreamEvent`. Hooks run by priority; the first non-`Continue` outcome wins and is announced by a redacted `HookFired`. `PreToolUse` hooks chain `MutateArgs` and fail closed (throw or `null` = `Block`); other phases treat throw or `null` as `Continue`; an `Error` always escapes |
| **Context compaction** | The loop counts from the provider's last report (`ConversationHistory.anchor`, cleared by a replacement or a turn reporting no input) plus the `TokenCounter` estimate of the messages since. The compactor is skipped while what the estimate cannot see reaches the 0.95 watermark by itself. `ContextCompactor.compact(history, context)` returns a history the `TokenCounter` estimates lower, or it is ignored; the default summarises with the session's model; wrap it to observe or archive; `ContextCompactor.disabled()` never compacts. |
| **Permission system** | `Permission(mode, allow, ask, deny)`, default `empty(DEFAULT)`; a rule is `PermissionRule.of(tool, glob)` or `any(tool)` over the call's canonical key (a path tool's canonical path, `Execute`'s `<RUNTIME>/<script>` plus the extras it sets). The most restrictive matching rule wins (deny > ask > allow), else the mode's default for the tool's category. ASK surfaces as `QuestionAsked`, answered with `session.answer`; dry-run a policy with `permission.evaluate(key, category)`. Keys, globs and walking tools: `Permission` javadoc |
| **Tool calls** | Each tool call runs on its own virtual thread under `toolTimeoutDefault` (an `Agent` call under its subagent's `maxWallClock` plus `stopGrace`), one deadline from dispatch. At the deadline, or when the session is cancelled, the call's token is cancelled and its thread interrupted: stopped within `stopGrace` is `TimedOut` (an `Agent` call: within its subagent's `stopGrace`, what its tool returned), still running is `Unknown`. While a call the session stopped waiting for still runs, every new call is `Rejected`, so a session leaks at most one call. A tool's `Exception` is `Threw`; its `Error` ends the session. |
| **CostEstimate + CostCalculator (long microUSD)** | Currency is integer micro-USD (Stripe-style fixed-precision), never `BigDecimal` on the hot path. `CostEstimate(long microUsd)` in `core.common`; `CostCalculator.cost(modelId, Usage) -> CostEstimate` is the per-turn lookup. `CostCalculator.ZERO` is the opt-in default (no cost tracking); `CostCalculator.staticTable(Map<String, Pricing>)` builds from a deployer-supplied rate card. A budget needs a calculator: `SessionOptions` throws `IllegalArgumentException` when `maxBudgetMicroUsd` is set and the calculator is `ZERO`. A price table never prices an unknown model at zero: `staticTable` and `AnthropicPricing.calculator` throw `IllegalArgumentException` naming the id, which ends a session's run in `ErrorDuringExecution`. `Pricing(long inputMicroUsdPerMillion, long outputMicroUsdPerMillion)` uses `Math.multiplyExact` / `addExact` for overflow safety. `SessionLimits.maxBudgetMicroUsd` is `OptionalLong`; when set, `StopClassifier` produces `ResultMessage.ErrorMaxBudgetUsd(microUsdSpent)` when cost exceeds the cap. Long max ≈ $9.2T headroom |
| **SessionLimits (record + Builder)** | Per run: `maxTurns` → `ErrorMaxTurns`, `maxBudgetMicroUsd` → `ErrorMaxBudgetUsd`, `maxWallClock` → `ErrorMaxWallClock`. Also `toolTimeoutDefault` (one tool call), `stopGrace` (how long anything the session cancels is given to stop), `maxContextTokens` (compaction trigger), `startTimeout` (`onSessionStart`) and `maxToolResultChars` (one result entering history). Defaults: 100 turns, no budget, 1 h, 2 min, 5 s, 0 = the model's window minus its output reserve, 60 s, 65,536 chars |
| **Loop crash semantics and teardown** | `AgentLoop.run` turns every `Exception` into an `ErrorDuringExecution` terminal. An `Error` is a host failure, never a terminal or a `ToolOutcome.Threw`. One rule covers it: every `Error` caught on a thread the session runs work on (a run's thread in `SessionRuns.execute`, a tool worker in `DispatchedCall`, a callback of the session's, a run's or a tool call's token through `CancellationToken.cancelAsync`, the model stream's reader and its close, the compaction summary call, a run's wall clock, `onSessionStart` and `onSessionEnd`) goes to one sink, `SessionState.ending()` (`SessionEnding`). Recording one cancels the session through `SessionEnding.cancel`, the one session cancellation path `close()` and the teardown share, with the reason `host error <class>`; the run in progress follows the session's token (`Run`), so no further model or tool call is made. Waiting paths still rethrow an `Error` as itself (`DispatchedCall.await`, `ModelCall`, `FaultTolerance`, the compaction), so a model's, a tool's and a hook's `Error` leave the run without a `RunEnded` event, and `ToolRound` catches only `RuntimeException`. `SessionEnding.failWith` also records an `Error` carried as the cause of a `CompletionException` or `ExecutionException` where such an exception is caught. The run in progress fails with the first `Error` recorded, as the same instance, before the session tears down as `close()` does; the teardown's seal adds every later one, and an exception of `onSessionEnd`, as suppressed; an `Error` that reaches the session while it closes with no run to carry it is logged at `SEVERE`, and one recorded after the seal is logged at `SEVERE` too, on the `AgentSessionImpl` logger. The run's virtual thread then throws the `Error` to its uncaught-exception handler; nothing stops the JVM, the host decides. Outside the sink: threads the session does not own. A sandbox child's failure stays contained: it comes back over RPC as tool output. A run's own token is cancelled by `interrupt()` (`"interrupted"`), its wall clock (a virtual thread, `helios-wall-clock-<sessionId>`, waiting on the run's token for `maxWallClock`), the session's cancellation and its own end (`"run ended"`); its end then waits one `stopGrace` deadline for the token's callbacks and `ToolDispatch.endLiveCalls`, logging what still runs, before `RunEnded` and the run's future; a `Cancelled`, `ErrorMaxWallClock` or `ErrorMaxBudgetUsd` terminal takes its usage and cost after that wait (`StopClassifier.withSpend`), so a call's late spend, a subagent's, counts. `onSessionStart` is the last step of `AgentSessionImpl`'s construction, after `SessionWiring` built the loop; `SessionStart` runs it on a thread of its own and waits `SessionLimits.startTimeout` (60 s). A refusal, `null`, an `Exception`, a timeout or an interrupt of the opening thread makes `open` throw `SessionUnavailableException` (an `Error` propagates as itself) and releases what the session acquired without calling `onSessionEnd`; a start that settles after the session stopped waiting is logged as a `WARNING`, and a late acceptance is ended by `SessionTeardown.end` on a thread of its own. Every other end goes through `SessionLifecycle.tearDown` once: on the thread of the run in progress once that run ended, if the session closed or a host `Error` failed it, before that thread settles the run's future; for an idle session a host `Error` failed, on a thread of its own; otherwise on `close()`'s caller. `close()` cancels the run in progress and joins its thread, then the teardown, every wait sharing one deadline, `SessionLimits.stopGrace` (5 s) from the start of `close()`, so a step after one that used it up runs but is not waited for: cancel the session and wait for its token's callbacks and `ToolDispatch.endLiveCalls`; run `onSessionEnd` on a thread of its own (`BoundedTask`) and wait; end the event streams, never waiting for a reader; seal. A tool call or `onSessionEnd` still running at the deadline is logged as a `WARNING` and never taken for ended: a tool that ignores both its token and interruption may still run, and its effects land, after the result settles. `SessionRunsTest`, `SessionRunsEndingTest`, `SessionHostErrorTest`, `SessionFinalizationTest`, `SessionStartTest` and `SessionTeardownTest` hold the acceptance tests |
| **ExecutionProvider** | An `ExecutionProvider` runs `Execute` scripts. It declares `supportedRuntimes()`; `Execute` refuses any other runtime before calling it. `execute(session, request, token)` runs on the tool call's thread and must stop when that thread is interrupted or the token fires. `onSessionStart` / `onSessionEnd` run once per session. Each provider enforces its own isolation and timeout clamp |
| **Session store** | `SessionOptions.Builder.withStore(store)`: `AgentSession.open` is get-or-create by session id, loading the stored history after the options' system prompt (never stored). The log (`session.store`) is append-only, `MessageAdded`, `HistoryReplaced` (compaction or `MutateHistory`) and `RunEnded` (the run's terminal by its record's simple name and, on `Success` only, its `result`), loaded by `StoredSession.fold`; `loop.StoredHistory` appends a turn once its run goes on, with the next turn's start, and the turn that ends a run with the run's `RunEnded`, in one batch. `StoredSession.lastRunEnded()` is the log's last entry when it is a `RunEnded`: the run that wrote last ended, and a `Success` run's answer is its `result()`. The messages the session writes itself carry a `store.MessageOrigin` tag in `Message.metadata()` (`helios.origin`: `schema-correction`, `rejected-attempt`, `hook-inject`, `partial-turn`: the text of a model call cut short, kept by `ModelCall.keepPartial` as the run's last message without tool calls or provider metadata). Each append names the last sequence number read or written; a mismatch is `SessionConflictException`, ending the run `ErrorDuringExecution` kind `SessionConflict` (other failures `SessionStoreFailed`) and closing the session. Stores: `InMemorySessionStore`, `FileSessionStore` (JSON lines via `SessionEntryJson`; refuses a group- or other-writable directory and a file named on disk unlike its id), `persistence.PgSessionStore` (one `helios_session_entries` row per entry, `SessionEntryJson` as `TEXT`; the first insert of a batch is conditioned on the session's `MAX(seq)`, the primary key refuses a racing writer; no redactor); each passes `session.test.SessionStoreContract`, which also stops a run at each of its store writes and reopens it. Restart after a crash: the stored history never ends with a tool call that has no result, since the loop writes after a whole turn and at a run's end after answering open calls; the host loads the session, sees `lastRunEnded()` empty, reopens it under the same id and `send`s a continuation of its choosing, or records the run as abandoned |
| **Subagents** | `withSubagent(new Subagent(name, description, options))` (name `[a-z][a-z0-9_-]{0,63}`, options without `AskUserQuestion`) registers the built-in `Agent` tool (`session.AgentTool`): input `{agent, prompt}`, `EXECUTION`, key `Agent(<name>)`, so hooks and permission rules gate it like any tool; no JSON-schema `enum` (core `ToolParameter` has none), the description lists each `name: description`, the `agent` parameter the names, and an unknown name is a failure listing them. A call opens one fresh child session from the subagent's options (fresh `sess-` id, `agentName` the subagent's, nothing inherited), sends the prompt, joins the run and closes the child. The parent run is `Run.stateOfThisThread()` and the call `ToolDispatch.callOfThisThread()` (a `ScopedValue` `DispatchedCall` binds around `Tool.execute`). The child's run totals roll up into the parent run's as they accumulate (`SessionTotals` with a parent run, through `SessionState`; the parent's `promptTokens` kept, nested subagents chain), so the spend counts however long the child's teardown takes; under a parent budget the child's is `min(own, left)`, nothing left fails before opening. `ToolDispatch`'s child-limits function (`AgentTool::childLimits`) runs the call under its subagent's limits: deadline `maxWallClock` plus `stopGrace`, then that `stopGrace` for the child to stop, and a tool that returns by then answers with what it returned, so the model reads the child's `Cancelled` failure. The call's token interrupts the child and closes it, which tears it down even while its run is stuck. A child's host `Error` (also one a joined future carries, or its close records) is rethrown, so it fails the parent as itself; every answer, and why a child crashed (its run's `RuntimeException`) or could not open, is redacted by the child's own secret registry as it is when the call answers, before the parent's redacts it. A child still stopping when the parent run's `stopGrace` runs out is left running like any tool call, and what it accounts after that is in its own result only. A child (`AgentSessionImpl(options, Optional<AgentTool.Parent>)`) denies every `ASK` (`AgentTool.DENY_ASKS`) and its run span carries `helios.parent.session_id` / `run_id` / `tool_call_id` (`RunSpans.parentLinkage`, so `RunSpans` keeps owning the names), nested under the call's `execute_tool` span. `build()` refuses duplicate names, a tool named `Agent` and, under a budget, a `ZERO`-calculator subagent at any depth. Background children, delivery into the parent, idempotency and concurrency caps are the host's. `SubagentSessionTest` holds the acceptance tests |
| **Session tracing** | OpenTelemetry spans through the API only: `withOpenTelemetry` (default `GlobalOpenTelemetry`), `withTraceDetail` (`STANDARD` no content, `VERBOSE` redacted content). Each run is an `invoke_agent` span, a child of the context of the `send` that started it, with a `chat` span per model attempt, an `execute_tool` span per call and a zero-length `hook` span per `HookFired`, each carrying `helios.turn.index`, its events' `turnIndex` (`1` for a run's first model turn), except the compactor's `chat` span, which carries `helios.compaction` = `true` instead; `RunSpans` owns every span name and attribute; `SpanFailure` is the error a run span ends with. A span call that throws a `RuntimeException` is logged and changes no run. `withTraceAttributes` labels every run span with `app.*` keys (any other key throws). `persistence.PgSpanExporter` stores every span that carries `helios.run.id`, and no other: Helios's own, and a host's span of any other scope as a `host` row of that run (`Span.scope()` from its `otel.scope.name` attribute), so a host records what it made of a run as `app.*` attributes and status on a span it ends with the run's id, in either order with the run's row; `PgTraceStore.list` selects labels by JSON containment, `attributes eq "${\"app.x\":\"v\"}"` |
| **SessionRegistry (helios-runtime)** | In-memory registry of the sessions a process serves, each owned by one `Principal`: `find` and `close` act only for the owner and answer anyone else as for an unknown id. `create` takes capacity atomically before the factory runs: at most 16 sessions per principal and 256 per registry, evicting the owner's own longest-idle session to make room, else `SessionCapacityException` (`429` / `503`). `sweep()`, which `RuntimeServer` runs every 30 s, closes a session idle past its retention (15 min) |
| **AgentHttpService (helios-runtime)** | Helidon `HttpService` of eight routes: create, message, interrupt, SSE events, result long poll, questions, answer, delete. Each runs the deployer's `Authenticator` first (`AuthenticationGate`: `401` with its challenge, `503` if it throws), and a foreign session is the same `404` as an unknown id. Admission then caps a body at 256 KiB (`413`) and a principal at 32 requests in flight (`429`); a refusal answers without reading the body and closes the connection. A `WebServer` of your own sets Helidon's `maxPayloadSize` and `continueImmediately` as `RuntimeServer` does |
| **Coverage gate** | Every library module (core, session, runtime, gemini, anthropic, openai, persistence, onnx, repl, testing) enforces JaCoCo `INSTRUCTION ≥ 95% / BRANCH ≥ 90%` through the one `coverage-gate` check execution in the root `pom.xml`, bound to the `verify` phase; `architecture` and the examples set `jacoco.skip`. A module's coverage comes from its offline suite: provider modules run against recorded transcripts and stub servers, never live keys. A module below the gate is raised with behaviour tests, never by lowering the limit or excluding classes. CI runs `./mvnw -B clean verify` |

## Cross-cutting framework primitives (`helios-core`)

| Pattern | Description |
|---------|-------------|
| **Result<T>** | Sealed interface: Success/Failure with pattern matching |
| **Cancellation** | `core.model.CancellationToken`: `cancel(reason)` takes effect once and the first reason wins. `onCancel` callbacks fire once (immediately if already cancelled) and `Registration.remove()` detaches one; work checks `isCancelled` / `throwIfCancelled`. both `cancel` and `cancelAsync` run each callback on a virtual thread of its own, in no order; `cancel` waits for them |
| **Fault Tolerance** | Zero-deps: Backoff, RetryPolicy, CircuitBreaker, FaultTolerance. `FaultTolerance.withoutRetry()` returns a sibling envelope with retry stripped (CB + timeout retained). The session loop does not use it: `ToolDispatch` runs every tool call exactly once, so no tool call is ever retried (see the Tool calls row) |
| **Secret Redaction** | `core.common.SecretRegistry` holds a session's secret values (`SessionOptions.withSecretRegistry`; tools and providers reach it as `SessionContext.secrets()`); `redactor()` replaces each with `<redacted:NAME>`. The session redacts every tool result's text once, before its `ToolResult` event, `PostToolUse` hooks and history, and every `SerializedError` it builds; no tool redacts its own output. Output cut at a size cap drops a trailing partial secret (`Redactor.trimPartialSecret`). Accepted values: `SecretRegistry` javadoc |
| **Provenance (Basis-style structured output)** | `core.common.Confidence` (LOW/MEDIUM/HIGH ordinal), `core.common.Source` (title?, url, excerpts), `core.common.FieldProvenance` (field, sources, reasoning, confidence), `core.common.Provenanced<T>` ({output, provenance:[...]} sidecar pattern). `OutputSchema.provenancedOf(MyOutput.class)` returns an `OutputSchema<Provenanced<MyOutput>>` whose JSON schema asks the model for `{output, provenance}`. `ProvenanceValidator.DEFAULT` rejects `MEDIUM`/`HIGH` entries with no sources — the calibration mechanism that prevents the model from rubber-stamping HIGH on every field. Custom validators via `OutputSchema.provenancedOf(MyOutput.class, validator)`; `ProvenanceValidator.excerptLengthCap(int)` and `andThen(...)` compose. Mirrors parallel.ai's Basis framework with the family renamed to "provenance" |
| **CommandGrant (host-owned CLI)** | `core.tool.CommandGrant.builder("gh").withSecretRegistry(reg).withEnv("GH_TOKEN", t).build().toTool()` produces a Tool that lets the model invoke a single CLI binary under tight controls. Hardening, all on by default: binary path pinned at build time (no PATH-shadow surprises), argv-only never shell, `ProcessBuilder.environment().clear()` then injects only granted env (no JVM env leak), argv pre-scan refuses any registered secret value (forces env-only secret transport), stdin = closed, per-call temp cwd by default, stdout+stderr capped (a cut stream loses its trailing partial secret), descendants killed and reaped on timeout, cancellation of the call's token and interrupt (`BoundedProcess.await(CancellationToken)`), stderr hidden from model unless `withStderrToModel(true)`. Concurrency limited per grant (default 4, immediate refuse on overflow). `withSecretRegistry` is required (`build()` throws without it) and must be the session's registry: in a session with another registry instance the tool returns a failure and starts no process. Output is raw; the session redacts it. `invoke` / `InvocationResult` are package-private |
| **Workspace file tools (`Read` / `Grep` / `Glob`)** | One v2 file-tool family in `session.files`. `ReadTool.binding(workspace)`, `GrepTool.binding(workspace)`, `GlobTool.binding(workspace)`. Path-jail via `WorkspaceRoot`, the single confinement contract: `root()` is canonical; `resolveSafe` refuses `..`/absolute lexically, then canonicalises the deepest *existing* prefix (so a new leaf under a symlinked ancestor is judged by the real target; dangling/looping links refused) and returns a path with no symlink component; `canonical` spells it as the tools' permission key (`.`, `./a/b`; a missing parent refused) and the tools resolve that key with `resolveCanonical`, which fails when it no longer resolves to itself; `Glob`, `Grep` and `LS` skip, without asking, entries their own deny rules decide, and those their own ask rules decide unless the walk itself was approved through one (`Grep` also `Read`'s deny and ask entries); all I/O goes through descriptor-relative `WorkspaceRoot` primitives, including no-follow ancestor descent, metadata-only leaf pinning before regular-file I/O, bounded reads, creation, deletion and pinned directory enumeration. Walkers skip anything not a regular file per lstat (symlinks, FIFOs, devices); hidden directories pruned. `FileSystemMemoryBackend` is strictly narrower — a memory path must canonicalise to exactly its lexical location under `.agent/memory`, so every symlink below it is refused. `WorkspaceRoot.MEMORY_STORE` is reserved in every workspace: `canonical` refuses a target in it and `WorkspaceWalk` skips it, so only `MemoryRead`/`MemoryWrite` reach it (`Execute` is not covered). `new WorkspaceRoot(root, false)` is the explicitly weaker, portable trusted mode, never a preset default: canonical targets may lie outside the root, and its I/O does not resist a concurrent symlink swap. Strict mode requires Linux x86-64/AArch64, the default filesystem, `/proc/self/fd` and `--enable-native-access=com.standardapplied.helios.session` (module path) or `ALL-UNNAMED` (classpath); unsupported configurations fail closed. Filesystem memory stays strict even with a trusted workspace. Host native-access grants are not forwarded to sandbox children. Confinement is not a snapshot or protection against hard links or mount manipulation, and keys assume a case-sensitive filesystem (casefold, vfat and SMB are out of scope). Per-file size caps, a 2,000-char line cut shared by `Read` and `Grep` (`TextPage.cut`), a 60,000-char `Read` page that names the next `offset`, attachment caps that fit Anthropic's request (`MAX_IMAGE_BYTES` 7,500,000, `MAX_PDF_BYTES` 18,000,000) and match/result count caps all enforced; a line cut at its cap loses its trailing partial secret (through `ToolContext.sessionContext().secrets()`); `Grep` matches through `CancellableLine`, so a cancelled call stops mid-line and fails `Grep: cancelled`. Their text output is redacted by the session's one pass like every tool's, so a token written by `CommandGrant("gh")` stays redacted when the agent later reads the file; `Read` attachments (images, PDFs) pass through unredacted. **Curated-corpus use case:** point a `WorkspaceRoot` at any directory (not just the session's working dir) and the same tools cover what the removed `FilesystemKnowledge` / `kb_*` family did pre-2.4.0. Read-only enforced via `Permission.planMode()` or a curated `Permission` policy — there is no Write tool to disable |
| **Provider-neutral web tools (2.8.0)** | `ModelConfig.withWebSearch/withWebFetch` — Gemini: Google Search grounding + URL context; Anthropic: `web_search_20260318`/`web_fetch_20260318` server tools (citations → `Response.citations()`, content blocks echoed verbatim via `anthropic.rawContent` metadata, `pause_turn` auto-continued by the Anthropic provider's `PauseContinuation`, bounded at 8); OpenAI: fails fast at construction |
| **Current model catalogs** | Reasoning is `ModelConfig.reasoning`, an `Optional<core.model.Reasoning>`: `Reasoning.Off` or `Reasoning.Effort(Level, Display)` (`MINIMAL`..`MAX`; `HIDDEN` / `SUMMARY` / `PROGRESS`). Absent sends no thinking/reasoning/effort field. Present is sent exactly or the provider's `create` throws (model id, rejected value, accepted values); nothing is clamped. Each catalogue declares what a model accepts (`AnthropicModelId.ReasoningRules`, `OpenAIModelId.ReasoningRules`, `GeminiModelId.reasoning()`, all checked through `core.provider.ReasoningSupport`) and only the `*RequestBuilder` reads it (ArchUnit). Uncatalogued Anthropic and OpenAI ids accept every value the provider can spell (OpenAI has no `PROGRESS`) and get the current shape; Gemini has no uncatalogued path. Anthropic (`Effort` → `thinking.type=adaptive` + `display` `omitted`/`summarized`/`updates` + `output_config.effort`): Fable 5 / 5.1 / 5.2, Mythos 5.1 / 5.2, Opus 5.5 (`ALWAYS_ON_WITH_PROGRESS` — `Off` throws, `LOW`..`MAX`, all displays), Mythos 5 (`ALWAYS_ON` — no `PROGRESS`), Sonnet 5.5 (`OFF_BETWEEN_TOOLS` — `Off` sends bare `between_tools`, all displays), Opus 5 / Sonnet 5 / Haiku 5.5 (`OFF_WHEN_DISABLED` — `Off` sends `disabled`, no `PROGRESS`). `PROGRESS` makes the provider add the `thinking-display-updates-2026-08-18` beta itself. Every catalogued model throws on any set `temperature`/`topP`. A `claude…` id before generation 5 (`AnthropicModelId.predatesClaude5`: `claude-sonnet-4-6`, `claude-3-5-sonnet-20241022`, `claude-2.1`) makes `AnthropicProvider.create` throw, listing the catalogued ids. Fable 5.2, Mythos 5.2, Fable 5.1, Mythos 5.1, Opus 5.5 and Sonnet 5.5 reject forced `tool_choice` — `AnthropicProvider.create` fails fast. `fromWireId` resolves only eight-digit dated snapshots to a family, so an uncatalogued release never inherits a predecessor's request rules. Thinking turns that interleave thinking with text/tool calls or hold `redacted_thinking` are echoed verbatim via `anthropic.rawContent`. Retryable mid-stream API errors (`overloaded_error` etc.) surface as `TransientStreamException`. `refusal` → `FinishReason.REFUSAL`, with `stop_details` category/explanation on `Response.REFUSAL_*_KEY` metadata → `ResultMessage.Refusal.category()`; a refused turn never dispatches tool calls it emitted before the refusal. `AnthropicPricing.calculator(cachePolicy)` is the list-price `CostCalculator` (`AS_OF` dates it); Haiku 5.5 is priced per request by `Usage.promptTokens()`, over 100,000 at its long-prompt rate. Gemini 3.6 / 3.7 / 3.8 Flash, 4 Argon and 4 Carbon: `Off` throws (thinking cannot stop); 3.7 / 3.8 Flash, 4 Argon and 4 Carbon take `LOW`..`HIGH`, 3.6 Flash `MINIMAL`..`HIGH`; `HIDDEN`/`SUMMARY` → `thinking_summaries` `none`/`auto`. OpenAI (`Effort` → `reasoning.effort`, `SUMMARY` adds `summary: auto`): gpt-6-astra / gpt-6.1-sol (`ALWAYS_REASONS` — `Off` throws, `LOW`..`MAX`, no sampling), gpt-6.1-terra / gpt-6-sol / gpt-6-luna / gpt-5.6 family (`NONE_TO_MAX` — `Off` sends `none`, sampling only with `Off`), gpt-5.5 (`NONE_TO_XHIGH`), gpt-5.4 family (`NONE_BY_DEFAULT_TO_XHIGH` — sampling unless `Effort`), o-series (`LOW_TO_HIGH`, `Off` throws, no sampling), gpt-4.1 / gpt-4o (`NOT_REASONING` — every `Effort` throws); `ModelConfig.promptCacheKey` → `prompt_cache_key`; `cache_write_tokens` → `Usage.cacheCreationInputTokens`. Every provider streams its thinking as `ModelChunk.ThinkingDelta`s, so `QueryEvent.AssistantThinking` fires incrementally for every provider, progress notes included |
| **Replayed reasoning** | Every assistant message records `anthropic.promptStamp` / `gemini.promptStamp`, the `core.provider.PromptStamp` of the system prompt and tools its request sent (canonical JSON, cache breakpoints aside; the model id is not part of it). Anthropic, on a model whose `AnthropicModelId.bindsReasoning()`, keeps `thinking` / `redacted_thinking` only on the turns after the last one stamped otherwise (`ReasoningReplay`; a turn left empty is sent as `[earlier output omitted]`) and with adaptive thinking always sends `thinking.block_binding.prefix_mismatch_behavior: "error"` (beta `thinking-binding-controls-2026-08-01`). Gemini continues an interaction only from the latest assistant turn, and only under the current stamp, and resends the whole conversation once when a continued request is answered `404` before any event (`InteractionResend`). A model switch needs nothing: the API drops blocks the new model cannot read |
| **Structured Output Resilience** | Three layers, each independently tested. **(1)** `StructuredContentParser.parse()` reads to `Map` first, runs `core.schema.SchemaValidator`, then the schema's `SubmitValidator` (if any) on the typed value, and surfaces every failure — schema-validation mismatches, JSON-syntax errors, and semantic rejections (`SubmitValidationException` subtype) — as `StructuredOutputParseException` (e.g. `provenance[3].sources[0].title is required but missing` for schema errors, or `JSON syntax error: ...` for malformed JSON). **(2)** Provider `chat(messages, tools, outputSchema)` skips parse entirely when `response.toolCalls()` is non-empty — tool-calling turns are intermediate, structured output is the deliverable of a later text-only turn — and for a `REFUSAL` or `CONTENT_FILTER` turn, which ends the run as a `Refusal`. Anthropic's `AnthropicRequestBuilder` also emits a turn-aware schema instruction when tools are present ("you may call tools; when ready to emit final answer, it must be valid JSON…") so the system prompt stops fighting the deployer's "use tools first" guidance. **(3)** Session loop self-correction in `ModelCall.selfCorrectSchema` pattern-matches `StructuredOutputParseException` on the `TurnOutcome` that `ModelCall.stream` returns, appends the model's wrong attempt to history as an assistant message tagged `MessageOrigin.REJECTED_ATTEMPT`, then `StructuredOutputParseException.correctionMessage()` (field-level diff only — rawContent is intentionally not echoed per the exception's class-level cost note) as a user message tagged `MessageOrigin.SCHEMA_CORRECTION`, never through the steering queue or `OnUserMessage` hooks, returns `TOOL_CALLS` so the loop iterates. Bounded by `SessionLimits.maxTurns()`. `SchemaValidator` is public in `core/schema` and reused by typed `runBlocking` post-hoc parsing |
| **Trace usage/cost telemetry + rollups (2.7.0)** | `persistence.Span`/`Trace` carry typed nullable `usage` (`Response.Usage`) + `cost` (`CostEstimate`), rebuilt by `PgSpanExporter` from the spans' `gen_ai.usage.*` and `helios.cost.micro_usd` attributes; a run's totals are its result's. Persisted as first-class columns on `helios_traces`/`helios_spans` (null = not recorded). `PgTraceStore.summarize(TraceRollupKey, TraceFilter)` returns per-group `TraceRollup` (run/error counts, duration p50/p95, token-class + cost sums, thumbs) grouped by NAME / MODEL_ID — no hand-rolled SQL against the schema |
| **Prompt drafts + activation (2.7.0)** | `PromptRegistry.registerDraft(name, content)` creates the next version inactive (active version stays live); `activate(name, version)` promotes/rolls back explicitly, throws on unknown target. Eval runners pin drafts via `resolve(name, version)`. `InMemoryPromptRegistry.resolve(name)` returns the *active* version, not the newest |
| **ScriptedModel (helios-testing, 2.7.0)** | Deterministic `Model` replaying a builder-defined turn script (`withTextTurn`/`withToolCallsTurn`, optional per-turn `Usage`; `withStreamTurn(CloseableIterator<ModelChunk>)` answers `chatStream` with that stream verbatim, and `ModelStreams.of` / `failing` / `stalled` build the common one-pass streams, `stalled(onStall, chunks...)` yielding its chunks before it stalls). Structured-output calls parse scripted JSON through the real `StructuredContentParser`; tool-call and refused turns skip parse like providers; exhaustion fails fast; `calls()` captures every invocation's messages |
| **Provider plumbing (`core.provider`)** | `Model.chatStream` returns a lazy `CloseableIterator<ModelChunk>`, read by one reader in one pass and ending in one `MessageStop`; a failed turn throws from `hasNext()`, and `close()`, from any thread, is the only cancel. A provider is a `StreamingModel` assembled from its request builder, stream parser (read through `SseReader`), JSON holder and exception type. A stream ends in one `Done` or `Error`, so a body cut short is never a completion. `ModelConfig.streamIdleTimeout` (120 s) is the one idle clock; a 408, 429 or 5xx at open is a `TransientStreamException` |
| **Annotation (v2 structured note)** | `persistence.Annotation` attaches a structured judgment to a stored run or span: its `subjectId` is the run id or the span id, a `String` (opaque consumer subjects were rejected — an annotation always targets something Helios observed). `facet` (nullable) is a named sub-coordinate within the subject so one author holds many judgments per subject without synthetic ids; `authorKind` (`HUMAN\|MODEL\|SYSTEM` enum) types the author; `metadata` is a first-class `Map<String,Object>` → `jsonb`, always non-null/immutable/null-tolerant. `PgAnnotationStore.upsertAnnotation` persists the full record, idempotent on `(subjectId, facet, label, authorId)` via a partial unique index with `COALESCE(facet,'')` (author-less rows always insert, preserving pre-v2 behavior; `createdAt` preserved + `updatedAt` advanced on conflict). Reads on the same store: `findAnnotationsBySubject` / `findAnnotationsBySubjects(batch)` / `listAnnotations(Paginate, scimFilter)` over first-class columns. `facet`/`label`/`metadata` are opaque — stored and returned uninterpreted. DDL delta in CHANGELOG |

## Resource Lifecycle

`Model` is `AutoCloseable` and holds long-lived OS resources (HttpClient connection pools, file descriptors). Per-provider `close()` calls `httpClient.shutdown()` with a 5s grace period before `shutdownNow()`.

**Ownership rule:** the component that constructs a `Model` owns its lifecycle. A session does NOT close its Model — closing a Model while sibling sessions reference it would fail their subsequent requests. Build the Model once at app startup, share it across many sessions, and `close()` it once at shutdown.

`AgentSession` is `AutoCloseable` and owns its loop, its wall-clock virtual thread, its event streams and any pending tool/question state. Always use `try-with-resources`; `close()` cancels the loop, and the result future settles once the teardown has run, its waits (live tool calls, `onSessionEnd`) sharing one deadline, `SessionLimits.stopGrace` (5 s) from its start; it never waits for an event reader. A tool that ignores both its token and interruption may still run after the result settles.

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

User-visible release-by-release breaking changes live in [`CHANGELOG.md`](CHANGELOG.md).

## REPL Module (the sandbox behind `Execute(JSHELL)`)

Sandboxed JShell execution, and the one way to run Java snippets: a session wires
`JShellExecutionProvider` with `withExecutionProvider`, which registers `Execute`; a call with
`runtime: JSHELL` runs in that session's own `ReplSession`, whose `JvmSandbox` subprocess keeps
variables, imports and classes across calls. The pieces are `JShellExecutionProvider`, `JvmSandbox`,
`JvmSandboxBootstrap`, `ReplSession`, `ReplConfig`, `SandboxPrelude`, the sandbox policy verifier and
module limits, and the `HostFunction` infrastructure. The sandbox has no built-in host functions;
every host call is a custom `HostFunction` registered through `ReplConfig.withHostFunction`.

### Key Design Decisions

**Protocol.** JSON-RPC 2.0 over NDJSON; `\0RPC:` magic prefix distinguishes RPC lines from regular stdout. Sandbox exceptions returned as `ToolResult.success()` so the model sees tracebacks and self-corrects.

**Output and frame caps.** Constants on `ProcessTransport`, no configuration. `MAX_CAPTURED_OUTPUT_CHARS` (1,000,000) bounds each of a snippet's outputs on both paths, in `CapturedOutput`, which owns the `\n[output truncated: <n> more chars discarded]` marker: `SnippetEvaluator` captures through a `CapturedStream` in the sandbox, which also bounds each capture's JSON-escaped width to a third of a frame, so `System.out` and `System.err` reach the host inside the execute reply, cut, and timeout diagnostics share stderr's cap, and `StdoutCapture` reads what reaches the subprocess's raw stdout in chunks on the host. Past the cap output is read and dropped, so neither a snippet nor the pipe ever blocks. `MAX_FRAME_CHARS` (8,000,000) is the host's guard against code that writes the RPC socket itself: `ProcessTransport` reads lines through `BoundedLineReader`, and a longer line ends the channel. `RpcChannel.close` releases the reader, handlers, pending calls and transport once, and the read loop ends there too. `JvmSandbox.isAlive` is `false` once the channel ended; the bootstrap sees the closed socket and exits.

**Single-execute semantics.** `SnippetEvaluator`, which `JvmSandboxBootstrap` runs behind its RPC read loop (`BootstrapRpc`), enforces single-execute with `Semaphore(1)` — `System.setOut` / `setErr` are JVM-global; concurrent evals would corrupt streams. `ReplSession` uses `Semaphore.tryAcquire()` for max-concurrent-sessions bounding. `HostFunctionRegistry.freeze()` prevents modifications after sandbox startup.

**`SandboxPrelude`.** Installs a curated JShell preamble at sandbox boot: standard imports (`java.util.*`, `java.util.stream.*`, `java.util.function.*`, `java.io.*`, `java.math.*`, `java.time.*`, `Collectors`), free `print` / `println` / `printf` (PRINTING-equivalent), and ten script-style helpers (`sum`, `sumInts`, `mean`, `max`, `min`, `join`, `filter`, `map`, `sorted`, `countBy`).

**Custom host functions are typed and JShell-callable.** Every non-reserved `HostFunction` registered before sandbox boot gets a typed JShell static wrapper synthesized into the prelude. `HostFunction("marketQuote", [HostParameter.required("ticker", STRING, ...)], handler)` becomes callable as `marketQuote("AAPL")` from emitted Java code. The wrapper packs args into a `LinkedHashMap` keyed by parameter name and dispatches via `HostBridge.__call`. The one reserved name, `__call` (`HostFunctionRegistry.RESERVED_NAMES`), is skipped — `HostBridge` owns that static method.

**Sandbox policy seam (`com.standardapplied.helios.repl.sandbox.policy`).** Layer-2 enforcement point that wraps JShell's `LocalExecutionControl` via `GuardedExecutionControl` (subclass) + `GuardedExecutionControlProvider`. The bootstrap installs the provider via `JShell.builder().executionEngine(new GuardedExecutionControlProvider(policy), Map.of())` instead of the stock `"local"` engine. Every snippet class, JShell wrapper, and `var` declaration flows through `load(ClassBytecodes[])` as raw bytes before any classloader sees them — that's the chokepoint `BytecodeVerifier` scans against the active `SandboxPolicy`. `SandboxPolicy` (record + Builder, `permissive()` preset) carries `deniedClasses`, `deniedPackages`, `denyReflection`, `denyNativeAccess`, `denyDynamicClassDefinition`, and `onViolation`. Non-permissive policies travel host→subprocess via `--sandbox-policy=<base64>` argv (encoded by `SandboxPolicySerialization`); permissive is the bootstrap's own default and isn't propagated. SecurityManager is gone in JDK 25 (JEP 411/486 finalized), so the only honest in-JVM enforcement is bytecode-level — and even that is defense-in-depth, not the perimeter (OS-level isolation remains the only authoritative boundary).

**Sandbox policy enforcement (`PolicyBytecodeVerifier`).** Real verifier built on the JDK Classfile API (JEP 484, finalised in JDK 24). Scans every method's code stream for `InvokeInstruction` (INVOKE*), `NewObjectInstruction` (NEW), `FieldInstruction` (GET*/PUT*), `LoadConstantInstruction` (LDC of a class literal, method handle or dynamic constant) and `InvokeDynamicInstruction`. Indirect references get the same owner/member policy as direct ones: the walker follows every `invokedynamic` bootstrap argument, method-handle constant and dynamic constant (including nested bootstrap arguments) at the constant-pool-entry level, peels array types to their element class, bounds nesting at 32, rejects self-referencing constants, and turns any Classfile API parse failure into a `SandboxPolicyException` (`dynamicConstantDepth` / `dynamicConstantCycle` / `malformedClassfile`) so malformed input fails closed. Language bootstraps (`LambdaMetafactory`, `StringConcatFactory`, `ObjectMethods`, `SwitchBootstraps`, the `ConstantBootstraps` materialisers, static kind only) are trusted as dispatchers but their arguments never are — `FileReader::new` is denied exactly like `new FileReader(...)`, while lambdas, records, concatenation and pattern switches keep working. Any other bootstrap is checked like a direct static call. Method-type signatures stay unchecked, matching direct invokes. Rule order on match: explicit `deniedClasses` → `deniedPackages` → categorical `denyReflection` / `denyNativeAccess` / `denyDynamicClassDefinition` → allow-list default-deny. Reflection rule denies entire `java/lang/reflect/*` and `java/lang/invoke/*` plus the reflective entry points on `java/lang/Class` (`forName`, `newInstance`, `getMethod*`, `getField*`, `getConstructor*`, `getDeclared*`, `getRecordComponents`, `getEnclosingMethod`, `getEnclosingConstructor`) — non-reflective `Class` methods (`getName`, `cast`, `isInstance`, etc.) stay callable. **Denial signal**: JShell silently swallows the `ClassInstallException` thrown when the verifier rejects load — the snippet's `SnippetEvent` comes back with status `VALID`, no value, no exception attached. `GuardedExecutionControl.verifyAll` writes the policy message to `System.err` before rewrapping into `ClassInstallException`; the sandbox bootstrap captures stderr during `execute` and forwards it back to the host as the eval result's `stderr`, so the model receives a clean policy traceback through the standard channel.

**Allow-list mode (`SandboxPolicy.allowedPackages`).** Opt-in inverse of the deny-list. When non-empty, the verifier flips for JDK-scoped owners (under `java/`, `javax/`, `jdk/`, `sun/`, `com/sun/`): default-deny, allow only if the owner's package matches one of the allowed prefixes. Non-JDK owners (snippet's own `REPL.$JShell$N` wrappers, user helper classes, third-party JARs on the snippet's classpath) bypass the allow-list and are always permitted — they're the very code being verified. Deny rules layer on top as overrides ("deny wins"). The rule label on violation is `allowedPackages-default-deny`.

**`SandboxPolicy.noEgress()` curated preset.** "Compute with no egress" — allows `java.lang`, `java.util` (+ stream/function/regex/concurrent.atomic), `java.math`, `java.time`, `java.text`, `java.io` (for stdio + in-memory streams); denies the dangerous classes inside those packages (`ProcessBuilder`, `Runtime`, `Thread`, `ThreadGroup`, `File*Stream`, `FileReader`, `FileWriter`, `RandomAccessFile`, `ObjectInputStream`, `ObjectOutputStream`); enables all three categorical denies. Composes allow-list mode with explicit denies and categorical flags — single API call for the most common enterprise posture.

**Subprocess module restriction (`SubprocessModules`, L3).** Sealed interface (`Unrestricted` / `Restricted` records) controlling the subprocess JVM's `--limit-modules` flag. Three factories: `unrestricted()` (default — all JDK modules observable), `minimal()` (required roots only — `java.base`, `java.compiler`, `jdk.compiler`, `jdk.jshell`; under modulepath launch `com.standardapplied.helios.repl` is also added; strips `java.sql`, `java.naming`, `java.scripting`, `java.desktop`, `jdk.httpserver`, etc.), `allowingExtras(String...)` (required + named extras). Wired through `JvmSandboxConfig.withSubprocessModules(...)`. **Works under classpath AND modulepath launches.** The bootstrap module name (`com.standardapplied.helios.repl`) is conditionally appended to `--limit-modules` by `SandboxLauncher.buildLaunchCommand` only when the parent uses `--module-path` — naming an unobservable module under classpath launch would crash the subprocess with "Module not found". Classpath launches get the JDK-only baseline; the bootstrap loads via classpath into the unnamed module which can read all observable JDK modules. **Bootstrap-transitive-closure limit (modulepath only)**: under modulepath launch the bootstrap's transitive `requires` clauses keep their target modules observable — `com.standardapplied.helios.core` requires `java.net.http` via `HttpClientFactory`, so http stays observable under any L3 setting; under classpath launch module-info `requires` are ignored (classpath JARs are unnamed-module members), so classpath gets *stricter* L3 enforcement. Deployers on modulepath who need to deny those modules reach for L2 `deniedPackages` instead. Composes with L2 enforcement — limit-modules eliminates whole categories at compile time, the verifier catches the rest at load time. `SandboxLauncher.shouldPropagateJvmArg` filters `--add-modules` and `--limit-modules` from inherited parent args so Maven Surefire's `--add-modules=ALL-MODULE-PATH` doesn't defeat the restriction. Empirical verification: `SubprocessModulesClasspathLaunchTest` manually launches the bootstrap in classpath-only mode and proves modules are stripped from the subprocess's boot layer.

## Example modules

- **`examples/session-demo`** — end-to-end `AgentSession` integration against Gemini with a real workspace. Validates the v2 session shape including `SessionPresets.readOnly(model, root)`, the streaming `QueryEvent` flow, and the typed `runBlocking` path. Every README `java` block that calls `SessionPresets` is a method of `QuickStarts` in its test sources, named by a `<!-- snippet: QuickStarts#<method> -->` marker above the block and held to it by `QuickStartsDocsTest`.
The v1 example modules were deleted with the v1 core surface, and the preset demos with their presets in 3.0; none has a replacement yet.

**Roadmap items tracked in `docs/specs/`** rather than in this file — keeps this README a description of what *is*, not a wish list.
