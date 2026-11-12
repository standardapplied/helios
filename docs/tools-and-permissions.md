# Tools, permissions and hooks

A session's model acts through tools: the built-in workspace file tools, `CommandGrant` CLIs, and
any `Tool` you write. Every call passes the session's permission policy, which allows, asks or
denies it by rule, and its hooks, which can block, rewrite or stop. This guide covers writing and
registering tools, giving the agent a CLI without its secrets, pointing the file tools at a
curated corpus, the permission rules and the hook phases. The confinement the file tools enforce
is in the [security guide](security.md#workspace-confinement). Back to the [README](../README.md).

- [Tools](#tools)
- [CommandGrant: a CLI without its secrets](#commandgrant-a-cli-without-its-secrets)
- [A curated corpus](#a-curated-corpus)
- [Permissions](#permissions)
- [Hooks](#hooks)

## Tools

<!-- snippet: ToolSnippets#weatherTool -->
```java
var weatherTool =
    Tool.newBuilder()
        .withName("get_weather")
        .withDescription("Current weather for a city")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("city")
                .withType(ParameterType.STRING)
                .withRequired(true)
                .build())
        .withExecutor((args, ctx) -> ToolResult.success("72°F in " + args.get("city")))
        .build();
```

A tool reaches a session as a `ToolBinding`: `withTool(ToolBinding.newBuilder(tool).withCategory(category).build())`.
The category (`READ`, `WRITE`, `EXECUTION`, `NETWORK` or `CONTROL`) sets the permission default.
The session runs each tool call once and never retries one: `ToolDispatch` runs the tool on a
virtual thread bounded by the per-call timeout (`SessionLimits.toolTimeoutDefault`), and turns an
unknown tool, a timeout or a thrown exception into a failed result the model sees.

## CommandGrant: a CLI without its secrets

`CommandGrant` produces a `Tool` that lets the model invoke a single CLI binary under tight
controls. It must be built with the secret registry of the sessions that run it; `build()`
registers every `withEnv(...)` value there, and the session redacts the tool's output against it.
The value of `GH_TOKEN` below never reaches the model:

<!-- snippet: ToolSnippets#commandGrant -->
```java
var registry = new SecretRegistry();
var gh =
    CommandGrant.builder("gh")
        .withSecretRegistry(registry)
        .withEnv("GH_TOKEN", System.getenv("GH_TOKEN"))
        .withTimeout(Duration.ofSeconds(30))
        .withMaxOutputBytes(50_000)
        .withArgValidator(
            args ->
                args.isEmpty() || !"auth".equals(args.get(0))
                    ? Optional.empty()
                    : Optional.of("'gh auth' is not allowed via this grant"))
        .build();

var options =
    SessionOptions.newBuilder()
        .withModel(model)
        .withSecretRegistry(registry)
        .withTool(
            ToolBinding.newBuilder(gh.toTool()).withCategory(ToolCategory.EXECUTION).build())
        .build();
```

`build()` without `withSecretRegistry` throws, and the tool refuses to start a process in a session
whose registry is a different instance. Hardening is on by default: the binary path is pinned at
build, arguments are passed as argv with no shell, the `ProcessBuilder` environment is cleared and
then given only the granted variables so the JVM's environment never leaks into the child, an
argument holding a registered secret value is refused (secrets travel only in the environment),
each call gets a temporary working directory unless `withCwd` sets one, output is capped (a stream
cut at the cap loses any trailing start of a registered secret), descendants are killed on
timeout, and stderr is hidden from the model unless `withStderrToModel(true)`. Defaults: a 30 s
timeout, 50,000 bytes of output and 4 concurrent calls per grant, a call past that refused at once.

## A curated corpus

The session's `Read`, `Grep` and `Glob` tools accept any `WorkspaceRoot`, not just the agent's
working directory. Point them at a curated reference tree to give the model lexical search over
content you supply, with no vector database for a bounded corpus. Their text output is redacted by
the session like every tool's, so with one `SecretRegistry` set on the session and on your
`CommandGrant`s, a token written to a file by `gh auth login` stays redacted when the agent later
reads it back.

<!-- snippet: ToolSnippets#curatedCorpus -->
```java
var corpus = WorkspaceRoot.of(Path.of("/var/kb/support"));
var options =
    SessionOptions.newBuilder()
        .withModel(model)
        .withSecretRegistry(registry)
        .withTool(ReadTool.binding(corpus))
        .withTool(GrepTool.binding(corpus))
        .withTool(GlobTool.binding(corpus))
        .withPermission(Permission.planMode())
        .build();
```

Path confinement is built into `WorkspaceRoot`; per-file size caps, per-output caps,
hidden-directory pruning, skipping of symlinks and special files during walks, and binary-file
skipping are built into the tools. `Read` cuts a line at 2,000 characters and returns at most
60,000 characters per page, naming the next `offset`. `Permission.planMode()` or a policy of your
own enforces read-only at the session level; there is no `Write` tool to disable. To track what the
agent read, register a `PostToolUseHook` on `Read`.

## Permissions

<!-- snippet: QuickStarts#permissions -->
```java
var policy =
    Permission.newBuilder(PermissionMode.DEFAULT)
        .withAllow(PermissionRule.of("Read", "./src/**"))
        .withAsk(PermissionRule.of("MemoryWrite", "/memories/**"))
        .withDeny(PermissionRule.of("Read", "**/secrets.*"))
        .build();

try (var session =
    AgentSession.open(SessionPresets.workspace(model, root).withPermission(policy).build())) {
  session.runBlocking(UserMessage.text("Summarise the public API under src."));
}
```

`Permission.newBuilder(mode)` builds a policy: `withAllow`, `withAsk` and `withDeny` each append to
the list they name, and `toBuilder()` layers rules onto a preset. A rule is `PermissionRule.of(tool,
glob)` or `PermissionRule.any(tool)`. Rule order, in every mode: **deny > ask > allow > the mode's
default for the tool's category**. A rule has no effect of its own; the list it sits in is its
effect. The modes:

| Mode | `READ`, `CONTROL` | `WRITE` | `EXECUTION` | `NETWORK` |
|---|---|---|---|---|
| `DEFAULT` | allow | ask | ask | ask |
| `ACCEPT_EDITS` | allow | allow | ask | ask |
| `PLAN` | allow | deny | deny | ask |
| `BYPASS_PERMISSIONS` | allow | allow | allow | allow |
| `LOCKED_DOWN` | deny | deny | deny | deny |

A session built without `withPermission` gets `Permission.empty(PermissionMode.DEFAULT)`. A
headless host, where no one answers, sets `Permission.empty(PermissionMode.BYPASS_PERMISSIONS)`, or
allow rules for the tools its runs need. The presets: `Permission.defaultInWorkspace()` is `DEFAULT`
with memory writes allowed on `/memories/**`; `Permission.planMode()` is an empty `PLAN` policy;
`Permission.lockedDown()` is `LOCKED_DOWN` with `Execute` and `AskUserQuestion` allowed. Ask
decisions surface as a `QuestionAsked` event the host answers with `session.answer(questionId,
response)`, whether or not the session registers `AskUserQuestion`. To test a policy without a
session, `policy.evaluate(key, category)` returns the decision the session would act on, naming the
deciding rule.

An allow never carves an exception out of a matching ask: "ask broadly, allow a narrow exception"
(`ASK Read(./secrets/**)` with `ALLOW Read(./secrets/README.md)`) asks for the exception too. Where
the mode already asks for the tool's category (`WRITE`, `EXECUTION`, `NETWORK` under `DEFAULT`),
drop the broad ask and keep the allow. For a `READ` or `CONTROL` tool, or under `LOCKED_DOWN`, there
is no equivalent: list the exception's siblings as asks instead. This is the order Claude Code
uses.

**Keys.** A path tool's key is the canonical path it touches, however the model spelled it: `.` or
`./a/b` in the workspace (`secrets/key`, `docs/../secrets/key`, an absolute path and a symlink to it
are all `./secrets/key`), `/memories/` or `/memories/a/b` in memory. A path that names no target is
refused before any rule is evaluated, and the tool runs on the path that was authorised. `Execute`'s
key is `<RUNTIME>/<script>` plus any extras the call sets, `Agent`'s `<name>` and `Skill`'s
`<skill>` or `<skill>/<file>`. A call
whose key carries a registered secret is refused.

**Globs** match that spelling: a trailing `/**` matches the path before it and everything below it
(`./**` matches `.`), `/**/` and a leading `**/` match zero or more segments, `*` and `?` stay within
a segment. Rules name real paths: a rule written through a symlink, such as `./link/**`, never
matches, since a key never goes through one, and creation cannot detect that. A rule for a path
tool that no canonical path can match, such as `Read("secrets/**")`, fails `AgentSession.open`, and
so does a deny or ask rule that names no tool the session has (built-ins included, and `Read` where
`Grep` is registered, since `Grep` returns `Read`'s content): a misspelt deny would protect nothing.
A rule names its tool exactly, case included; an allow rule may name any tool.

**Walking tools.** `Glob`, `Grep`, `LS` and a `MemoryRead` listing skip, without asking and in
every mode, every entry below their path that one of their own deny rules decides, and those their
own ask rules decide unless the walk itself was approved through one; `Grep`, which returns
contents, also skips every entry a `Read` deny or ask rule decides. They never descend into a
withheld directory, and end with `[withheld by permission policy: <n>]`. The decision that admitted
a walk's start covers what it finds except what those rules withhold; a mode or category default,
such as `LOCKED_DOWN`'s deny, is not a rule and withholds nothing.

**Memory** is its own namespace: a memory call is authorised by `MemoryRead`/`MemoryWrite` rules on
its `/memories/...` key, and a `Read` rule over the directory backing a filesystem memory store does
not apply to it. That directory, `<workspace>/.agent/memory`, is reachable only through
`MemoryRead`/`MemoryWrite`, in every workspace: `Read`, `LS`, `Glob` and `Grep` refuse a path in
it, however spelled, before any rule is evaluated, and their walks skip it, so `MemoryRead` rules
protect it. `Execute` is not covered; the sandbox is the boundary there.

## Hooks

Seven phases: `OnUserMessage`, `PreModelTurn`, `PostModelTurn`, `PreToolUse`, `PostToolUse` and
`PreStop` (each method returns its phase's outcome type, so the compiler lists what is legal), and
the observe-only `OnStreamEvent`. Hooks run by priority; the first non-`Continue` outcome wins and
is announced by a `HookFired` event. `PreToolUse` hooks chain `MutateArgs` and fail closed (a throw
or `null` is `Block`); other phases treat a throw or `null` as `Continue`; an `Error` always
escapes. This hook refuses a tool call whatever the policy says:

<!-- snippet: QuickStarts#hooks -->
```java
PreToolUseHook denyShell =
    (call, ctx) -> {
      if (call.name().equals("Execute")) {
        return HookOutcome.block("Execute disabled in this deployment");
      }
      return HookOutcome.cont();
    };

var session =
    AgentSession.open(SessionPresets.workspace(model, root).withHook(denyShell).build());
```

A `PreToolUse` hook's `Block` refuses the call and emits `QueryEvent.ToolBlocked`; an
`OnUserMessage` hook's `Block` drops the message and emits `QueryEvent.MessageBlocked`, so a UI can
show the drop. A `Stop` ends the run in `Success`, with the hook's text as its result.
