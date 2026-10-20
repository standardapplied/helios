/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session;

import static com.standardapplied.helios.session.WriteToolSessions.answering;
import static com.standardapplied.helios.session.WriteToolSessions.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolOutcome;
import com.standardapplied.helios.session.execution.ExecuteTool;
import com.standardapplied.helios.session.memory.FileSystemMemoryBackend;
import com.standardapplied.helios.session.test.QuestionAnswers;
import com.standardapplied.helios.testing.ScriptedModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Each preset builds options from which a real session runs the tools it promises. */
final class SessionPresetsTest {

  private static final ToolCall WRITE_NOTE =
      new ToolCall(
          "c1",
          "MemoryWrite",
          Map.of("op", "create", "path", "/memories/note.md", "content", "remember this"));

  @TempDir Path root;

  private Path note() {
    return root.resolve(FileSystemMemoryBackend.STORAGE_SUBDIR).resolve("note.md");
  }

  @Test
  void minimalAnswersATextTurn() {
    var model = ScriptedModel.newBuilder().withTextTurn("hello").build();

    var run = run(model, SessionPresets.minimal(model));

    assertEquals("hello", assertInstanceOf(ResultMessage.Success.class, run.terminal()).result());
  }

  @Test
  void readOnlyReadsAFileInTheRoot() throws IOException {
    Files.writeString(root.resolve("notes.txt"), "preset content", StandardCharsets.UTF_8);
    var model = answering(new ToolCall("c1", "Read", Map.of("path", "notes.txt")));

    var run = run(model, SessionPresets.readOnly(model, root));

    assertInstanceOf(ResultMessage.Success.class, run.terminal());
    assertTrue(
        model.calls().get(1).stream()
            .anyMatch(m -> m.content() != null && m.content().contains("preset content")),
        () -> "second call: " + model.calls().get(1));
  }

  @Test
  void readOnlyRegistersNoMemoryTools() {
    var model = answering(WRITE_NOTE);

    var run = run(model, SessionPresets.readOnly(model, root));

    assertEquals(new ToolOutcome.NotFound(), run.onlyResult().outcome());
    assertFalse(Files.exists(note()));
  }

  @Test
  void workspaceWritesAMemoryNote() throws IOException {
    var model = answering(WRITE_NOTE);

    var run = run(model, SessionPresets.workspace(model, root));

    assertInstanceOf(ResultMessage.Success.class, run.terminal());
    assertEquals("remember this", Files.readString(note(), StandardCharsets.UTF_8));
  }

  @Test
  void openEndedExecutesThroughTheProviderOnceAllowed() {
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var model =
        answering(
            new ToolCall("c1", ExecuteTool.NAME, Map.of("runtime", "bash", "script", "echo hi")));

    var run =
        run(
            model,
            SessionPresets.openEnded(model, root, provider),
            session -> QuestionAnswers.selecting(session, "Allow"));

    assertInstanceOf(ResultMessage.Success.class, run.terminal());
    assertEquals(1, run.events().eventsOf(QueryEvent.QuestionAsked.class).size());
    assertEquals(List.of("echo hi"), provider.executed.stream().map(r -> r.script()).toList());
  }

  static Stream<Arguments> nullArguments() {
    var model = ScriptedModel.newBuilder().withTextTurn("unused").build();
    var provider = new RecordingProvider(new CopyOnWriteArrayList<>());
    var root = Path.of(".");
    return Stream.of(
        nullArgument("minimal", "model", () -> SessionPresets.minimal(null)),
        nullArgument("readOnly", "model", () -> SessionPresets.readOnly(null, root)),
        nullArgument("readOnly", "root", () -> SessionPresets.readOnly(model, null)),
        nullArgument("workspace", "model", () -> SessionPresets.workspace(null, root)),
        nullArgument("workspace", "root", () -> SessionPresets.workspace(model, null)),
        nullArgument("openEnded", "model", () -> SessionPresets.openEnded(null, root, provider)),
        nullArgument("openEnded", "root", () -> SessionPresets.openEnded(model, null, provider)),
        nullArgument("openEnded", "provider", () -> SessionPresets.openEnded(model, root, null)));
  }

  private static Arguments nullArgument(String preset, String argument, Executable call) {
    return Arguments.of(preset, argument, call);
  }

  @ParameterizedTest(name = "{0} refuses a null {1}")
  @MethodSource("nullArguments")
  void aNullArgumentIsRefusedByName(String preset, String argument, Executable call) {
    var thrown = assertThrows(NullPointerException.class, call);

    assertEquals(argument + " must not be null", thrown.getMessage());
  }
}
