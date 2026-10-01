/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import ai.singlr.core.common.Confidence;
import ai.singlr.core.common.FieldProvenance;
import ai.singlr.core.common.ProvenanceValidator;
import ai.singlr.core.common.Provenanced;
import ai.singlr.core.common.Source;
import ai.singlr.core.common.Strings;
import ai.singlr.core.common.SubmitValidator;
import ai.singlr.core.common.ValidationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Wraps a Java class with its generated JSON Schema. Used to specify structured output requirements
 * for model responses.
 *
 * <p>Two flavors:
 *
 * <ul>
 *   <li>{@link #of(Class)} — schema for a plain output type {@code T}.
 *   <li>{@link #provenancedOf(Class)} — schema for {@link Provenanced} {@code <T>} which pairs the
 *       output payload with a sidecar {@code provenance} array of per-field {@link FieldProvenance}
 *       entries (citations, reasoning, ordinal {@link Confidence}).
 * </ul>
 *
 * <p>The {@code provenanceValidator} field is non-null only for provenanced schemas. The submit
 * path applies it after JSON Schema validation, after structural checks (every output field has
 * exactly one entry).
 *
 * <p>{@code innerOutputType} is also non-null only for provenanced schemas. {@link
 * #reconstructProvenanced(Map, Function)} reads it to reconstruct {@code Provenanced<T>} with the
 * user's actual output class, since Java's type erasure prevents Jackson from doing this from
 * {@code type = Provenanced.class} alone.
 *
 * <p>{@code submitValidator} is an optional whole-output semantic check that runs after structural
 * validation wherever a final structured output is accepted: {@link StructuredContentParser} (so
 * every provider, the {@code AgentSession} loop, and typed {@code runBlocking}) and the CodeAct
 * {@code Submit} tool. Use {@link #withSubmitValidator(SubmitValidator)} (or the {@link
 * #withSubmitValidator(Predicate, String)} convenience overload) to attach one. On failure the
 * model sees the correction message as its next user turn and retries within the session's turn
 * budget.
 *
 * @param <T> the type of the output
 * @param type the class
 * @param schema the generated JSON Schema
 * @param provenanceValidator validator applied to each {@link FieldProvenance} when this is a
 *     provenanced schema; {@code null} for plain schemas
 * @param innerOutputType the user's underlying output class for provenanced schemas; {@code null}
 *     for plain schemas
 * @param submitValidator semantic validator applied to the parsed output at submit time; {@code
 *     null} when no semantic check is configured
 */
public record OutputSchema<T>(
    Class<T> type,
    JsonSchema schema,
    ProvenanceValidator provenanceValidator,
    Class<?> innerOutputType,
    SubmitValidator<T> submitValidator) {

  /**
   * Creates an OutputSchema by generating a JSON Schema from the given class.
   *
   * @param clazz the record or class to use for structured output
   * @param <T> the type
   * @return an OutputSchema with the generated schema
   * @throws IllegalArgumentException if the class is a primitive, array, enum, or leaf type
   */
  public static <T> OutputSchema<T> of(Class<T> clazz) {
    var schema = SchemaGenerator.generate(clazz);
    return new OutputSchema<>(clazz, schema, null, null, null);
  }

  /**
   * Creates an OutputSchema from a hand-built {@link JsonSchema}. Use this when {@link
   * SchemaGenerator} can't produce the shape you need (custom envelopes, non-record output types,
   * dynamic schemas).
   *
   * @param clazz the runtime type associated with this schema
   * @param schema the hand-built JSON Schema describing the expected output
   * @param <T> the type
   * @return an OutputSchema bound to the given class and schema
   */
  public static <T> OutputSchema<T> of(Class<T> clazz, JsonSchema schema) {
    return new OutputSchema<>(clazz, schema, null, null, null);
  }

  /**
   * Returns a copy of this schema with the given {@link SubmitValidator} attached. When the model
   * produces a final output, the accept path runs JSON Schema validation, then provenance
   * reconstruction (when applicable), then this validator on the parsed typed output. A failure is
   * surfaced the same way structural failures are: {@link StructuredContentParser} throws {@link
   * SubmitValidationException} and the session loop injects the correction and retries, bounded by
   * {@code SessionLimits.maxTurns()}; the CodeAct {@code Submit} tool returns it inline.
   *
   * <p>Operator-thrown exceptions inside the validator are caught and converted to a failure with
   * message {@code "submit validator threw: <message>"}, so a buggy predicate doesn't tombstone the
   * agent run.
   *
   * @param validator the semantic validator; never {@code null}
   * @return a new {@code OutputSchema} with the validator set
   */
  public OutputSchema<T> withSubmitValidator(SubmitValidator<T> validator) {
    if (validator == null) {
      throw new IllegalArgumentException("validator must not be null");
    }
    return new OutputSchema<>(type, schema, provenanceValidator, innerOutputType, validator);
  }

  /**
   * Convenience overload for the simple {@code Predicate + correction} case. Equivalent to
   * supplying a {@link SubmitValidator} that returns {@link ValidationResult#success()} when the
   * predicate matches and {@link ValidationResult#failure(String)} carrying {@code correction} when
   * it does not.
   *
   * @param predicate the test the parsed output must satisfy; never {@code null}
   * @param correction the correction message surfaced to the model on failure; never blank
   * @return a new {@code OutputSchema} with the wrapped validator set
   */
  public OutputSchema<T> withSubmitValidator(Predicate<T> predicate, String correction) {
    if (predicate == null) {
      throw new IllegalArgumentException("predicate must not be null");
    }
    if (Strings.isBlank(correction)) {
      throw new IllegalArgumentException("correction must not be blank");
    }
    SubmitValidator<T> wrapped =
        output ->
            predicate.test(output)
                ? ValidationResult.success()
                : ValidationResult.failure(correction);
    return withSubmitValidator(wrapped);
  }

  /**
   * Creates a provenanced OutputSchema for {@code Provenanced<T>}. The model is expected to emit
   * {@code { output: <T>, provenance: [{field, sources, reasoning, confidence}, ...] }}. Uses
   * {@link ProvenanceValidator#DEFAULT} which rejects {@link Confidence#MEDIUM}/{@link
   * Confidence#HIGH} entries with no sources.
   *
   * @param clazz the underlying output class (NOT {@code Provenanced.class})
   * @param <T> the underlying output type
   * @return an OutputSchema describing {@code Provenanced<T>}
   */
  public static <T> OutputSchema<Provenanced<T>> provenancedOf(Class<T> clazz) {
    return provenancedOf(clazz, ProvenanceValidator.DEFAULT);
  }

  /**
   * Creates a provenanced OutputSchema with a custom {@link ProvenanceValidator}.
   *
   * @param clazz the underlying output class
   * @param validator the per-entry validator; never {@code null}
   * @param <T> the underlying output type
   * @return an OutputSchema describing {@code Provenanced<T>} and bound to the validator
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  public static <T> OutputSchema<Provenanced<T>> provenancedOf(
      Class<T> clazz, ProvenanceValidator validator) {
    if (clazz == null) {
      throw new IllegalArgumentException("clazz must not be null");
    }
    if (validator == null) {
      throw new IllegalArgumentException("validator must not be null");
    }
    var outputSchema = SchemaGenerator.generate(clazz);
    var fieldProvenanceSchema = fieldProvenanceSchema();
    var provenancedSchema =
        JsonSchema.object()
            .withProperty("output", outputSchema, true)
            .withProperty("provenance", JsonSchema.array(fieldProvenanceSchema), true)
            .withDescription(
                "Structured output with per-field provenance. `output` is the answer; `provenance`"
                    + " is a sidecar array with one entry per top-level field of `output`. Each"
                    + " entry names the `field`, gives `sources` (each with `url` and optional"
                    + " `excerpts`), free-text `reasoning`, and ordinal `confidence` of LOW,"
                    + " MEDIUM, or HIGH. Confidence MEDIUM or HIGH requires at least one source.")
            .build();
    return new OutputSchema<>((Class) Provenanced.class, provenancedSchema, validator, clazz, null);
  }

  /**
   * Reconstruct a {@link Provenanced} value from a raw deserialized JSON map.
   *
   * <p>Core has no Jackson dependency, so the schema cannot do JSON parsing itself. Providers
   * (which already carry their own {@code ObjectMapper}) deserialize the model's response to {@code
   * Map<String, Object>} and pass it here along with a {@code outputConverter} that knows how to
   * coerce the {@code output} sub-map into the user's typed {@code T}.
   *
   * <p>{@code FieldProvenance} and {@code Source} are vanilla records with no library deps; this
   * helper builds them with plain field access, no Jackson required.
   *
   * @param raw the deserialized response map; must contain {@code output} and {@code provenance}
   * @param outputConverter function that converts the {@code raw.get("output")} value to {@code T}
   *     (typically delegates to the provider's {@code ObjectMapper.convertValue})
   * @param <T> the underlying output type
   * @return the reconstructed {@code Provenanced<T>}
   */
  public static <T> Provenanced<T> reconstructProvenanced(
      Map<String, Object> raw, Function<Object, T> outputConverter) {
    if (raw == null) {
      throw new IllegalArgumentException("raw response map must not be null");
    }
    if (outputConverter == null) {
      throw new IllegalArgumentException("outputConverter must not be null");
    }
    var rawOutput = raw.get("output");
    if (rawOutput == null) {
      throw new IllegalArgumentException("provenanced response missing 'output' field");
    }
    var output = outputConverter.apply(rawOutput);
    var rawProvenance = raw.get("provenance");
    if (!(rawProvenance instanceof List<?> provList)) {
      throw new IllegalArgumentException(
          "provenanced response 'provenance' must be an array, got: " + rawProvenance);
    }
    var entries = new ArrayList<FieldProvenance>(provList.size());
    for (var item : provList) {
      if (!(item instanceof Map<?, ?> entry)) {
        throw new IllegalArgumentException("provenance entry must be an object, got: " + item);
      }
      entries.add(parseFieldProvenance(entry));
    }
    return new Provenanced<>(output, entries);
  }

  private static FieldProvenance parseFieldProvenance(Map<?, ?> raw) {
    var field = (String) raw.get("field");
    var reasoning = (String) raw.get("reasoning");
    var confidenceWire = String.valueOf(raw.get("confidence"));
    var confidence = Confidence.fromWire(confidenceWire);
    var sources = new ArrayList<Source>();
    if (raw.get("sources") instanceof List<?> list) {
      for (var item : list) {
        if (item instanceof Map<?, ?> srcMap) {
          sources.add(parseSource(srcMap));
        }
      }
    }
    return new FieldProvenance(field, sources, reasoning, confidence);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Source parseSource(Map<?, ?> raw) {
    var title = (String) raw.get("title");
    var url = (String) raw.get("url");
    List<String> excerpts =
        raw.get("excerpts") instanceof List<?> list ? (List<String>) (List) list : List.of();
    return new Source(title, url, excerpts);
  }

  private static JsonSchema sourceSchema() {
    return new JsonSchema(
        "object",
        Map.of(
            "title", JsonSchema.string("Human-readable title of the source; may be omitted"),
            "url",
                JsonSchema.string(
                    "Canonical URL of the source. May be a non-HTTP URI scheme such as"
                        + " cdisc-ct://CL.AGEU/YEARS for non-web provenance."),
            "excerpts",
                JsonSchema.array(JsonSchema.string("Verbatim excerpt supporting the field value"))),
        null,
        List.of("url", "excerpts"),
        null,
        "Source citation: where this field's value came from.",
        null,
        null);
  }

  private static JsonSchema fieldProvenanceSchema() {
    return new JsonSchema(
        "object",
        Map.of(
            "field",
                JsonSchema.string(
                    "Name of the output field this entry describes; must match a field of `output`"),
            "sources", JsonSchema.array(sourceSchema()),
            "reasoning",
                JsonSchema.string("One or two sentences justifying the value of this field"),
            "confidence",
                JsonSchema.enumOf(List.of("LOW", "MEDIUM", "HIGH"))
                    .withDescription(
                        "Ordinal confidence. MEDIUM and HIGH require at least one source.")),
        null,
        List.of("field", "sources", "reasoning", "confidence"),
        null,
        "Per-field provenance entry. Include exactly one per top-level field of `output`.",
        null,
        null);
  }
}
