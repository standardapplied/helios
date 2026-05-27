/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.schema;

import ai.singlr.core.common.Strings;
import java.util.List;
import java.util.Map;

/**
 * Shared structured-output parser. Three providers (Anthropic, Gemini, OpenAI) used to duplicate
 * this code byte-for-byte; the shared implementation lives here so future fixes (Unicode BOM
 * handling, additional fence shapes, schema-error message tweaks) get applied once.
 *
 * <p>Algorithm:
 *
 * <ol>
 *   <li>Trim the content. If null/blank, return null (matches prior provider behaviour).
 *   <li>Parse the JSON to {@code Map<String,Object>} via the caller-supplied {@link JsonAdapter}.
 *   <li>Run {@link SchemaValidator} against the deserialized map. On mismatch throw a {@link
 *       StructuredOutputParseException} carrying the field-level diff.
 *   <li>Type-coerce the map into the schema's output class. For provenanced schemas use {@link
 *       OutputSchema#reconstructProvenanced(Map, java.util.function.Function)}.
 *   <li>On JSON-syntax failure retry once after stripping markdown fences. If that also fails,
 *       throw {@link StructuredOutputParseException} so the session loop's self-correction
 *       mechanism can inject a correction message and retry, same as for schema-validation
 *       failures.
 * </ol>
 *
 * <p>Core has no Jackson dependency; providers wire their {@code ObjectMapper}-equivalent via
 * {@link JsonAdapter} so this class stays JSON-library-agnostic.
 */
public final class StructuredContentParser {

  private StructuredContentParser() {}

  /**
   * JSON operations the parser needs. Providers wrap their {@code ObjectMapper} (Jackson 3.x in all
   * three current providers) so core stays free of the dependency.
   */
  public interface JsonAdapter {

    /**
     * Parse a JSON object into a string-keyed map. Implementations should propagate
     * library-specific exceptions; {@link StructuredContentParser#parse} catches and falls back to
     * the markdown-strip retry.
     *
     * @throws Exception when the JSON is syntactically invalid
     */
    Map<String, Object> toMap(String json) throws Exception;

    /**
     * Coerce a deserialized map into a typed record/POJO. For Jackson this is {@code
     * objectMapper.convertValue(map, type)}.
     *
     * @throws Exception when coercion fails (e.g., type mismatch on a record component)
     */
    <T> T fromMap(Map<String, Object> map, Class<T> type) throws Exception;
  }

  /**
   * Parse {@code content} against {@code schema} using the supplied {@code adapter}. All parse
   * failures — both schema-validation mismatches and JSON-syntax errors — surface as {@link
   * StructuredOutputParseException} so the session loop's self-correction mechanism can handle them
   * uniformly.
   *
   * @param <T> the typed output
   * @param content the raw model response — may be {@code null}/blank to indicate "no structured
   *     output produced"
   * @param schema the output schema describing the expected shape
   * @param adapter provider-supplied JSON adapter
   * @return the typed output, or {@code null} when {@code content} was null/blank
   */
  public static <T> T parse(String content, OutputSchema<T> schema, JsonAdapter adapter) {
    if (Strings.isBlank(content)) {
      return null;
    }
    var trimmed = content.trim();
    try {
      return parseToType(trimmed, schema, adapter);
    } catch (StructuredOutputParseException schemaMismatch) {
      throw schemaMismatch;
    } catch (Exception firstAttempt) {
      var stripped = stripMarkdownWrapper(trimmed);
      if (!stripped.equals(trimmed)) {
        try {
          return parseToType(stripped, schema, adapter);
        } catch (StructuredOutputParseException schemaMismatch) {
          throw schemaMismatch;
        } catch (Exception ignored) {
          // fall through to extraction retry
        }
      }
      var extracted = extractFirstJsonObject(trimmed);
      if (extracted != null && !extracted.equals(trimmed)) {
        try {
          return parseToType(extracted, schema, adapter);
        } catch (StructuredOutputParseException schemaMismatch) {
          throw schemaMismatch;
        } catch (Exception ignored) {
          // fall through to terminal failure
        }
      }
      throw new StructuredOutputParseException(
          List.of("JSON syntax error: " + firstAttempt.getMessage()), content);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static <T> T parseToType(String json, OutputSchema<T> schema, JsonAdapter adapter)
      throws Exception {
    Map<String, Object> raw = adapter.toMap(json);
    var errors = SchemaValidator.validate(raw, schema.schema());
    if (!errors.isEmpty()) {
      throw new StructuredOutputParseException(errors, json);
    }
    if (schema.innerOutputType() == null) {
      return adapter.fromMap(raw, schema.type());
    }
    return (T)
        OutputSchema.reconstructProvenanced(
            raw,
            m -> {
              try {
                return adapter.fromMap((Map<String, Object>) m, schema.innerOutputType());
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
  }

  /**
   * Extract the first complete balanced JSON object substring from {@code content}, or {@code null}
   * when none exists. Tracks brace depth and respects JSON string-literal escapes so braces inside
   * strings don't terminate the scan early.
   *
   * <p>Use case: models like Claude Sonnet 4.6 sometimes prepend prose to the JSON answer ({@code
   * "The map is built correctly. Here is the final answer:\n\n{...}"}). The raw content fails JSON
   * parsing because of the leading prose; this helper extracts the {@code {...}} portion so the
   * parser can recover without changing the model output.
   */
  public static String extractFirstJsonObject(String content) {
    if (content == null) {
      return null;
    }
    int start = content.indexOf('{');
    if (start < 0) {
      return null;
    }
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = start; i < content.length(); i++) {
      char c = content.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (inString) {
        if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
        continue;
      }
      switch (c) {
        case '"' -> inString = true;
        case '{' -> depth++;
        case '}' -> {
          depth--;
          if (depth == 0) {
            return content.substring(start, i + 1);
          }
        }
        default -> {
          // no-op
        }
      }
    }
    return null;
  }

  /**
   * Strip one layer of {@code ```} / {@code ```json} markdown fences. Returns the input unchanged
   * when no fences are present, so callers can compare {@code stripped.equals(input)} to detect a
   * no-op.
   */
  public static String stripMarkdownWrapper(String json) {
    var result = json;
    if (result.startsWith("```json")) {
      result = result.substring(7);
    } else if (result.startsWith("```")) {
      result = result.substring(3);
    }
    if (result.endsWith("```")) {
      result = result.substring(0, result.length() - 3);
    }
    return result.trim();
  }
}
