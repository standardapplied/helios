/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.session.tools.ToolCategory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class AskUserQuestionToolTest {

  private static Map<String, Object> option(String label, String description) {
    var m = new LinkedHashMap<String, Object>();
    m.put("label", label);
    m.put("description", description);
    return m;
  }

  private static QuestionGateway stub(AskUserQuestionResponse response) {
    return req ->
        new AskUserQuestionResponse(
            req.questionId(), response.selectedLabels(), response.customText());
  }

  @Test
  void bindingMetadata() {
    var binding =
        AskUserQuestionTool.binding(req -> AskUserQuestionResponse.single(req.questionId(), "A"));
    assertEquals("AskUserQuestion", binding.name());
    assertEquals("AskUserQuestion", AskUserQuestionTool.NAME);
    assertEquals(ToolCategory.CONTROL, binding.category());
    assertEquals("AskUserQuestion", binding.permissionKey(Map.of()).toolName());
    assertEquals("", binding.permissionKey(Map.of()).canonicalArgs());
  }

  @Test
  void singleSelectFlow() {
    var captured = new AtomicReference<AskUserQuestionRequest>();
    QuestionGateway gateway =
        req -> {
          captured.set(req);
          return AskUserQuestionResponse.single(req.questionId(), "Yes");
        };
    var binding = AskUserQuestionTool.binding(gateway);

    var args =
        Map.<String, Object>of(
            "question",
            "Continue?",
            "options",
            List.of(option("Yes", "go on"), option("No", "stop")));
    var result = binding.tool().execute(args);

    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("user selected:"));
    assertTrue(result.output().contains("- Yes"));
    var req = captured.get();
    assertEquals("Continue?", req.question());
    assertEquals(2, req.options().size());
    assertEquals("Yes", req.options().get(0).label());
    assertFalse(req.multiSelect());
  }

  @Test
  void multiSelectFlowEmitsCustomText() {
    QuestionGateway gateway =
        req -> new AskUserQuestionResponse(req.questionId(), List.of("A", "B"), "extra note");
    var binding = AskUserQuestionTool.binding(gateway);

    var args =
        Map.<String, Object>of(
            "question",
            "Pick all that apply",
            "options",
            List.of(option("A", "desc A"), option("B", "desc B"), option("C", "desc C")),
            "multiSelect",
            true);
    var result = binding.tool().execute(args);

    assertTrue(result.success(), result.output());
    assertTrue(result.output().contains("- A"));
    assertTrue(result.output().contains("- B"));
    assertTrue(result.output().contains("custom text:"));
    assertTrue(result.output().contains("extra note"));
  }

  @Test
  void missingQuestionFails() {
    var result =
        AskUserQuestionTool.binding(stub(AskUserQuestionResponse.single("q", "A")))
            .tool()
            .execute(Map.of("options", List.of(option("A", ""), option("B", ""))));
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'question'"), result.output());
  }

  @Test
  void missingOptionsFails() {
    var result =
        AskUserQuestionTool.binding(stub(AskUserQuestionResponse.single("q", "A")))
            .tool()
            .execute(Map.of("question", "q?"));
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'options'"), result.output());
  }

  @Test
  void wrongOptionsTypeFails() {
    var result =
        AskUserQuestionTool.binding(stub(AskUserQuestionResponse.single("q", "A")))
            .tool()
            .execute(Map.of("question", "q?", "options", "not-an-array"));
    assertFalse(result.success());
    assertTrue(result.output().contains("must be an array"), result.output());
  }

  @Test
  void nonObjectOptionEntryFails() {
    var result =
        AskUserQuestionTool.binding(stub(AskUserQuestionResponse.single("q", "A")))
            .tool()
            .execute(Map.of("question", "q?", "options", List.of("plain-string")));
    assertFalse(result.success());
    assertTrue(result.output().contains("each option must be an object"), result.output());
  }

  @Test
  void missingLabelFails() {
    var result =
        AskUserQuestionTool.binding(stub(AskUserQuestionResponse.single("q", "A")))
            .tool()
            .execute(
                Map.of(
                    "question", "q?", "options", List.of(Map.of("description", "no label here"))));
    assertFalse(result.success());
    assertTrue(result.output().contains("'label' is required"), result.output());
  }

  @Test
  void invalidRequestSurfacesAsFailure() {
    // Only one option — fails AskUserQuestionRequest validation.
    var result =
        AskUserQuestionTool.binding(stub(AskUserQuestionResponse.single("q", "A")))
            .tool()
            .execute(Map.of("question", "q?", "options", List.of(option("A", ""))));
    assertFalse(result.success());
    assertTrue(result.output().contains("between 2 and 4 entries"), result.output());
  }

  @Test
  void interruptedSurfacesAsFailure() {
    QuestionGateway gateway =
        req -> {
          throw new InterruptedException("from test");
        };
    var result =
        AskUserQuestionTool.binding(gateway)
            .tool()
            .execute(
                Map.of("question", "q?", "options", List.of(option("A", ""), option("B", ""))));
    assertFalse(result.success());
    assertTrue(result.output().contains("interrupted"), result.output());
    // Clear the interrupt status set by the executor.
    Thread.interrupted();
  }

  @Test
  void cancelledSurfacesAsFailure() {
    QuestionGateway gateway =
        req -> {
          throw new CancellationException("from test");
        };
    var result =
        AskUserQuestionTool.binding(gateway)
            .tool()
            .execute(
                Map.of("question", "q?", "options", List.of(option("A", ""), option("B", ""))));
    assertFalse(result.success());
    assertTrue(result.output().contains("cancelled"), result.output());
  }

  @Test
  void rejectsNullGateway() {
    assertThrows(NullPointerException.class, () -> AskUserQuestionTool.binding(null));
  }
}
