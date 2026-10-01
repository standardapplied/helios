/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AskUserQuestionValueTypesTest {

  // ── AskUserQuestionOption ────────────────────────────────────────────────

  @Test
  void optionStoresFields() {
    var o = new AskUserQuestionOption("label", "desc");
    assertEquals("label", o.label());
    assertEquals("desc", o.description());
  }

  @Test
  void optionOfDefaultsDescription() {
    assertEquals("", AskUserQuestionOption.of("L").description());
  }

  @Test
  void optionRejectsNullLabel() {
    assertThrows(NullPointerException.class, () -> new AskUserQuestionOption(null, ""));
  }

  @Test
  void optionRejectsBlankLabel() {
    assertThrows(IllegalArgumentException.class, () -> new AskUserQuestionOption("   ", ""));
  }

  @Test
  void optionRejectsOverlongLabel() {
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> new AskUserQuestionOption("x".repeat(81), ""));
    assertEquals("label must be at most 80 chars, got 81", ex.getMessage());
  }

  @Test
  void optionRejectsNullDescription() {
    assertThrows(NullPointerException.class, () -> new AskUserQuestionOption("L", null));
  }

  // ── AskUserQuestionRequest ───────────────────────────────────────────────

  @Test
  void requestStoresFieldsAndCopiesOptions() {
    var mutable = new ArrayList<AskUserQuestionOption>();
    mutable.add(AskUserQuestionOption.of("A"));
    mutable.add(AskUserQuestionOption.of("B"));
    var r = new AskUserQuestionRequest("qid", "hdr", "Pick one", mutable, false);
    mutable.add(AskUserQuestionOption.of("C"));
    assertEquals(2, r.options().size());
    assertEquals("qid", r.questionId());
    assertEquals("hdr", r.header());
    assertEquals("Pick one", r.question());
    assertEquals(false, r.multiSelect());
  }

  @Test
  void requestRejectsBlankQuestionId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AskUserQuestionRequest(
                " ",
                "",
                "q?",
                List.of(AskUserQuestionOption.of("A"), AskUserQuestionOption.of("B")),
                false));
  }

  @Test
  void requestRejectsOverlongHeader() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new AskUserQuestionRequest(
                    "qid",
                    "x".repeat(13),
                    "q?",
                    List.of(AskUserQuestionOption.of("A"), AskUserQuestionOption.of("B")),
                    false));
    assertEquals("header must be at most 12 chars, got 13", ex.getMessage());
  }

  @Test
  void requestRejectsBlankQuestion() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AskUserQuestionRequest(
                "qid",
                "",
                "  ",
                List.of(AskUserQuestionOption.of("A"), AskUserQuestionOption.of("B")),
                false));
  }

  @Test
  void requestRejectsTooFewOptions() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AskUserQuestionRequest(
                "qid", "", "q?", List.of(AskUserQuestionOption.of("A")), false));
  }

  @Test
  void requestRejectsTooManyOptions() {
    var five = new ArrayList<AskUserQuestionOption>();
    for (var i = 0; i < 5; i++) {
      five.add(AskUserQuestionOption.of("O" + i));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new AskUserQuestionRequest("qid", "", "q?", five, false));
  }

  @Test
  void requestRejectsNullOption() {
    var withNull = new ArrayList<AskUserQuestionOption>();
    withNull.add(AskUserQuestionOption.of("A"));
    withNull.add(null);
    assertThrows(
        NullPointerException.class,
        () -> new AskUserQuestionRequest("qid", "", "q?", withNull, false));
  }

  @Test
  void requestRejectsNullArguments() {
    assertThrows(
        NullPointerException.class,
        () ->
            new AskUserQuestionRequest(
                null,
                "",
                "q",
                List.of(AskUserQuestionOption.of("A"), AskUserQuestionOption.of("B")),
                false));
    assertThrows(
        NullPointerException.class,
        () ->
            new AskUserQuestionRequest(
                "qid",
                null,
                "q",
                List.of(AskUserQuestionOption.of("A"), AskUserQuestionOption.of("B")),
                false));
    assertThrows(
        NullPointerException.class,
        () ->
            new AskUserQuestionRequest(
                "qid",
                "",
                null,
                List.of(AskUserQuestionOption.of("A"), AskUserQuestionOption.of("B")),
                false));
    assertThrows(
        NullPointerException.class, () -> new AskUserQuestionRequest("qid", "", "q", null, false));
  }

  // ── AskUserQuestionResponse ──────────────────────────────────────────────

  @Test
  void responseStoresFieldsAndCopiesLabels() {
    var labels = new ArrayList<String>();
    labels.add("A");
    var r = new AskUserQuestionResponse("qid", labels, "note");
    labels.add("B");
    assertEquals(1, r.selectedLabels().size());
    assertEquals("A", r.selectedLabels().get(0));
    assertEquals("note", r.customText());
    assertNotSame(labels, r.selectedLabels());
  }

  @Test
  void responseSingleFactory() {
    var r = AskUserQuestionResponse.single("qid", "A");
    assertEquals(List.of("A"), r.selectedLabels());
    assertEquals("", r.customText());
  }

  @Test
  void responseRejectsBlankQuestionId() {
    assertThrows(
        IllegalArgumentException.class, () -> new AskUserQuestionResponse(" ", List.of("A"), ""));
  }

  @Test
  void responseRejectsEmptyLabels() {
    assertThrows(
        IllegalArgumentException.class, () -> new AskUserQuestionResponse("qid", List.of(), ""));
  }

  @Test
  void responseRejectsNullLabel() {
    var labels = new ArrayList<String>();
    labels.add(null);
    assertThrows(NullPointerException.class, () -> new AskUserQuestionResponse("qid", labels, ""));
  }

  @Test
  void responseRejectsBlankLabel() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new AskUserQuestionResponse("qid", List.of("  "), ""));
  }

  @Test
  void responseRejectsNullCustomText() {
    assertThrows(
        NullPointerException.class, () -> new AskUserQuestionResponse("qid", List.of("A"), null));
  }

  @Test
  void responseRejectsNullArguments() {
    assertThrows(
        NullPointerException.class, () -> new AskUserQuestionResponse(null, List.of("A"), ""));
    assertThrows(NullPointerException.class, () -> new AskUserQuestionResponse("qid", null, ""));
  }
}
