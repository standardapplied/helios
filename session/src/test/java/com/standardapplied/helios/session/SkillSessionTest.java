/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.test.BindingCalls.keyOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.context.TokenCounter;
import com.standardapplied.helios.core.model.CloseableIterator;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.hooks.HookOutcome;
import com.standardapplied.helios.session.hooks.PreModelTurnHook;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.session.permissions.PermissionRule;
import com.standardapplied.helios.session.store.InMemorySessionStore;
import com.standardapplied.helios.session.store.MessageOrigin;
import com.standardapplied.helios.session.store.SessionEntry;
import com.standardapplied.helios.session.store.SessionStore;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A session with skills: the catalog its one system message ends with, the {@code Skill} tool's
 * activations, file reads, failures, keys and dedupe, the skills a compaction puts back, whatever
 * the compactor, and what a reopened session and a session without skills send.
 */
final class SkillSessionTest {

  private static final String SID = "sess-skills";
  private static final String INTRODUCTION =
      "The skills below hold instructions for specific tasks. When a task matches a skill's"
          + " description, call the Skill tool with the skill's name before you start, and follow"
          + " the instructions it returns.";
  private static final Skill PDF = Skill.of("pdf", "Extracts text from PDFs.", "Use pdfplumber.");
  private static final Skill FORMS = Skill.of("forms", "Fills in forms.", "Fill every field.");
  private static final String PDF_LOADED =
      "<skill_content name=\"pdf\">\nUse pdfplumber.\n</skill_content>";
  private static final String FORMS_LOADED =
      "<skill_content name=\"forms\">\nFill every field.\n</skill_content>";

  /** A model that records the tools of each call, then answers from its script. */
  private record Recording(ScriptedModel script, List<List<Tool>> tools) implements Model {

    Recording(ScriptedModel script) {
      this(script, new CopyOnWriteArrayList<>());
    }

    @Override
    public CloseableIterator<ModelChunk> chatStream(List<Message> messages, List<Tool> tools) {
      this.tools.add(List.copyOf(tools));
      return script.chatStream(messages, tools);
    }

    @Override
    public String id() {
      return script.id();
    }

    @Override
    public String provider() {
      return script.provider();
    }

    List<List<Message>> calls() {
      return script.calls();
    }

    List<String> toolNames(int call) {
      return tools.get(call).stream().map(Tool::name).toList();
    }
  }

  /** A file whose supplier counts its calls. */
  private static final class CountedFile implements Supplier<String> {

    private final String text;
    final AtomicInteger reads = new AtomicInteger();

    CountedFile(String text) {
      this.text = text;
    }

    @Override
    public String get() {
      reads.incrementAndGet();
      return text;
    }
  }

  private static Recording model(ScriptedModel.Builder script) {
    return new Recording(script.build());
  }

  private static ToolCall skill(String id, String name) {
    return new ToolCall(id, SkillTool.NAME, Map.of("skill", name));
  }

  private static ToolCall file(String id, String name, String path) {
    return new ToolCall(id, SkillTool.NAME, Map.of("skill", name, "file", path));
  }

  private static ToolCall echo(String id) {
    return new ToolCall(id, "echo", Map.of());
  }

  private static ToolBinding echoTool() {
    var tool =
        Tool.newBuilder()
            .withName("echo")
            .withDescription("answers ok")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    return ToolBinding.newBuilder(tool).withCategory(ToolCategory.READ).build();
  }

  private static ToolBinding hostTool(String name) {
    var tool =
        Tool.newBuilder()
            .withName(name)
            .withDescription("host")
            .withExecutor((args, ctx) -> ToolResult.success("ok"))
            .build();
    return ToolBinding.newBuilder(tool).withCategory(ToolCategory.READ).build();
  }

  private static Message tagged(String content) {
    return Message.newBuilder()
        .withRole(Role.USER)
        .withContent(content)
        .withMetadata(MessageOrigin.SKILL.metadata())
        .build();
  }

  private static Message summary(String gist) {
    return Message.user(SummarizingCompactor.SUMMARY_PREFIX + gist);
  }

