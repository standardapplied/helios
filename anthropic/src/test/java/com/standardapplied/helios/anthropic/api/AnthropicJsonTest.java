/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.anthropic.AnthropicModelId;
import com.standardapplied.helios.anthropic.AnthropicProvider;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.schema.StructuredContentParser;
import com.standardapplied.helios.core.schema.StructuredOutputParseException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

class AnthropicJsonTest {

  private static Model model(AnthropicModelId model, ModelConfig config) {
    return new AnthropicProvider().create(model.id(), config);
  }

  private static <T> T parse(ModelConfig config, String content, OutputSchema<T> schema) {
    return StructuredContentParser.parse(
        content, schema, AnthropicJson.STRUCTURED, config.rawOutputCapturePolicy());
  }

  public record TestPerson(String name, int age) {}

  @Test
  void parseStructuredContentPlainSchema() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var result =
        parse(config, "{\"name\":\"Alice\",\"age\":30}", OutputSchema.of(TestPerson.class));
    assertEquals("Alice", result.name());
    assertEquals(30, result.age());
  }

  @Test
  void parseStructuredContentProvenancedReconstructsTypedOutput() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var schema = OutputSchema.provenancedOf(TestPerson.class);
    var json =
        "{\"output\":{\"name\":\"Alice\",\"age\":30},\"provenance\":["
            + "{\"field\":\"name\",\"sources\":[{\"url\":\"https://x.com\",\"excerpts\":[\"a\"]}],"
            + "\"reasoning\":\"named in source\",\"confidence\":\"HIGH\"},"
            + "{\"field\":\"age\",\"sources\":[],\"reasoning\":\"guess\",\"confidence\":\"LOW\"}]}";

    var result = parse(config, json, schema);

    assertNotNull(result);
    assertEquals("Alice", result.output().name());
    assertEquals(30, result.output().age());
    assertEquals(2, result.provenance().size());
    assertEquals("HIGH", result.forField("name").confidence().wireValue());
  }

  @Test
  void parseStructuredContentNullReturnsNull() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    assertNull(parse(config, null, OutputSchema.of(TestPerson.class)));
  }

  @Test
  void parseStructuredContentSchemaMismatchSurfacesFieldLevelDiff() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var schema = OutputSchema.of(TestPerson.class);
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () -> parse(config, "{\"name\":\"Alice\"}", schema));
    assertTrue(
        ex.errors().stream().anyMatch(e -> e.contains("age") && e.contains("required")),
        "diff must name the missing 'age' field as required: " + ex.errors());
    assertEquals("{\"name\":\"Alice\"}", ex.rawContent());
  }

  @Test
  void disabledRawOutputCaptureIsAppliedByAnthropicParser() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey("test-key")
            .withRawOutputCapture(RawOutputCapturePolicy.DISABLED)
            .build();
    var model = model(AnthropicModelId.CLAUDE_OPUS_4_6, config);

    var error =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                parse(
                    config,
                    "{\"name\":\"private-model-output-canary\"}",
                    OutputSchema.of(TestPerson.class)));

    assertNull(error.rawContent());
    assertEquals(RawOutputCapturePolicy.DISABLED, model.rawOutputCapturePolicy());
  }

  @Test
  void parseStructuredContentSchemaMismatchInProvenancedEnvelopeReportsNestedPath() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var schema = OutputSchema.provenancedOf(TestPerson.class);
    // Source object missing the required 'url' field — surfaces as a deep path under provenance.
    var json =
        "{\"output\":{\"name\":\"Alice\",\"age\":30},\"provenance\":["
            + "{\"field\":\"name\",\"sources\":[{\"excerpts\":[\"a\"]}],"
            + "\"reasoning\":\"named in source\",\"confidence\":\"HIGH\"}]}";
    var ex = assertThrows(StructuredOutputParseException.class, () -> parse(config, json, schema));
    assertTrue(
        ex.errors().stream().anyMatch(e -> e.contains("provenance[0].sources[0].url")),
        "diff must include the deep path 'provenance[0].sources[0].url': " + ex.errors());
  }

  @Test
  void parseStructuredContentSyntaxErrorThrowsStructuredOutputParseException() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () ->
                parse(
                    config, "{\"name\":\"Alice\",unterminated", OutputSchema.of(TestPerson.class)));
    assertTrue(ex.errors().stream().anyMatch(e -> e.startsWith("JSON syntax error:")));
  }

  @Test
  void aStructuredAnswerThatIsNotAnObjectReportsTheMapReadFailure() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var expected =
        assertThrows(
            JacksonException.class, () -> AnthropicJson.LENIENT.readValue("[1, 2]", Map.class));

    var ex =
        assertThrows(
            StructuredOutputParseException.class,
            () -> parse(config, "[1, 2]", OutputSchema.of(TestPerson.class)));

    assertEquals(List.of("JSON syntax error: " + expected.getMessage()), ex.errors());
  }
}
