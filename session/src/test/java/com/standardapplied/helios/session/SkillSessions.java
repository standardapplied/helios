/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
import com.standardapplied.helios.session.store.MessageOrigin;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Sessions with skills over a recording model: the skills, calls, scripts and options the skill
 * session tests share, and what a run sent and answered.
 */
final class SkillSessions {

  static final String SID = "sess-skills";
  static final String INTRODUCTION =
      "The skills below hold instructions for specific tasks. When a task matches a skill's"
          + " description, call the Skill tool with the skill's name before you start, and follow"
          + " the instructions it returns.";
  static final Skill PDF = skill("pdf", "Extracts text from PDFs.", "Use pdfplumber.").build();
  static final Skill FORMS = skill("forms", "Fills in forms.", "Fill every field.").build();
  static final String PDF_LOADED =
      "<skill_content name=\"pdf\">\nUse pdfplumber.\n</skill_content>";
  static final String FORMS_LOADED =
      "<skill_content name=\"forms\">\nFill every field.\n</skill_content>";
  static final String PDF_ALREADY_LOADED =
      "Skill \"pdf\" is already loaded; its instructions are earlier in this conversation.";

  private SkillSessions() {}

  /** A model that records the tools of each call, then answers from its script. */
  record Recording(ScriptedModel script, List<List<Tool>> tools) implements Model {

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

    /** The system message of the first call. */
    Message system() {
      return calls().getFirst().getFirst();
    }
  }

  /** A file whose supplier counts its calls. */
  static final class CountedFile implements Supplier<String> {

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

  static Skill.Builder skill(String name, String description, String instructions) {
    return Skill.newBuilder()
        .withName(name)
        .withDescription(description)
        .withInstructions(instructions);
  }

  static Recording model(ScriptedModel.Builder script) {
    return new Recording(script.build());
  }

  /** A model that loads {@code pdf}, then answers {@code done}. */
  static Recording loadingPdf() {
    return model(
        ScriptedModel.newBuilder().withToolCallsTurn(load("c1", "pdf")).withTextTurn("done"));
  }

  static ToolCall load(String id, String name) {
    return new ToolCall(id, SkillTool.NAME, Map.of("skill", name));
  }

  static ToolCall read(String id, String name, String path) {
    return new ToolCall(id, SkillTool.NAME, Map.of("skill", name, "file", path));
  }

  static ToolCall echo(String id) {
    return new ToolCall(id, "echo", Map.of());
  }

  /** A {@code READ} tool named {@code name} that answers {@code output}. */
  static ToolBinding tool(String name, String output) {
    var tool =
        Tool.newBuilder()
            .withName(name)
            .withDescription("answers " + output)
            .withExecutor((args, ctx) -> ToolResult.success(output))
            .build();
    return ToolBinding.newBuilder(tool).withCategory(ToolCategory.READ).build();
  }

  static Message tagged(String content) {
    return Message.newBuilder()
        .withRole(Role.USER)
        .withContent(content)
        .withMetadata(MessageOrigin.SKILL.metadata())
        .build();
  }

  static Message summary(String gist) {
    return Message.user(SummarizingCompactor.SUMMARY_PREFIX + gist);
  }

  static SessionOptions.Builder options(Recording model, Skill... skills) {
    return SessionOptions.newBuilder()
        .withModel(model)
        .withSessionId(SID)
        .withSystemPrompt("sys")
        .withTools(List.of(tool("echo", "ok")))
        .withSkills(List.of(skills));
  }

  /** Counts 96 of 100 tokens once the history holds {@code results} tool results, else 10. */
  static TokenCounter compactingAt(int results) {
    return messages ->
        messages.stream().filter(m -> m.role() == Role.TOOL).count() >= results ? 96L : 10L;
  }

  /** {@link #options}, compacting once the history holds a tool result. */
  static SessionOptions.Builder compacting(Recording model, Skill... skills) {
    return options(model, skills)
        .withLimits(SessionLimits.newBuilder().withMaxContextTokens(100L).build())
        .withTokenCounter(compactingAt(1));
  }

  static WriteToolSessions.Run run(Recording model, SessionOptions.Builder options) {
    return WriteToolSessions.run(model.script(), options.withModel(model));
  }

  /** The request {@code model} was last sent, after a run of {@code options} on {@code text}. */
  static List<Message> sent(Recording model, SessionOptions.Builder options, String text) {
    try (var session = AgentSession.open(options.withModel(model).build())) {
      assertInstanceOf(ResultMessage.Success.class, session.runBlocking(UserMessage.text(text)));
    }
    return model.calls().getLast();
  }

  /** The result of each tool call of {@code run}, by call id. */
  static Map<String, ToolResult> results(WriteToolSessions.Run run) {
    return run.events().eventsOf(QueryEvent.ToolResult.class).stream()
        .collect(Collectors.toMap(result -> result.call().id(), QueryEvent.ToolResult::result));
  }

  /** The outcome of each tool call of {@code run}, by call id. */
  static Map<String, ToolOutcome> outcomes(WriteToolSessions.Run run) {
    return results(run).entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().outcome()));
  }

  /** The text of each tool message of {@code request}, in order. */
  static List<String> toolTexts(List<Message> request) {
    return request.stream().filter(m -> m.role() == Role.TOOL).map(Message::content).toList();
  }
}