  private static SessionOptions.Builder options(Recording model, Skill... skills) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(SID)
        .withSystemPrompt("sys")
        .withTools(List.of(echoTool()))
        .withSkills(List.of(skills));
  }

  /** Counts 96 of 100 tokens once the history holds {@code results} tool results, else 10. */
  private static TokenCounter compactingAt(int results) {
    return messages ->
        messages.stream().filter(m -> m.role() == Role.TOOL).count() >= results ? 96L : 10L;
  }

  /** {@link #options}, compacting once the history holds a tool result. */
  private static SessionOptions.Builder compacting(Recording model, Skill... skills) {
    return options(model, skills)
        .withLimits(SessionLimits.newBuilder().withMaxContextTokens(100L).build())
        .withTokenCounter(compactingAt(1));
  }

  private static WriteToolSessions.Run run(Recording model, SessionOptions.Builder options) {
    return WriteToolSessions.run(model.script(), options.withModel(model));
  }

  /** The result of each tool call of {@code run}, by call id. */
  private static Map<String, ToolResult> results(WriteToolSessions.Run run) {
    return run.events().eventsOf(QueryEvent.ToolResult.class).stream()
        .collect(Collectors.toMap(result -> result.call().id(), QueryEvent.ToolResult::result));
  }

  // ── catalog ─────────────────────────────────────────────────────────────

  @Test
  void theCatalogFollowsTheHostPromptInTheOneSystemMessageInWithSkillsOrder() {
    var model = model(ScriptedModel.newBuilder().withTextTurn("done"));
    var quoting = Skill.of("a1", "a <b> & \"c\"", "");

    run(model, options(model, PDF, quoting));

    var expected =
        "sys\n\n"
            + INTRODUCTION
            + "\n\n<available_skills>\n"
            + "  <skill>\n"
            + "    <name>pdf</name>\n"
            + "    <description>Extracts text from PDFs.</description>\n"
            + "  </skill>\n"
            + "  <skill>\n"
            + "    <name>a1</name>\n"
            + "    <description>a &lt;b&gt; &amp; &quot;c&quot;</description>\n"
            + "  </skill>\n"
            + "</available_skills>";
    assertEquals(List.of(Message.system(expected), Message.user("go")), model.calls().getFirst());
    assertEquals(List.of("echo", "Skill"), model.toolNames(0));
  }

  @Test
  void withoutAHostPromptTheCatalogIsTheSystemMessage() {
    var model = model(ScriptedModel.newBuilder().withTextTurn("done"));

    run(model, options(model, PDF).withSystemPrompt(null));

    assertEquals(
        Message.system(
            INTRODUCTION
                + "\n\n<available_skills>\n"
                + "  <skill>\n"
                + "    <name>pdf</name>\n"
                + "    <description>Extracts text from PDFs.</description>\n"
                + "  </skill>\n"
                + "</available_skills>"),
        model.calls().getFirst().getFirst());
  }

  @Test
  void withoutSkillsTheRequestIsTheHostPromptAndToolsAlone() {
    var withSkills = model(ScriptedModel.newBuilder().withTextTurn("done"));
    var cleared = model(ScriptedModel.newBuilder().withTextTurn("done"));
    var never = model(ScriptedModel.newBuilder().withTextTurn("done"));
    var echo = echoTool();

    run(withSkills, options(withSkills, PDF).withTools(List.of(echo)));
    run(cleared, options(cleared, PDF).withTools(List.of(echo)).withSkills(List.of()));
    run(never, SessionOptions.newBuilder().withSystemPrompt("sys").withTools(List.of(echo)));

    var request = List.of(Message.system("sys"), Message.user("go"));
    assertEquals(
        List.of(request, request), List.of(cleared.calls().getFirst(), never.calls().getFirst()));
    assertEquals(
        List.of(List.of(echo.tool()), List.of(echo.tool())),
        List.of(cleared.tools().getFirst(), never.tools().getFirst()));
    assertEquals(echo.tool(), withSkills.tools().getFirst().getFirst());
  }

  @Test
  void aHostToolNamedSkillCollidesOnlyInASessionWithSkills() {
    var model = model(ScriptedModel.newBuilder().withTextTurn("done"));
    var withSkills = options(model, PDF).withTool(hostTool(SkillTool.NAME)).build();

    var refused = assertThrows(IllegalArgumentException.class, () -> AgentSession.open(withSkills));

    assertEquals("duplicate tool name in registry: Skill", refused.getMessage());
    var run = run(model, options(model).withTool(hostTool(SkillTool.NAME)));
    assertInstanceOf(ResultMessage.Success.class, run.terminal());
  }

  // ── the tool ────────────────────────────────────────────────────────────

  @Test
  void theToolIsAReadToolWithARequiredSkillAndAnOptionalFile() {
    var binding = new SkillTool(List.of(PDF, FORMS)).binding();
    var tool = binding.tool();

    assertEquals(ToolCategory.READ, binding.category());
    assertEquals("Skill", tool.name());
    assertEquals(
        "Loads a skill: its instructions, which you follow for the task, and the list of its"
            + " files. With 'file', reads one of the skill's listed files instead.",
        tool.description());
    assertEquals(
        List.of(
            "skill true The skill's name, one of [pdf, forms]",
            "file false A path from the skill's <skill_resources> list, to read that file"),
        tool.parameters().stream()
            .map(p -> p.name() + " " + p.required() + " " + p.description())
            .toList());
  }

  @Test
  void theKeyIsTheSkillOrTheSkillAndItsFile() {
    var binding = new SkillTool(List.of(PDF)).binding();

    assertEquals(
        List.of(
            new ToolPermissionKey("Skill", "pdf"),
            new ToolPermissionKey("Skill", "pdf"),
            new ToolPermissionKey("Skill", "pdf/scripts/x.py")),
        List.of(
            keyOf(binding, Map.of("skill", "pdf")),
            keyOf(binding, Map.of("skill", "pdf", "file", " ")),
            keyOf(binding, Map.of("skill", "pdf", "file", "scripts/x.py"))));
  }

  @Test
  void anActivationAnswersTheInstructionsTheDirectoryAndTheFilesInOrderReadingNone() {
    var forms = new CountedFile("the forms");
    var script = new CountedFile("print(1)");
    var pdf =
        new Skill(
            "pdf",
            "Extracts text from PDFs.",
            "Use pdfplumber.",
            Optional.of("/skills/pdf & co"),
            Map.of("scripts/x.py", script, "reference/a&b.md", forms, "FORMS.md", forms));
    var model =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));

    run(model, options(model, pdf));

    assertEquals(
        Message.tool(
            "c1",
            "Skill",
            "<skill_content name=\"pdf\">\n"
                + "Use pdfplumber.\n"
                + "\n"
                + "Skill directory: /skills/pdf &amp; co\n"
                + "Relative paths in this skill are relative to the skill directory.\n"
                + "\n"
                + "<skill_resources>\n"
                + "<file>FORMS.md</file>\n"
                + "<file>reference/a&amp;b.md</file>\n"
                + "<file>scripts/x.py</file>\n"
                + "</skill_resources>\n"
                + "</skill_content>"),
        model.calls().get(1).getLast());
    assertEquals(List.of(0, 0), List.of(forms.reads.get(), script.reads.get()));
  }

  @Test
  void anActivationWithADirectoryAndNoFilesHasNoResources() {
    var pdf =
        new Skill(
            "pdf",
            "Extracts text from PDFs.",
            "Use pdfplumber.",
            Optional.of("/skills/pdf"),
            Map.of());
    var model =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));

    run(model, options(model, pdf));

    assertEquals(
        "<skill_content name=\"pdf\">\n"
            + "Use pdfplumber.\n"
            + "\n"
            + "Skill directory: /skills/pdf\n"
            + "Relative paths in this skill are relative to the skill directory.\n"
            + "</skill_content>",
        model.calls().get(1).getLast().content());
  }

  @Test
  void anActivationWithoutADirectoryOrFilesIsTheInstructionsAlone() {
    var model =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));

    var run = run(model, options(model, PDF));

    assertEquals(PDF_LOADED, model.calls().get(1).getLast().content());
    assertEquals(new ToolOutcome.Succeeded(), run.onlyResult().outcome());
  }

  @Test
  void aFileReadAnswersTheWrappedTextCallingOnlyThatSupplier() {
    var read = new CountedFile("the forms");
    var other = new CountedFile("print(1)");
    var pdf =
        new Skill(
            "pdf",
            "Extracts text from PDFs.",
            "Use pdfplumber.",
            Optional.empty(),
            Map.of("reference/a&b.md", read, "scripts/x.py", other));
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(file("c1", "pdf", "reference/a&b.md"))
                .withTextTurn("done"));

    run(model, options(model, pdf));

    assertEquals(
        "<skill_file skill=\"pdf\" path=\"reference/a&amp;b.md\">\nthe forms\n</skill_file>",
        model.calls().get(1).getLast().content());
    assertEquals(List.of(1, 0), List.of(read.reads.get(), other.reads.get()));
  }

  @Test
  void eachFailureAnswersItsTextAndReadsNothing() {
    var read = new CountedFile("text");
    var pdf = new Skill("pdf", "Extracts text from PDFs.", "", Optional.empty(), Map.of("x", read));
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(
                    skill("c1", "nope"),
                    file("c2", "pdf", "missing.md"),
                    file("c3", "pdf", "../x"),
                    new ToolCall("c4", SkillTool.NAME, Map.of()))
                .withTextTurn("done"));

    var results = results(run(model, options(model, PDF, FORMS).withSkills(List.of(pdf, FORMS))));

    assertEquals(
        Map.of(
            "c1",
            ToolResult.failure("Skill: no skill is named \"nope\"; the skills are [pdf, forms]"),
            "c2",
            ToolResult.failure(
                "Skill: skill \"pdf\" has no file \"missing.md\"; load the skill to list its files"),
            "c3",
            ToolResult.failure(
                "Skill: skill \"pdf\" has no file \"../x\"; load the skill to list its files"),
            "c4",
            ToolResult.failure("Skill: missing required 'skill'")),
        results);
    assertEquals(0, read.reads.get());
  }

  @Test
  void aSupplierThatThrowsOrAnswersNullEndsTheCallAsThrew() {
    Supplier<String> throwing =
        () -> {
          throw new IllegalStateException("gone");
        };
    Supplier<String> answeringNull = () -> null;
    var pdf =
        new Skill(
            "pdf",
            "Extracts text from PDFs.",
            "",
            Optional.empty(),
            Map.of("throws.md", throwing, "null.md", answeringNull));
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(file("c1", "pdf", "throws.md"), file("c2", "pdf", "null.md"))
                .withTextTurn("done"));

    var results = results(run(model, options(model, pdf)));

    assertEquals(
        Map.of(
            "c1",
            new ToolOutcome.Threw("java.lang.IllegalStateException"),
            "c2",
            new ToolOutcome.Threw("java.lang.NullPointerException")),
        results.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().outcome())));
  }

  // ── permissions ─────────────────────────────────────────────────────────

  @Test
  void aDenyRuleOverASkillRefusesItsActivationAndItsFiles() {
    var read = new CountedFile("text");
    var secret = new Skill("secret", "Holds secrets.", "", Optional.empty(), Map.of("a.md", read));
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(skill("c1", "secret"), file("c2", "secret", "a.md"))
                .withTextTurn("done"));
    var denying =
        new Permission(
            PermissionMode.DEFAULT,
            List.of(),
            List.of(),
            List.of(PermissionRule.of("Skill", "secret/**")));

    var results = results(run(model, options(model, secret).withPermission(denying)));

    assertEquals(
        Map.of(
            "c1",
            new ToolOutcome.Rejected(
                "Permission", "deny rule Skill(secret/**) matched Skill(secret)"),
            "c2",
            new ToolOutcome.Rejected(
                "Permission", "deny rule Skill(secret/**) matched Skill(secret/a.md)")),
        results.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().outcome())));
    assertEquals(0, read.reads.get());
  }

  @Test
  void lockedDownRefusesTheToolUnlessARuleAllowsIt() {
    var refusing =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));
    var allowing =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));

    var refused =
        run(
            refusing,
            options(refusing, PDF).withPermission(Permission.empty(PermissionMode.LOCKED_DOWN)));
    var allowed =
        run(
            allowing,
            options(allowing, PDF)
                .withPermission(
                    new Permission(
                        PermissionMode.LOCKED_DOWN,
                        List.of(PermissionRule.any("Skill")),
                        List.of(),
                        List.of())));

    assertEquals(
        new ToolOutcome.Rejected(
            "Permission",
            "LOCKED_DOWN mode forbids READ by default (Skill(pdf)) — add an explicit allow rule to"
                + " open this tool"),
        refused.onlyResult().outcome());
    assertEquals(ToolResult.success(PDF_LOADED), allowed.onlyResult());
  }

  // ── dedupe ──────────────────────────────────────────────────────────────

  @Test
  void aSecondActivationInALaterTurnAnswersThatTheSkillIsLoaded() {
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(skill("c1", "pdf"))
                .withToolCallsTurn(skill("c2", "pdf"))
                .withTextTurn("done"));

    run(model, options(model, PDF));

    assertEquals(
        List.of(
            PDF_LOADED,
            "Skill \"pdf\" is already loaded; its instructions are earlier in this conversation."),
        model.calls().getLast().stream()
            .filter(m -> m.role() == Role.TOOL)
            .map(Message::content)
            .toList());
  }

  @Test
  void aSecondActivationInTheSameRoundAnswersThatTheSkillIsLoadedAndComesBackOnce() {
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(skill("c1", "pdf"), skill("c2", "pdf"))
                .withTextTurn("the gist")
                .withTextTurn("done"));

    var run = run(model, compacting(model, PDF));

    assertEquals(
        Map.of(
            "c1",
            ToolResult.success(PDF_LOADED),
            "c2",
            ToolResult.success(
                "Skill \"pdf\" is already loaded; its instructions are earlier in this"
                    + " conversation.")),
        results(run));
    assertEquals(
        List.of(model.calls().getFirst().getFirst(), tagged(PDF_LOADED), summary("the gist")),
        model.calls().getLast());
  }

  // ── compaction ──────────────────────────────────────────────────────────

  @Test
  void theDefaultCompactorGetsTheActivationAsTheModelWasGivenItRedactedAndCut() {
    var secrets = new SecretRegistry();
    secrets.register("DB_PASSWORD", "hunter2-hunter2");
    var pdf =
        Skill.of(
            "pdf", "Extracts text from PDFs.", "Sign with hunter2-hunter2.\n" + "x".repeat(3_000));
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(skill("c1", "pdf"))
                .withToolCallsTurn(echo("c2"))
                .withTextTurn("the gist")
                .withTextTurn("done"));

    run(
        model,
        compacting(model, pdf)
            .withTokenCounter(compactingAt(2))
            .withSecretRegistry(secrets)
            .withLimits(
                SessionLimits.newBuilder()
                    .withMaxContextTokens(100L)
                    .withMaxToolResultChars(1_024)
                    .build()));

    var given = model.calls().get(1).getLast();
    assertEquals(Message.tool("c1", "Skill", given.content()), given);
    assertTrue(
        given
            .content()
            .startsWith("<skill_content name=\"pdf\">\nSign with <redacted:DB_PASSWORD>."),
        given::content);
    assertTrue(given.content().contains("[output truncated: "), given::content);
    assertEquals(
        List.of(model.calls().getFirst().getFirst(), tagged(given.content()), summary("the gist")),
        model.calls().getLast());
  }

  @Test
  void aCustomCompactorGetsTheSkillsBackAfterItsSystemMessage() {
    var model =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));
    run(
        model,
        compacting(model, PDF)
            .withContextCompactor(
                (history, context) ->
                    CompactionResult.noOp(List.of(history.getFirst(), history.get(1)))));

    var system = model.calls().getFirst().getFirst();
    assertEquals(List.of(system, tagged(PDF_LOADED), Message.user("go")), model.calls().getLast());
  }

  @Test
  void aCompactorThatKeepsNoSystemMessageGetsTheSkillsFirst() {
    var model =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));
    run(
        model,
        compacting(model, PDF)
            .withContextCompactor(
                (history, context) -> CompactionResult.noOp(List.of(history.get(1)))));

    assertEquals(List.of(tagged(PDF_LOADED), Message.user("go")), model.calls().getLast());
  }

  @Test
  void aCompactorThatChangesNothingGetsNothingPutBack() {
    var identity =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));
    var disabled =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));
    var answeringNull =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));

    var unchanged =
        run(
            identity,
            compacting(identity, PDF)
                .withContextCompactor((history, context) -> CompactionResult.noOp(history)));
    var off =
        run(disabled, compacting(disabled, PDF).withContextCompactor(ContextCompactor.disabled()));
    var nothing =
        run(
            answeringNull,
            compacting(answeringNull, PDF).withContextCompactor((history, context) -> null));

    for (var model : List.of(identity, disabled, answeringNull)) {
      assertEquals(
          List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL),
          model.calls().getLast().stream().map(Message::role).toList());
    }
    assertEquals(
        List.of(List.of(), List.of(), List.of()),
        List.of(
            unchanged.events().eventsOf(QueryEvent.ContextEdited.class),
            off.events().eventsOf(QueryEvent.ContextEdited.class),
            nothing.events().eventsOf(QueryEvent.ContextEdited.class)));
  }

  @Test
  void aTaggedMessageWithoutContentIsNoActivation() {
    var model =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));
    var empty =
        Message.newBuilder()
            .withRole(Role.USER)
            .withMetadata(MessageOrigin.SKILL.metadata())
            .build();

    run(
        model,
        compacting(model, PDF)
            .withContextCompactor(
                (history, context) ->
                    CompactionResult.noOp(List.of(history.getFirst(), empty, history.get(1)))));

    assertEquals(
        List.of(model.calls().getFirst().getFirst(), tagged(PDF_LOADED), empty, Message.user("go")),
        model.calls().getLast());
  }

  @Test
  void skillsComeBackInFirstActivationOrderOnceAfterEveryCompaction() {
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(skill("c1", "forms"), skill("c2", "pdf"))
                .withTextTurn("first gist")
                .withToolCallsTurn(echo("c3"))
                .withTextTurn("second gist")
                .withTextTurn("done"));

    run(model, compacting(model, PDF, FORMS));

    var system = model.calls().getFirst().getFirst();
    assertEquals(
        List.of(system, tagged(FORMS_LOADED), tagged(PDF_LOADED), summary("first gist")),
        model.calls().get(2));
    assertEquals(
        List.of(system, tagged(FORMS_LOADED), tagged(PDF_LOADED), summary("second gist")),
        model.calls().getLast());
  }

  @Test
  void aFileReadAndAFailedActivationPutNothingBack() {
    var pdf =
        new Skill(
            "pdf", "Extracts text from PDFs.", "", Optional.empty(), Map.of("a.md", () -> "text"));
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(file("c1", "pdf", "a.md"), skill("c2", "nope"))
                .withTextTurn("the gist")
                .withTextTurn("done"));

    run(model, compacting(model, pdf));

    assertEquals(
        List.of(model.calls().getFirst().getFirst(), summary("the gist")), model.calls().getLast());
  }

  @Test
  void aMutateHistoryHookThatDropsTheActivationGetsNothingPutBack() {
    var model =
        model(
            ScriptedModel.newBuilder()
                .withToolCallsTurn(skill("c1", "pdf"))
                .withToolCallsTurn(echo("c2"))
                .withTextTurn("the gist")
                .withTextTurn("done"));
    PreModelTurnHook droppingTheActivation =
        (history, context) ->
            context.turnIndex() == 2
                ? HookOutcome.mutateHistory(history.subList(0, 2))
                : HookOutcome.cont();
    TokenCounter afterTheEcho =
        messages -> messages.stream().anyMatch(m -> "echo".equals(m.toolName())) ? 96L : 10L;

    run(
        model,
        compacting(model, PDF).withTokenCounter(afterTheEcho).withHook(droppingTheActivation));

    assertEquals(
        List.of(model.calls().getFirst().getFirst(), summary("the gist")), model.calls().getLast());
  }

  // ── reopening ───────────────────────────────────────────────────────────

  /** A stored session in which the model loaded {@code pdf}, without compacting. */
  private static void loadedIn(SessionStore store) {
    var model =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c1", "pdf")).withTextTurn("done"));
    run(model, reopened(model, store, PDF));
  }

  /**
   * Options over {@code store} that compact while the history holds an assistant turn and ends with
   * a user message starting {@code compact}.
   */
  private static SessionOptions.Builder reopened(
      Recording model, SessionStore store, Skill... skills) {
    TokenCounter whenAsked =
        messages ->
            messages.getLast().role() == Role.USER
                    && messages.getLast().content().startsWith("compact")
                    && messages.stream().anyMatch(m -> m.role() == Role.ASSISTANT)
                ? 96L
                : 10L;
    return compacting(model, skills).withStore(store).withTokenCounter(whenAsked);
  }

  private static List<Message> sent(Recording model, SessionOptions.Builder options, String text) {
    try (var session = AgentSession.open(options.build())) {
      assertInstanceOf(ResultMessage.Success.class, session.runBlocking(UserMessage.text(text)));
    }
    return model.calls().getLast();
  }

  @Test
  void aReopenedSessionGetsTheSkillBackFromItsStoredHistory() {
    var store = new InMemorySessionStore();
    loadedIn(store);
    var model = model(ScriptedModel.newBuilder().withTextTurn("the gist").withTextTurn("done"));

    var request = sent(model, reopened(model, store, PDF), "compact now");

    assertEquals(
        List.of(
            request.getFirst(),
            tagged(PDF_LOADED),
            summary("the gist"),
            Message.user("compact now")),
        request);
  }

  @Test
  void aSecondReopenGetsTheSkillBackFromTheTaggedMessageOnce() {
    var store = new RecordingStore();
    loadedIn(store);
    var first = model(ScriptedModel.newBuilder().withTextTurn("the gist").withTextTurn("done"));
    sent(first, reopened(first, store, PDF), "compact now");
    var second = model(ScriptedModel.newBuilder().withTextTurn("second gist").withTextTurn("done"));

    var request = sent(second, reopened(second, store, PDF), "compact again");

    var replaced =
        store.batches.stream()
            .flatMap(List::stream)
            .filter(SessionEntry.HistoryReplaced.class::isInstance)
            .map(entry -> ((SessionEntry.HistoryReplaced) entry).history())
            .toList();
    assertEquals(tagged(PDF_LOADED), replaced.getFirst().getFirst());
    assertEquals(
        List.of(
            request.getFirst(),
            tagged(PDF_LOADED),
            summary("second gist"),
            Message.user("compact again")),
        request);
  }

  @Test
  void aSkillRemovedFromTheOptionsIsNotPutBackAndCannotBeLoaded() {
    var store = new InMemorySessionStore();
    loadedIn(store);
    var model =
        model(
            ScriptedModel.newBuilder()
                .withTextTurn("the gist")
                .withToolCallsTurn(skill("c2", "pdf"))
                .withTextTurn("done"));

    sent(model, reopened(model, store, FORMS), "compact now");

    var afterTheCompaction = model.calls().get(1);
    assertEquals(
        List.of(afterTheCompaction.getFirst(), summary("the gist"), Message.user("compact now")),
        afterTheCompaction);
    assertEquals(
        Message.tool("c2", "Skill", "Skill: no skill is named \"pdf\"; the skills are [forms]"),
        model.calls().get(2).getLast());
  }

  @Test
  void aReopenedSessionAnswersThatALoadedSkillIsLoaded() {
    var store = new InMemorySessionStore();
    loadedIn(store);
    var model =
        model(
            ScriptedModel.newBuilder().withToolCallsTurn(skill("c2", "pdf")).withTextTurn("done"));

    var request = sent(model, reopened(model, store, PDF), "again");

    assertEquals(
        Message.tool(
            "c2",
            "Skill",
            "Skill \"pdf\" is already loaded; its instructions are earlier in this conversation."),
        request.getLast());
  }
}
