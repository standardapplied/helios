/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.openai.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import com.standardapplied.helios.openai.OpenAIModelId;
import com.standardapplied.helios.openai.OpenAIProvider;
import org.junit.jupiter.api.Test;

class OpenAIJsonTest {

  private static Model createModel(OpenAIModelId modelId, ModelConfig config) {
    return new OpenAIProvider().create(modelId.id(), config);
  }

  private static <T> T parse(String content, OutputSchema<T> schema) {
    return parse(content, schema, RawOutputCapturePolicy.ENABLED);
  }

  private static <T> T parse(
      String content, OutputSchema<T> schema, RawOutputCapturePolicy policy) {
    return StructuredContentParser.parse(content, schema, OpenAIJson.STRUCTURED, policy);
  }

  public record TestPerson(String name, int age) {}

  @Test
  void parseStructuredContentValidJson() {
    var result = parse("{\"name\":\"Alice\",\"age\":30}", OutputSchema.of(TestPerson.class));
    assertNotNull(result);
    assertEquals("Alice", result.name());
    assertEquals(30, result.age());
  }

  @Test
  void parseStructuredContentNullReturnsNull() {
    assertNull(parse(null, OutputSchema.of(TestPerson.class)));
  }

  @Test
  void parseStructuredContentBlankReturnsNull() {
    assertNull(parse("   ", OutputSchema.of(TestPerson.class)));
  }

  @Test
  void parseStructuredContentInvalidJsonThrows() {
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () -> parse("not json at all", OutputSchema.of(TestPerson.class)));
    assertTrue(ex.errors().stream().anyMatch(e -> e.startsWith("JSON syntax error:")));
  }

  @Test
  void parseStructuredContentThatIsNotAnObjectReportsTheUntypedMapItCouldNotRead() {
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () -> parse("[1, 2]", OutputSchema.of(TestPerson.class)));

    assertEquals(
        "JSON syntax error: Cannot deserialize value of type"
            + " `java.util.LinkedHashMap<java.lang.Object,java.lang.Object>` from Array value"
            + " (token `JsonToken.START_ARRAY`)",
        ex.errors().getFirst().lines().findFirst().orElseThrow());
  }

  @Test
  void disabledRawOutputCaptureIsAppliedByOpenAiParser() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withRawOutputCapture(RawOutputCapturePolicy.DISABLED)
            .build();
    var model = createModel(OpenAIModelId.GPT_4O, config);

    var error =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                parse(
                    "{\"name\":\"private-model-output-canary\"}",
                    OutputSchema.of(TestPerson.class),
                    config.rawOutputCapturePolicy()));

    assertNull(error.rawContent());
    assertEquals(RawOutputCapturePolicy.DISABLED, model.rawOutputCapturePolicy());
  }

  @Test
  void parseStructuredContentMarkdownWrapped() {
    var result =
        parse("```json\n{\"name\":\"Bob\",\"age\":25}\n```", OutputSchema.of(TestPerson.class));
    assertNotNull(result);
    assertEquals("Bob", result.name());
  }

  @Test
  void parseStructuredContentMarkdownWrappedInvalidThrows() {
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () -> parse("```json\nnot valid\n```", OutputSchema.of(TestPerson.class)));
    assertTrue(ex.errors().stream().anyMatch(e -> e.startsWith("JSON syntax error:")));
  }

  @Test
  void parseStructuredContentProvenancedReconstructsTypedOutput() {
    var schema = OutputSchema.provenancedOf(TestPerson.class);
    var json =
        "{\"output\":{\"name\":\"Alice\",\"age\":30},\"provenance\":["
            + "{\"field\":\"name\",\"sources\":[{\"url\":\"https://x.com\",\"excerpts\":[\"a\"]}],"
            + "\"reasoning\":\"named in source\",\"confidence\":\"HIGH\"},"
            + "{\"field\":\"age\",\"sources\":[],\"reasoning\":\"guess\",\"confidence\":\"LOW\"}]}";

    var result = parse(json, schema);

    assertNotNull(result);
    assertEquals("Alice", result.output().name());
    assertEquals(30, result.output().age());
    assertEquals(2, result.provenance().size());
    assertEquals("HIGH", result.forField("name").confidence().wireValue());
  }

  @Test
  void parseStructuredContentProvenancedHandlesMarkdownWrapper() {
    var schema = OutputSchema.provenancedOf(TestPerson.class);
    var json =
        "```json\n{\"output\":{\"name\":\"Bob\",\"age\":25},\"provenance\":["
            + "{\"field\":\"name\",\"sources\":[],\"reasoning\":\"r\",\"confidence\":\"LOW\"},"
            + "{\"field\":\"age\",\"sources\":[],\"reasoning\":\"r\",\"confidence\":\"LOW\"}]}\n```";

    var result = parse(json, schema);
    assertEquals("Bob", result.output().name());
  }

  @Test
  void parseStructuredContentSchemaMismatchSurfacesFieldLevelDiff() {
    var schema = OutputSchema.of(TestPerson.class);
    var ex =
        assertThrows(
            StructuredOutputParseException.class, () -> parse("{\"name\":\"Alice\"}", schema));
    assertTrue(
        ex.errors().stream().anyMatch(e -> e.contains("age") && e.contains("required")),
        "diff must name the missing 'age' field as required: " + ex.errors());
    assertEquals("{\"name\":\"Alice\"}", ex.rawContent());
  }

  @Test
  void parseStructuredContentSchemaMismatchInProvenancedEnvelopeReportsNestedPath() {
    var schema = OutputSchema.provenancedOf(TestPerson.class);
    var json =
        "{\"output\":{\"name\":\"Alice\",\"age\":30},\"provenance\":["
            + "{\"field\":\"name\",\"sources\":[{\"excerpts\":[\"a\"]}],"
            + "\"reasoning\":\"named in source\",\"confidence\":\"HIGH\"}]}";
    var ex = assertThrows(StructuredOutputParseException.class, () -> parse(json, schema));
    assertTrue(
        ex.errors().stream().anyMatch(e -> e.contains("provenance[0].sources[0].url")),
        "diff must include the deep path 'provenance[0].sources[0].url': " + ex.errors());
  }
}
