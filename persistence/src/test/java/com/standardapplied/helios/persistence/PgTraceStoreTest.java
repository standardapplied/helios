/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.persistence;

import static com.standardapplied.helios.persistence.ExportedSpans.run;
import static com.standardapplied.helios.persistence.ExportedSpans.step;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.common.Paginate;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.persistence.mapper.JsonbMapper;
import com.standardapplied.scimsql.FilterSyntaxException;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link PgTraceStore} reads back the runs and spans {@link PgSpanExporter} stored. */
class PgTraceStoreTest {

  private final PgTraceStore store = new PgTraceStore(PgTestSupport.pgConfig());
  private final PgSpanExporter exporter = new PgSpanExporter(PgTestSupport.pgConfig());

  @BeforeEach
  void setUp() {
    PgTestSupport.truncateTraces();
  }

  /**
   * Store run {@code index}, named {@code name}, on {@code model}, starting {@code index} minutes
   * in.
   */
  private void seed(int index, String name, String model) {
    var attributes = Attributes.of(AttributeKey.stringKey("gen_ai.request.model"), model);
    var span = run("run-" + index, "%016x".formatted(index + 1), name, index * 60, attributes);
    assertTrue(exporter.export(List.of(span)).isSuccess());
  }

  @Test
  void anUnknownRunIsNotFound() {
    assertNull(store.findById("run-unknown"));
  }

  @Test
  void aRunsSpansAreOrderedByStartTimeThenId() {
    var spans =
        List.of(
            step("run-0", "00000000000000a1", "hook late", null, 3, Attributes.empty()),
            step("run-0", "00000000000000b1", "hook tied", null, 2, Attributes.empty()),
            step("run-0", "00000000000000a9", "chat tied", "chat", 2, Attributes.empty()),
            step("run-0", "00000000000000c9", "chat first", "chat", 1, Attributes.empty()),
            step("run-other", "00000000000000c1", "chat other", "chat", 0, Attributes.empty()));
    assertTrue(exporter.export(spans).isSuccess());
    seed(0, "agent", "scripted");

    assertEquals(
        List.of("chat first", "chat tied", "hook tied", "hook late"),
        store.findById("run-0").spans().stream().map(Span::name).toList());
  }

  @Test
  void aStoredTotalThatDisagreesWithTheCountsIsNotReadIntoTheUsage() {
    var attributes =
        Attributes.builder()
            .put("gen_ai.usage.input_tokens", 10L)
            .put("gen_ai.usage.output_tokens", 2L)
            .build();
    assertTrue(
        exporter
            .export(List.of(run("run-0", "0000000000000001", "agent", 0, attributes)))
            .isSuccess());
    PgTestSupport.dbClient()
        .execute()
        .dml("UPDATE helios_traces SET total_tokens = 99 WHERE id = ?", "run-0");

    assertEquals(Usage.of(10, 2), store.findById("run-0").usage());
  }

  @Test
  void aFailingDatabaseIsAPgExceptionForEachRead() {
    var failing = new PgTraceStore(new PgConfig(PgTestSupport.failingWrites(true), null, null));

    assertEquals(
        "Failed to find trace: run-0",
        assertThrows(PgException.class, () -> failing.findById("run-0")).getMessage());
    assertThrows(PgException.class, () -> failing.list(Paginate.of(), null));
    assertThrows(
        PgException.class, () -> failing.summarize(TraceRollupKey.NAME, TraceFilter.none()));
  }

  @Test
  void listWithoutFilterReturnsPaginatedResultsNewestFirst() {
    for (int i = 0; i < 5; i++) {
      seed(i, "trace-" + i, "scripted");
    }

    var result = store.list(new Paginate(1, 3), null);

    assertEquals(3, result.items().size());
    assertTrue(result.hasMore());
    assertEquals(
        List.of("trace-4", "trace-3", "trace-2"),
        result.items().stream().map(Trace::name).toList());
    assertTrue(result.items().getFirst().spans().isEmpty());
  }

  @Test
  void listSecondPageReturnsTail() {
    for (int i = 0; i < 5; i++) {
      seed(i, "trace-" + i, "scripted");
    }

    var result = store.list(new Paginate(2, 3), null);

    assertFalse(result.hasMore());
    assertEquals(List.of("trace-1", "trace-0"), result.items().stream().map(Trace::name).toList());
  }

  @Test
  void listWithNullPaginateUsesDefaults() {
    seed(0, "single", "scripted");

    var result = store.list(null, null);

    assertEquals(1, result.items().size());
    assertFalse(result.hasMore());
  }

