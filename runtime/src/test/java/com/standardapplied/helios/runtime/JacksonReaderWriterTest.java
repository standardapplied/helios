/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.common.GenericType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.WritableHeaders;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

final class JacksonReaderWriterTest {

  private static final GenericType<Map<String, Object>> MAP_TYPE = new GenericType<>() {};

  private final JsonMapper mapper = JsonMapper.builder().build();

  // ── JacksonReader ─────────────────────────────────────────────────────────

  @Test
  void readerParsesJsonViaSingleHeaderOverload() {
    var reader = new JacksonReader<Map<String, Object>>(mapper);
    var headers = WritableHeaders.create();
    headers.contentType(MediaTypes.APPLICATION_JSON);
    var stream = new ByteArrayInputStream("{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8));
    Map<String, Object> result = reader.read(MAP_TYPE, stream, headers);
    assertEquals("v", result.get("k"));
  }

  @Test
  void readerParsesJsonViaTwoHeaderOverload() {
    var reader = new JacksonReader<Map<String, Object>>(mapper);
    var requestHeaders = WritableHeaders.create();
    var responseHeaders = WritableHeaders.create();
    responseHeaders.contentType(MediaTypes.APPLICATION_JSON);
    var stream = new ByteArrayInputStream("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
    Map<String, Object> result = reader.read(MAP_TYPE, stream, requestHeaders, responseHeaders);
    assertEquals(1, result.get("a"));
  }

  @Test
  void readerParsesParameterizedType() {
    var reader = new JacksonReader<List<String>>(mapper);
    var headers = WritableHeaders.create();
    headers.contentType(MediaTypes.APPLICATION_JSON);
    var stream = new ByteArrayInputStream("[\"a\",\"b\"]".getBytes(StandardCharsets.UTF_8));
    List<String> result = reader.read(new GenericType<List<String>>() {}, stream, headers);
    assertEquals(List.of("a", "b"), result);
  }

  @Test
  void readerThrowsOnMalformedJson() {
    var reader = new JacksonReader<Map<String, Object>>(mapper);
    var headers = WritableHeaders.create();
    headers.contentType(MediaTypes.APPLICATION_JSON);
    var stream = new ByteArrayInputStream("{broken".getBytes(StandardCharsets.UTF_8));
    var ex =
        assertThrows(JacksonRuntimeException.class, () -> reader.read(MAP_TYPE, stream, headers));
    assertTrue(ex.getMessage().startsWith("Failed to deserialize JSON"));
  }

  @Test
  void readerHonorsCharsetFromContentType() {
    var reader = new JacksonReader<Map<String, Object>>(mapper);
    var headers = WritableHeaders.create();
    headers.contentType(MediaTypes.create("application/json; charset=UTF-8"));
    var stream = new ByteArrayInputStream("{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8));
    Map<String, Object> result = reader.read(MAP_TYPE, stream, headers);
    assertEquals("v", result.get("k"));
  }

  // ── JacksonWriter ─────────────────────────────────────────────────────────

  @Test
  void writerSerializesWithSingleHeaderOverload() {
    var writer = new JacksonWriter<Map<String, Object>>(mapper);
    var headers = WritableHeaders.create();
    var out = new ByteArrayOutputStream();
    writer.write(MAP_TYPE, Map.of("k", "v"), out, headers);
    assertEquals("{\"k\":\"v\"}", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void writerSerializesWithRequestAndResponseHeaders() {
    var writer = new JacksonWriter<Map<String, Object>>(mapper);
    var requestHeaders = WritableHeaders.create();
    var responseHeaders = WritableHeaders.create();
    var out = new ByteArrayOutputStream();
    writer.write(MAP_TYPE, Map.of("k", "v"), out, requestHeaders, responseHeaders);
    assertEquals("{\"k\":\"v\"}", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void writerHonorsJsonAcceptedCharset() {
    var writer = new JacksonWriter<Map<String, Object>>(mapper);
    var requestHeaders = WritableHeaders.create();
    requestHeaders.set(io.helidon.http.HeaderNames.ACCEPT, "application/json; charset=UTF-8");
    var responseHeaders = WritableHeaders.create();
    var out = new ByteArrayOutputStream();
    writer.write(MAP_TYPE, Map.of("k", "v"), out, requestHeaders, responseHeaders);
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"k\":\"v\""));
  }

  @Test
  void writerHonorsJsonAcceptedNoCharset() {
    var writer = new JacksonWriter<Map<String, Object>>(mapper);
    var requestHeaders = WritableHeaders.create();
    requestHeaders.set(io.helidon.http.HeaderNames.ACCEPT, "application/json");
    var responseHeaders = WritableHeaders.create();
    var out = new ByteArrayOutputStream();
    writer.write(MAP_TYPE, Map.of("k", "v"), out, requestHeaders, responseHeaders);
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"k\":\"v\""));
  }

  @Test
  void writerSerializesParameterizedType() {
    var writer = new JacksonWriter<List<String>>(mapper);
    var headers = WritableHeaders.create();
    var out = new ByteArrayOutputStream();
    writer.write(new GenericType<List<String>>() {}, List.of("a", "b"), out, headers);
    assertEquals("[\"a\",\"b\"]", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void successResultSerializesGroundingCitationsOntoTheWire() {
    var success =
        new ai.singlr.session.ResultMessage.Success(
            "sess-1",
            "Canberra is the capital.",
            ai.singlr.core.model.Response.Usage.of(1, 1),
            ai.singlr.core.common.CostEstimate.zero(),
            java.time.Duration.ZERO,
            List.of(
                ai.singlr.core.model.Citation.newBuilder()
                    .withSourceId("https://en.wikipedia.org/x")
                    .withTitle("wikipedia.org")
                    .build()));
    var writer = new JacksonWriter<Object>(mapper);
    var headers = WritableHeaders.create();
    var out = new ByteArrayOutputStream();
    writer.write(new GenericType<Object>() {}, success, out, headers);
    var json = out.toString(StandardCharsets.UTF_8);
    assertTrue(
        json.contains("\"citations\""), "Success JSON must carry a citations array: " + json);
    assertTrue(json.contains("https://en.wikipedia.org/x"), json);
  }

  @Test
  void writerWrapsSerializationFailures() {
    var writer = new JacksonWriter<Object>(mapper);
    var headers = WritableHeaders.create();
    var out = new ByteArrayOutputStream();
    // An object Jackson can't serialize: a non-serializable lambda-style object with cycles
    var bad = new java.util.HashMap<String, Object>();
    bad.put("self", bad); // cyclic reference
    var ex =
        assertThrows(
            JacksonRuntimeException.class,
            () -> writer.write(GenericType.create(Object.class), bad, out, headers));
    assertTrue(ex.getMessage().startsWith("Failed to serialize"));
  }

  @Test
  void writerWrapsSerializationFailuresOnWriterPath() {
    var writer = new JacksonWriter<Object>(mapper);
    var requestHeaders = WritableHeaders.create();
    requestHeaders.set(io.helidon.http.HeaderNames.ACCEPT, "application/json; charset=UTF-8");
    var responseHeaders = WritableHeaders.create();
    var out = new ByteArrayOutputStream();
    var bad = new java.util.HashMap<String, Object>();
    bad.put("self", bad);
    var ex =
        assertThrows(
            JacksonRuntimeException.class,
            () ->
                writer.write(
                    GenericType.create(Object.class), bad, out, requestHeaders, responseHeaders));
    assertTrue(ex.getMessage().startsWith("Failed to serialize"));
  }

  // ── JacksonRuntimeException ──────────────────────────────────────────────

  @Test
  void runtimeExceptionPreservesCause() {
    var cause = new RuntimeException("nested");
    var ex = new JacksonRuntimeException("wrapped", cause);
    assertEquals("wrapped", ex.getMessage());
    assertEquals(cause, ex.getCause());
  }
}
