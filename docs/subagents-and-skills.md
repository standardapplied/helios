# Subagents and skills

A session can hand a task to a *subagent*, a child session the model starts through the built-in
`Agent` tool, and can offer the model *skills*, instructions in the [Agent Skills](https://agentskills.io)
format that it loads through the built-in `Skill` tool when a task matches. Both are opt-in session
options in `helios-session`; the optional `helios-skills` module reads skills from folders on disk.
Sessions themselves are covered in the [sessions guide](sessions.md). Back to the
[README](../README.md).

- [Subagents](#subagents)
- [Skills in a session](#skills-in-a-session)
- [Loading skills from folders](#loading-skills-from-folders)

## Subagents

A subagent is a child agent the model delegates a task to. `withSubagent(new Subagent(name,
description, options))` registers the built-in `Agent` tool, whose call names the subagent and gives
it a prompt; each call runs one fresh child session from the subagent's own options, waits for its
run and closes it. Nothing is inherited from the parent: the child's model, prompt, tools, skills,
permission, limits, output schema and store are all in its options.

<!-- snippet: QuickStarts#subagents -->
```java
var researcher =
    new Subagent(
        "researcher",
        "reads the repository and answers one question about it",
        SessionPresets.readOnly(model, repo).build());
var options =
    SessionPresets.minimal(model)
        .withSubagent(researcher)
        .withPermission(
            Permission.newBuilder(PermissionMode.DEFAULT)
                .withAllow(PermissionRule.of("Agent", "researcher"))
                .build())
        .build();
try (var session = AgentSession.open(options)) {
  System.out.println(session.send("Which classes form the session's public API?").join());
}
```

- **Result.** A child's `Success` is the call's result (its JSON under an output schema); any other
  terminal, a child that cannot open and an unknown name are failures the model reads. The child's
  secret registry redacts what it answers before the parent's does.
- **Spend.** The child's usage and cost are added to the parent run's as it spends, so the parent's
  `ResultMessage` and budget count them; under `maxBudgetMicroUsd` the child gets at most what the
  run has left, and with nothing left the call fails without starting one. Under a budget every
  subagent needs a cost calculator.
- **Time and cancellation.** The call's deadline is the subagent's `maxWallClock` plus its
  `stopGrace`, not `toolTimeoutDefault`. An interrupt, a close, the deadline or the parent's wall
  clock interrupts the child, which ends `Cancelled`; at the deadline the call waits the subagent's
  `stopGrace` for it to close.
- **Permissions.** `Agent` is an `EXECUTION` tool keyed `Agent(<name>)`, so a rule allows, asks or
  denies one subagent, and hooks apply as to any tool. A child has no one to answer: its `ASK` is
  denied, so allow in its own permission what it needs.
- **Skills.** A child sees only the skills in its subagent's own options, never the parent's.
- **Linkage.** The child's run span nests under the call's `execute_tool` span and carries
  `helios.parent.session_id`, `helios.parent.run_id` and `helios.parent.tool_call_id`;
  `PgTraceStore.list` with the filter `attributes eq "${\"helios.parent.session_id\":\"<id>\"}"`
  finds a session's children.

Helios runs subagents in the foreground of the parent's call. Background work, delivery into the
parent (`send`), caps on concurrent children and idempotency are the host's job, and a child
session the host starts itself is linked with its own `withTraceAttributes` labels.

## Skills in a session

A skill is a name, a description and instructions, with optional files. Build one with
`Skill.newBuilder()`: `withName`, `withDescription` and `withInstructions` are required;
`withDirectory(path)` says where its files live, and `withFile(path, content)` (or
`withFiles(map)`) adds a file, its content a `Supplier<String>` called each time the model reads
that file. A `Skill` shared by several sessions has its suppliers called concurrently. A name is at
most 64 characters of `[a-z0-9]+(-[a-z0-9]+)*` and a description at most 1,024 characters; a file
path or the directory holds no `<`, `>`, `"` and no control, format or line or paragraph separator
character, so the model sees each exactly as it must spell it. `withSkill(skill)` appends one and
`withSkills(list)` replaces them. No preset adds a skill: adding one is your trust decision, since
what a skill says reaches the model as instructions.

<!-- snippet: QuickStarts#skills -->
```java
var releaseNotes =
    Skill.newBuilder()
        .withName("release-notes")
        .withDescription("Writes release notes from the commits since the last tag.")
        .withInstructions(
            "List the commits since the last tag, group them by module and write one line for"
                + " each change a user of the library would notice.")
        .build();
var options = SessionPresets.workspace(model, repo).withSkill(releaseNotes).build();
try (var session = AgentSession.open(options)) {
  System.out.println(session.send("Write the notes for the next release.").join());
}
```

- **Catalog.** The session's one system message is your prompt, a blank line, a line telling the
  model to load a matching skill before it starts, a blank line, then an `<available_skills>` list
  of each skill's name and XML-escaped description, in `withSkills` order.
  `SessionOptions.systemPrompt()` stays your text. A session without skills sends exactly what it
  sent before: no catalog and no tool.
- **The `Skill` tool.** `{"skill": "<name>"}` answers the skill's instructions inside
  `<skill_content name="<name>">`, followed by its directory and its `<skill_resources>` list of
  files when it has them. Once the skill is loaded in the conversation, `{"skill": "<name>", "file":
  "<path>"}` answers one listed file inside `<skill_file>`, calling only that file's supplier;
  before, it answers that the skill must be loaded first, and a path that is not exactly one of the
  skill's files reads nothing. A skill already loaded in the conversation answers that it is
  loaded. Results are redacted, cut to `maxToolResultChars` and evented like any tool's:
  instructions longer than that are cut once, and the cut part is not served again.
- **Permissions.** `Skill` is a `READ` tool keyed `Skill(<name>)` or `Skill(<name>/<file>)`, so it
  runs without asking in every mode but `LOCKED_DOWN`. Since a skill's files are readable only once
  it is loaded, denying `Skill(pdf)` (`PermissionRule.of("Skill", "pdf")`) covers the skill and its
  files, while a rule over `Skill(pdf/**)` restricts its files alone; hooks apply as to any tool. A
  tool of yours named `Skill` collides only in a session with skills.
- **Compaction.** When a compaction removes what the model loaded, the session puts each such skill
  back right after the system message, as a user message tagged `MessageOrigin.SKILL` holding the
  text the model was given, whatever compactor you set; it survives any number of compactions.
  Skills put back come in the order they were first loaded, before any activation your compactor
  kept. A `PreModelTurnHook`'s `MutateHistory` is yours: nothing is put back after it.
- **Reopening.** What the model loaded is read from the history, so a reopened session needs
  nothing more in its store. A skill no longer in the options stays in the conversation as sent,
  but cannot be loaded and is not put back; changed instructions reach the model only in a new
  session. Changing a session's skills or their descriptions between runs changes its system prompt
  and tool list: on Anthropic models that bind reasoning the next request drops earlier reasoning
  once, with one cache miss, and Gemini resends the whole conversation once.
- **Subagents.** A child sees only the skills in its subagent's own options, never the parent's.
- **Scripts.** Helios runs no skill code. A script runs through `Execute`, under your permission
  policy, at the path the activation's "Skill directory" names; making that path visible to your
  execution provider's sandbox is yours. To offer a user-typed `/skill-name`, find the skill in
  `options.skills()` and send its `instructions()` in a message of your own; such a message is not
  a load, so a compaction does not put it back.

## Loading skills from folders

The optional `helios-skills` artifact reads Agent Skills folders, each a `SKILL.md` of YAML
frontmatter between `---` lines and a Markdown body, into `Skill` values.

<!-- snippet: QuickStarts#discoveredSkills -->
```java
var options = SessionPresets.workspace(model, repo).withSkills(Skills.discover(repo)).build();
try (var session = AgentSession.open(options)) {
  System.out.println(session.send("Write the notes for the next release.").join());
}
```

- **Two calls.** `Skills.discover(repo)` reads `~/.agents/skills`, `~/.claude/skills`,
  `<repo>/.agents/skills` and `<repo>/.claude/skills`, in that order (`~` is the JVM's `user.home`,
  read on each call). `Skills.load(folders...)` reads the folders you name, in your order; each must
  exist and may itself be a skill's folder. `Skills.load(repo.resolve(".agents/skills"))` reads a
  project's skills without the user's.
- **Which skill wins.** When two skills share a name the first found wins and the other is left out
  with a warning, so a user skill wins over a project skill: a repository cannot take the place of a
  skill the user installed. This is Claude Code's order, not the agentskills.io guide's suggestion
  of project over user.
- **A snapshot.** A call reads each `SKILL.md` and the text of each file once, and nothing touches
  the disk after it returns: a loaded skill keeps working if its folder is changed or deleted, and
  does not see the change. Call again to pick up changes, and build new sessions with the result.
- **What is found.** Every directory up to four levels below a folder that holds a `SKILL.md` is a
  skill, and its regular files up to four levels below it are its files; a `SKILL.md` inside a
  skill is one of that skill's files, not a second skill. Hidden entries and `node_modules` are
  skipped. The four folders and the ones passed to `load` are resolved, so a link there is
  followed, but a project folder only while its real path stays inside the project. Below them no
  link is followed.
- **Bounds.** A `SKILL.md` of at most 1 MiB; 2,000 directories and 10,000 files per folder; 1,000
  files per skill; 16 MiB of file bytes per call (about twice that in heap), past which the call
  stops and returns the skills loaded before it.
- **Listed but not readable.** A file larger than 60,000 bytes, the largest page the `Read` tool
  returns, chosen so a file fits whole under the default 65,536-character tool-result cap, or one
  that is not UTF-8 text, such as an image, stays listed for a script to use at its path; reading it
  through `Skill` answers why it cannot.
- **Lenient reading.** A name unlike its directory's and unknown frontmatter keys still load
  (`allowed-tools` is ignored; your `Permission` stays the only authority). A `SKILL.md` without
  valid frontmatter (a duplicated key or a YAML alias included), or whose name, description or
  directory `Skill` refuses, is skipped and the others load; a file `Skill` refuses or that cannot
  be read is left out of its skill.
- **Trust.** Loading is your decision. A repository's `.agents/skills` and `.claude/skills` are
  written by its author: their instructions reach the model, and their scripts may be proposed to
  `Execute`. What `discover` finds reaches a session only when you pass it to `withSkills`; to keep
  a project's folders out, call `load` with folders of your own.
- **Load skills once**, at startup or while no session can write those folders, and reuse the
  returned list across sessions: skills are immutable values. A process rewriting a folder during
  the call can make the loader serve any file the JVM can read as a skill's file, or block the call
  forever; call it from a platform thread, not a virtual one. After the call returns, nothing on
  disk can affect a loaded skill.
- **Scripts** run as described under [Skills in a session](#skills-in-a-session), through an
  `ExecutionProvider` whose sandbox sees the skill's folder; for a sandbox that mounts the skills at
  another path, rebuild each skill with that path, `skill.toBuilder().withDirectory(mount).build()`.
- **Warnings.** Every skip and leniency is a `WARNING` on the logger
  `com.standardapplied.helios.skills.Skills`, the only report; attach a `java.util.logging.Handler`
  to collect them.
