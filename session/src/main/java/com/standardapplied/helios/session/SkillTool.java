/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.store.MessageOrigin;
import com.standardapplied.helios.session.tools.ToolArgs;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The skills of a session, as {@link SessionOptions.Builder#withSkills} describes: the catalog its
 * system prompt ends with, the built-in {@code Skill} tool that loads a skill or reads one of its
 * files, and the compactor wrapper that puts back what a compaction removed of the skills the model
 * loaded. Which skills are loaded is read from the history alone: a {@code Skill} tool result, or a
 * user message tagged {@link MessageOrigin#SKILL}, that opens with a session skill's {@code
 * <skill_content>} tag. The tool reaches its run's history through {@link Run#stateOfThisThread}.
 */
final class SkillTool {

  /** The stable tool name advertised to the model. */
  static final String NAME = "Skill";

  private static final String INTRODUCTION =
      "The skills below hold instructions for specific tasks. When a task matches a skill's"
          + " description, call the Skill tool with the skill's name before you start, and follow"
          + " the instructions it returns.";

  private final Map<String, Skill> skills = new LinkedHashMap<>();

  /** The tool over {@code skills}. */
  SkillTool(List<Skill> skills) {
    skills.forEach(skill -> this.skills.put(skill.name(), skill));
  }

  /**
   * The session's one system prompt: the host's, then a blank line and the catalog of the options'
   * skills; the catalog alone without the host's; the host's alone without skills.
   */
  static Optional<String> systemPrompt(SessionOptions options) {
    if (options.skills().isEmpty()) {
      return options.systemPrompt();
    }
    var catalog = catalog(options.skills());
    return Optional.of(options.systemPrompt().map(host -> host + "\n\n" + catalog).orElse(catalog));
  }

  /** The binding the session registers when it has a skill. */
  ToolBinding binding() {
    var tool =
        Tool.newBuilder()
            .withName(NAME)
            .withDescription(
                "Loads a skill: its instructions, which you follow for the task, and the list of"
                    + " its files. With 'file', reads one of the skill's listed files instead.")
            .withParameters(
                List.of(
                    ToolParameter.newBuilder()
                        .withName("skill")
                        .withType(ParameterType.STRING)
                        .withDescription("The skill's name, one of " + skills.keySet())
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("file")
                        .withType(ParameterType.STRING)
                        .withDescription(
                            "A path from the skill's <skill_resources> list, to read that file")
                        .withRequired(false)
                        .build()))
            .withExecutor((args, context) -> execute(args))
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.READ)
        .withPermissionKeyExtractor(SkillTool::key)
        .build();
  }

  /**
   * {@code compactor}, followed by putting back, right after the compacted history's leading system
   * messages, one tagged user message per skill loaded before the compaction and not after it,
   * carrying its latest activation's text, in the order of each skill's first activation.
   */
  ContextCompactor keeping(ContextCompactor compactor) {
    return (history, context) -> putBack(history, compactor.compact(history, context));
  }

  private CompactionResult putBack(List<Message> history, CompactionResult result) {
    if (result == null || result.history() == history) {
      return result;
    }
    var compacted = result.history();
    var lost = activations(history);
    lost.keySet().removeAll(activations(compacted).keySet());
    if (lost.isEmpty()) {
      return result;
    }
    var systemMessages = SummarizingCompactor.leadingSystemMessages(compacted);
    var restored = new ArrayList<>(compacted.subList(0, systemMessages));
    lost.values().forEach(activation -> restored.add(reattached(activation.content())));
    restored.addAll(compacted.subList(systemMessages, compacted.size()));
    return new CompactionResult(List.copyOf(restored), result.usage(), result.modelId());
  }

  private static Message reattached(String content) {
    return Message.newBuilder()
        .withRole(Role.USER)
        .withContent(content)
        .withMetadata(MessageOrigin.SKILL.metadata())
        .build();
  }

  /** Each skill activated in {@code history}, in first-activation order, to its latest one. */
  private Map<String, Message> activations(List<Message> history) {
    var activations = new LinkedHashMap<String, Message>();
    for (var message : history) {
      if (carriesActivation(message)) {
        skills.keySet().stream()
            .filter(name -> message.content().startsWith(opening(name)))
            .findFirst()
            .ifPresent(name -> activations.put(name, message));
      }
    }
    return activations;
  }

  private static boolean carriesActivation(Message message) {
    var carrier =
        message.role() == Role.TOOL && NAME.equals(message.toolName())
            || message.role() == Role.USER
                && MessageOrigin.of(message).equals(Optional.of(MessageOrigin.SKILL));
    return carrier && message.content() != null;
  }

  private ToolResult execute(Map<String, Object> args) {
    var name = ToolArgs.stringArg(args, "skill");
    if (Strings.isBlank(name)) {
      return ToolResult.failure("Skill: missing required 'skill'");
    }
    var skill = skills.get(name);
    if (skill == null) {
      return ToolResult.failure(
          "Skill: no skill is named \"" + name + "\"; the skills are " + skills.keySet());
    }
    var file = ToolArgs.stringArg(args, "file");
    if (!Strings.isBlank(file)) {
      return read(skill, file);
    }
    if (activations(Run.stateOfThisThread().history().snapshot()).containsKey(name)) {
      return ToolResult.success(
          "Skill \""
              + name
              + "\" is already loaded; its instructions are earlier in this conversation.");
    }
    return ToolResult.success(content(skill));
  }

  private static ToolResult read(Skill skill, String path) {
    var supplier = skill.files().get(path);
    if (supplier == null) {
      return ToolResult.failure(
          "Skill: skill \""
              + skill.name()
              + "\" has no file \""
              + path
              + "\"; load the skill to list its files");
    }
    var text =
        Objects.requireNonNull(
            supplier.get(), () -> "skill \"" + skill.name() + "\" file \"" + path + "\" is null");
    return ToolResult.success(
        "<skill_file skill=\""
            + skill.name()
            + "\" path=\""
            + escaped(path)
            + "\">\n"
            + text
            + "\n</skill_file>");
  }

  private static String content(Skill skill) {
    var content =
        new StringBuilder(opening(skill.name()))
            .append('\n')
            .append(skill.instructions())
            .append('\n');
    skill
        .directory()
        .ifPresent(
            directory ->
                content
                    .append("\nSkill directory: ")
                    .append(escaped(directory))
                    .append(
                        "\nRelative paths in this skill are relative to the skill directory.\n"));
    if (!skill.files().isEmpty()) {
      content.append("\n<skill_resources>\n");
      skill.files().keySet().stream()
          .sorted()
          .forEach(path -> content.append("<file>").append(escaped(path)).append("</file>\n"));
      content.append("</skill_resources>\n");
    }
    return content.append("</skill_content>").toString();
  }

  private static String catalog(List<Skill> skills) {
    var catalog = new StringBuilder(INTRODUCTION).append("\n\n<available_skills>\n");
    for (var skill : skills) {
      catalog
          .append("  <skill>\n    <name>")
          .append(skill.name())
          .append("</name>\n    <description>")
          .append(escaped(skill.description()))
          .append("</description>\n  </skill>\n");
    }
    return catalog.append("</available_skills>").toString();
  }

  private static String opening(String name) {
    return "<skill_content name=\"" + name + "\">";
  }

  private static String escaped(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static ToolPermissionKey key(Map<String, Object> args) {
    var skill = ToolArgs.stringArg(args, "skill");
    var file = ToolArgs.stringArg(args, "file");
    return new ToolPermissionKey(NAME, Strings.isBlank(file) ? skill : skill + "/" + file);
  }
}
