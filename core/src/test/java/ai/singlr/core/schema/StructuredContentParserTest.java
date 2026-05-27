/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredContentParserTest {

  /** A minimal adapter that parses JSON via a hand-rolled map shape — no Jackson in core tests. */
  private static final class MockAdapter implements StructuredContentParser.JsonAdapter {
    private final Map<String, Map<String, Object>> jsonFixtures;

    MockAdapter(Map<String, Map<String, Object>> fixtures) {
      this.jsonFixtures = fixtures;
    }

    @Override
    public Map<String, Object> toMap(String json) throws Exception {
      var fixture = jsonFixtures.get(json);
      if (fixture == null) {
        throw new RuntimeException("simulated JSON syntax error: " + json);
      }
      return fixture;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T fromMap(Map<String, Object> map, Class<T> type) throws Exception {
      if (type == Bag.class) {
        return (T) new Bag((String) map.get("name"), (Integer) map.get("count"));
      }
      throw new IllegalArgumentException("unsupported type: " + type);
    }
  }

  public record Bag(String name, int count) {}

  private static StructuredContentParser.JsonAdapter adapterFor(
      String json, Map<String, Object> map) {
    return new MockAdapter(Map.of(json, map));
  }

  private static StructuredContentParser.JsonAdapter throwingAdapter() {
    return new MockAdapter(Map.of());
  }

  // --- Null / blank passthrough ----------------------------------------------------------------

  @Test
  void nullContentReturnsNull() {
    var result = StructuredContentParser.parse(null, OutputSchema.of(Bag.class), throwingAdapter());
    assertNull(result);
  }

  @Test
  void blankContentReturnsNull() {
    var result =
        StructuredContentParser.parse("   ", OutputSchema.of(Bag.class), throwingAdapter());
    assertNull(result);
  }

  // --- Success path ----------------------------------------------------------------------------

  @Test
  void successfulParseReturnsTyped() {
    var content = "{\"name\":\"alpha\",\"count\":7}";
    var adapter = adapterFor(content, new LinkedHashMap<>(Map.of("name", "alpha", "count", 7)));

    var result = StructuredContentParser.parse(content, OutputSchema.of(Bag.class), adapter);

    assertEquals(new Bag("alpha", 7), result);
  }

  // --- Schema mismatch surfaces as StructuredOutputParseException ------------------------------

  @Test
  void schemaMismatchThrowsStructuredOutputParseException() {
    var content = "{\"name\":\"alpha\"}";
    var adapter = adapterFor(content, new LinkedHashMap<>(Map.of("name", "alpha")));
    // 'count' is missing — SchemaValidator should reject.

    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () -> StructuredContentParser.parse(content, OutputSchema.of(Bag.class), adapter));
    assertTrue(ex.errors().stream().anyMatch(e -> e.contains("count")));
  }

  // --- JSON syntax fallback through markdown strip ---------------------------------------------

  @Test
  void markdownWrappedJsonGetsStripped() {
    var rawInner = "{\"name\":\"beta\",\"count\":42}";
    var wrapped = "```json\n" + rawInner + "\n```";
    var fixtures =
        Map.<String, Map<String, Object>>of(
            // After strip we get the trimmed inner — that's what the adapter must accept.
            rawInner, new LinkedHashMap<>(Map.of("name", "beta", "count", 42)));
    var adapter = new MockAdapter(fixtures);

    var result = StructuredContentParser.parse(wrapped, OutputSchema.of(Bag.class), adapter);

    assertEquals(new Bag("beta", 42), result);
  }

  @Test
  void unwrappedJsonSyntaxErrorThrowsStructuredOutputParseException() {
    var content = "not-json";
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                StructuredContentParser.parse(
                    content, OutputSchema.of(Bag.class), throwingAdapter()));
    assertTrue(
        ex.errors().stream().anyMatch(e -> e.startsWith("JSON syntax error:")),
        "Expected JSON syntax error in errors(), got: " + ex.errors());
    assertEquals(content, ex.rawContent());
  }

  @Test
  void wrappedJsonStillSyntacticallyInvalidThrowsStructuredOutputParseException() {
    var content = "```json\nnot-json\n```";
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                StructuredContentParser.parse(
                    content, OutputSchema.of(Bag.class), throwingAdapter()));
    assertTrue(
        ex.errors().stream().anyMatch(e -> e.startsWith("JSON syntax error:")),
        "Expected JSON syntax error in errors(), got: " + ex.errors());
  }

  @Test
  void jsonSyntaxErrorCorrectionMessageMentionsError() {
    var malformed = "{\"name\":\"alpha\",\"count\":}";
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                StructuredContentParser.parse(
                    malformed, OutputSchema.of(Bag.class), throwingAdapter()));
    var correction = ex.correctionMessage();
    assertTrue(correction.contains("JSON syntax error"), "correction should mention syntax error");
    assertEquals(malformed, ex.rawContent(), "rawContent should preserve the original content");
  }

  @Test
  void schemaMismatchInStrippedRetryStillThrowsParseException() {
    var inner = "{\"name\":\"y\"}";
    var wrapped = "```json\n" + inner + "\n```";
    // First attempt: adapter throws (raw content not in fixtures). Strip fences, try again with
    // the inner — adapter returns valid map but schema validation rejects missing count.
    var fixtures =
        Map.<String, Map<String, Object>>of(inner, new LinkedHashMap<>(Map.of("name", "y")));
    var adapter = new MockAdapter(fixtures);

    assertThrows(
        StructuredOutputParseException.class,
        () -> StructuredContentParser.parse(wrapped, OutputSchema.of(Bag.class), adapter));
  }

  // --- stripMarkdownWrapper standalone -------------------------------------------------------

  @Test
  void stripMarkdownWrapperJsonFence() {
    assertEquals(
        "{\"name\":\"test\"}",
        StructuredContentParser.stripMarkdownWrapper("```json\n{\"name\":\"test\"}\n```"));
  }

  @Test
  void stripMarkdownWrapperPlainFence() {
    assertEquals(
        "{\"name\":\"test\"}",
        StructuredContentParser.stripMarkdownWrapper("```\n{\"name\":\"test\"}\n```"));
  }

  @Test
  void stripMarkdownWrapperWithoutFenceIsIdentity() {
    assertEquals(
        "{\"name\":\"test\"}", StructuredContentParser.stripMarkdownWrapper("{\"name\":\"test\"}"));
  }

  @Test
  void stripMarkdownWrapperTrimsWhitespace() {
    assertEquals("body", StructuredContentParser.stripMarkdownWrapper("```\n  body  \n```"));
  }

  @Test
  void stripMarkdownWrapperOnlyOpeningFence() {
    // The pre-1.x parser also stripped a trailing fence-less marker only when both ends matched.
    // The shared parser does it conservatively: leading fence removed, trailing left alone.
    assertEquals("{\"k\":1}", StructuredContentParser.stripMarkdownWrapper("```json\n{\"k\":1}"));
  }

  // --- Coverage of the provenanced fromMap path ------------------------------------------------

  @Test
  void provenancedSchemaRoutesThroughInnerOutputType() {
    var output = new LinkedHashMap<String, Object>();
    output.put("name", "Alice");
    output.put("count", 5);
    var first = new LinkedHashMap<String, Object>();
    first.put("field", "name");
    first.put("sources", List.of(Map.of("url", "https://x.com", "excerpts", List.of("alpha"))));
    first.put("reasoning", "stated");
    first.put("confidence", "HIGH");
    var second = new LinkedHashMap<String, Object>();
    second.put("field", "count");
    second.put("sources", List.of());
    second.put("reasoning", "inferred");
    second.put("confidence", "LOW");
    var prov = new java.util.ArrayList<Object>();
    prov.add(first);
    prov.add(second);
    var raw = new LinkedHashMap<String, Object>();
    raw.put("output", output);
    raw.put("provenance", prov);

    var content = "{\"output\":...}"; // matched only by exact key in fixtures
    var fixtures = new HashMap<String, Map<String, Object>>();
    fixtures.put(content, raw);

    var adapter = new MockAdapter(fixtures);
    var schema = OutputSchema.provenancedOf(Bag.class);

    @SuppressWarnings({"rawtypes", "unchecked"})
    var result =
        (ai.singlr.core.common.Provenanced<Bag>)
            StructuredContentParser.parse(content, schema, adapter);

    assertEquals(new Bag("Alice", 5), result.output());
    assertEquals(2, result.provenance().size());
  }

  @Test
  void provenancedInnerFromMapExceptionWraps() {
    var output = new LinkedHashMap<String, Object>();
    output.put("name", "Alice");
    output.put("count", 5);
    var prov = new LinkedHashMap<String, Object>();
    prov.put("field", "name");
    prov.put("sources", List.of(Map.of("url", "https://x.com", "excerpts", List.of("alpha"))));
    prov.put("reasoning", "stated");
    prov.put("confidence", "HIGH");
    var raw = new LinkedHashMap<String, Object>();
    raw.put("output", output);
    raw.put("provenance", List.of(prov));

    var content = "{\"output\":...}";
    var adapter =
        new StructuredContentParser.JsonAdapter() {
          @Override
          public Map<String, Object> toMap(String json) {
            return raw;
          }

          @Override
          public <T> T fromMap(Map<String, Object> map, Class<T> type) throws Exception {
            // The provenanced path lambda calls fromMap on the inner type (Bag.class). Failing
            // there must surface as a wrapped RuntimeException so the outer parse() throws the
            // provider's exception factory rather than dying silently.
            throw new IllegalStateException(
                "simulated coercion failure for " + type.getSimpleName());
          }
        };
    var schema = OutputSchema.provenancedOf(Bag.class);

    var ex =
        assertThrows(
            RuntimeException.class, () -> StructuredContentParser.parse(content, schema, adapter));
    var cause = unwrapCause(ex);
    assertNotNull(cause);
    assertTrue(
        cause.getMessage() != null && cause.getMessage().contains("simulated coercion failure"),
        "Expected wrapped cause to mention the simulated failure, got: " + cause);
  }

  private static Throwable unwrapCause(Throwable t) {
    var current = t;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  @Test
  void extractFirstJsonObjectReturnsNullWhenNoBrace() {
    assertNull(StructuredContentParser.extractFirstJsonObject("just prose, no object"));
    assertNull(StructuredContentParser.extractFirstJsonObject(""));
    assertNull(StructuredContentParser.extractFirstJsonObject(null));
  }

  @Test
  void extractFirstJsonObjectReturnsWholeObjectForPureJson() {
    var json = "{\"a\":1,\"b\":\"x\"}";
    assertEquals(json, StructuredContentParser.extractFirstJsonObject(json));
  }

  @Test
  void extractFirstJsonObjectStripsLeadingProse() {
    var content = "The answer is:\n\n{\"a\":1,\"b\":\"x\"}";
    assertEquals("{\"a\":1,\"b\":\"x\"}", StructuredContentParser.extractFirstJsonObject(content));
  }

  @Test
  void extractFirstJsonObjectHandlesNestedObjects() {
    var content = "Prose. {\"outer\":{\"inner\":{\"k\":42}},\"trail\":true}";
    assertEquals(
        "{\"outer\":{\"inner\":{\"k\":42}},\"trail\":true}",
        StructuredContentParser.extractFirstJsonObject(content));
  }

  @Test
  void extractFirstJsonObjectIgnoresBracesInsideStringLiterals() {
    var content = "Reply: {\"text\":\"this has } and { inside\",\"n\":1}";
    assertEquals(
        "{\"text\":\"this has } and { inside\",\"n\":1}",
        StructuredContentParser.extractFirstJsonObject(content));
  }

  @Test
  void extractFirstJsonObjectHandlesEscapedQuotesInString() {
    var content = "Result: {\"path\":\"\\\"escaped\\\"\",\"ok\":true}";
    assertEquals(
        "{\"path\":\"\\\"escaped\\\"\",\"ok\":true}",
        StructuredContentParser.extractFirstJsonObject(content));
  }

  @Test
  void extractFirstJsonObjectStopsAtFirstCompleteObject() {
    var content = "Two: {\"a\":1} {\"b\":2}";
    assertEquals("{\"a\":1}", StructuredContentParser.extractFirstJsonObject(content));
  }

  @Test
  void extractFirstJsonObjectReturnsNullForUnbalanced() {
    assertNull(StructuredContentParser.extractFirstJsonObject("{\"unclosed\":1"));
    assertNull(StructuredContentParser.extractFirstJsonObject("{\"nested\":{\"inner\":1"));
  }

  @Test
  void parseRecoversFromProseWrappedJson() {
    var bagJson = "{\"name\":\"x\",\"count\":1}";
    var content = "The map is built correctly. Here is the final answer:\n\n" + bagJson;
    var fixture = new LinkedHashMap<String, Object>();
    fixture.put("name", "x");
    fixture.put("count", 1);
    var adapter = new MockAdapter(Map.of(bagJson, fixture));
    var schema = OutputSchema.of(Bag.class);
    var bag = StructuredContentParser.parse(content, schema, adapter);
    assertNotNull(bag);
    assertEquals("x", bag.name());
    assertEquals(1, bag.count());
  }
}