  @Test
  void listEmptyTableReturnsEmptyList() {
    var result = store.list(Paginate.of(), null);

    assertTrue(result.items().isEmpty());
    assertFalse(result.hasMore());
  }

  @Test
  void listWithScimFilterByName() {
    seed(0, "my-agent", "scripted");
    seed(1, "other-agent", "scripted");

    var result = store.list(Paginate.of(), "name eq \"my-agent\"");

    assertEquals(List.of("my-agent"), result.items().stream().map(Trace::name).toList());
  }

  @Test
  void listWithScimFilterByModelId() {
    seed(0, "gemini-trace", "gemini-3.8-flash");
    seed(1, "other-trace", "gpt-4o");

    var result = store.list(Paginate.of(), "model_id eq \"gemini-3.8-flash\"");

    assertEquals(List.of("gemini-3.8-flash"), result.items().stream().map(Trace::modelId).toList());
  }

  @Test
  void listWithBlankFilterReturnsAll() {
    seed(0, "trace-1", "scripted");
    seed(1, "trace-2", "scripted");

    assertEquals(2, store.list(Paginate.of(), "  ").items().size());
  }

  @Test
  void listWithInvalidFilterThrows() {
    assertThrows(PgException.class, () -> store.list(Paginate.of(), "invalid filter gibberish!!!"));
  }

  @Test
  void listRefusesFilterWithTrailingInput() {
    seed(0, "my-agent", "scripted");

    var e =
        assertThrows(
            PgException.class,
            () -> store.list(Paginate.of(), "name eq \"my-agent\") or (name pr"));

    assertInstanceOf(FilterSyntaxException.class, e.getCause());
  }

  /** Store run {@code index}, starting {@code index} minutes in, labelled {@code labels}. */
  private void label(int index, Map<String, String> labels) {
    var attributes = Attributes.builder();
    labels.forEach(attributes::put);
    var span =
        run("run-" + index, "%016x".formatted(index + 1), "agent", index * 60, attributes.build());
    assertTrue(exporter.export(List.of(span)).isSuccess());
  }

  /**
   * The filter that selects the runs labelled {@code labels}: their JSON object, written by a JSON
   * library, with each backslash and quote escaped for the filter text.
   */
  private static String labelled(Map<String, String> labels) {
    var json = JsonbMapper.toJsonb(labels).replace("\\", "\\\\").replace("\"", "\\\"");
    return "attributes eq \"$" + json + "\"";
  }

  private List<String> listed(String filter) {
    return store.list(Paginate.of(), filter).items().stream().map(Trace::id).toList();
  }

  private void seedLabelledRuns() {
    label(0, Map.of("app.eval.case_id", "c-17", "app.eval.variant", "a"));
    label(1, Map.of("app.eval.case_id", "c-17", "app.eval.variant", "b"));
    label(2, Map.of("app.eval.case_id", "c-18", "app.eval.variant", "a"));
    label(3, Map.of());
    label(4, Map.of("app.note", "say \"hi\" \\ bye"));
  }

  @Test
  void aLabelFilterReturnsExactlyTheRunsCarryingTheLabel() {
    seedLabelledRuns();

    assertEquals(
        List.of("run-1", "run-0"),
        listed("attributes eq \"${\\\"app.eval.case_id\\\":\\\"c-17\\\"}\""));
    assertEquals(List.of("run-1", "run-0"), listed(labelled(Map.of("app.eval.case_id", "c-17"))));
    assertEquals(List.of(), listed(labelled(Map.of("app.eval.case_id", "c-99"))));
  }

  @Test
  void twoLabelsInOneObjectMustBothMatch() {
    seedLabelledRuns();

    assertEquals(
        List.of("run-1"),
        listed(labelled(Map.of("app.eval.case_id", "c-17", "app.eval.variant", "b"))));
  }

  @Test
  void twoLabelClausesJoinWithAnd() {
    seedLabelledRuns();

    assertEquals(
        List.of("run-0"),
        listed(
            labelled(Map.of("app.eval.case_id", "c-17"))
                + " and "
                + labelled(Map.of("app.eval.variant", "a"))));
  }

  @Test
  void aLabelValueHoldingAQuoteAndABackslashIsMatched() {
    seedLabelledRuns();

    assertEquals(List.of("run-4"), listed(labelled(Map.of("app.note", "say \"hi\" \\ bye"))));
  }
}
