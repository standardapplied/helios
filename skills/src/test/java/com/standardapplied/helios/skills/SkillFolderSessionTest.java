/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.skills;

import static com.standardapplied.helios.skills.SkillFolders.directory;
import static com.standardapplied.helios.skills.SkillFolders.real;
import static com.standardapplied.helios.skills.SkillFolders.skillFile;
import static com.standardapplied.helios.skills.SkillFolders.write;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.ResultMessage;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.execution.LocalProcessExecutionProvider;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.permissions.PermissionMode;
import com.standardapplied.helios.testing.ScriptedModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A skill folder loaded from disk works end to end in a session: the model activates it, reads one
 * of its files through {@code Skill} and runs its script through {@code Execute} at the path the
 * activation named.
 */
final class SkillFolderSessionTest {

  @TempDir Path temp;

  @Test
  void aLoadedFolderActivatesReadsAFileAndRunsItsScript() {
    var folder = directory(temp.resolve("skills/greeting"));
    write(
        folder.resolve("SKILL.md"),
        skillFile(
            "name: greeting\ndescription: Greets.",
            "Read references/guide.md, then run scripts/hello.sh."));
    write(folder.resolve("references/guide.md"), "Say hello.");
    write(folder.resolve("scripts/hello.sh"), "echo hello from skill\n");
    var directory = real(folder);
    var skills = Skills.load(temp.resolve("skills"));
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "Skill", Map.of("skill", "greeting")))
            .withToolCallsTurn(
                new ToolCall(
                    "c2", "Skill", Map.of("skill", "greeting", "file", "references/guide.md")))
            .withToolCallsTurn(
                new ToolCall(
                    "c3",
                    "Execute",
                    Map.of("runtime", "BASH", "script", "bash " + directory + "/scripts/hello.sh")))
            .withTextTurn("Hello.")
            .build();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSkills(skills)
            .withExecutionProvider(LocalProcessExecutionProvider.defaultPosix())
            .withPermission(Permission.empty(PermissionMode.BYPASS_PERMISSIONS))
            .build();

    ResultMessage result;
    try (var session = AgentSession.open(options)) {
      result = session.runBlocking(UserMessage.text("Greet me."));
    }

    assertInstanceOf(ResultMessage.Success.class, result);
    var system = model.calls().getFirst().getFirst();
    assertEquals(Role.SYSTEM, system.role());
    assertTrue(
        system.content().contains("<name>greeting</name>\n    <description>Greets.</description>"),
        system::content);
    var tools =
        model.calls().getLast().stream()
            .filter(message -> message.role() == Role.TOOL)
            .map(message -> message.content().replaceFirst("duration \\d+ms", "duration Nms"))
            .toList();
    assertEquals(
        List.of(
            "<skill_content name=\"greeting\">\n"
                + "Read references/guide.md, then run scripts/hello.sh.\n"
                + "\n"
                + "Skill directory: "
                + directory
                + "\n"
                + "Relative paths in this skill are relative to the skill directory.\n"
                + "\n"
                + "<skill_resources>\n"
                + "<file>references/guide.md</file>\n"
                + "<file>scripts/hello.sh</file>\n"
                + "</skill_resources>\n"
                + "</skill_content>",
            "<skill_file skill=\"greeting\" path=\"references/guide.md\">\n"
                + "Say hello.\n"
                + "</skill_file>",
            "[runtime BASH exit 0 duration Nms]\nhello from skill\n"),
        tools);
  }
}
