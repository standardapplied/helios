/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import io.helidon.common.GenericType;
import io.helidon.http.Headers;
import io.helidon.http.HttpMediaType;
import io.helidon.http.media.EntityReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/** Jackson 3.x JSON {@link EntityReader} for Helidon request bodies. */
final class JacksonReader<T> implements EntityReader<T> {

  private final ObjectMapper objectMapper;

  JacksonReader(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public T read(GenericType<T> type, InputStream stream, Headers headers) {
    return read(type, stream, contentTypeCharset(headers));
  }

  @Override
  public T read(
      GenericType<T> type, InputStream stream, Headers requestHeaders, Headers responseHeaders) {
    return read(type, stream, contentTypeCharset(responseHeaders));
  }

  @SuppressWarnings("unchecked")
  private T read(GenericType<T> type, InputStream in, Charset charset) {
    try (Reader r = new InputStreamReader(in, charset)) {
      Type t = type.type();
      if (t instanceof ParameterizedType) {
        JavaType javaType = objectMapper.getTypeFactory().constructType(t);
        return objectMapper.readValue(r, javaType);
      }
      return objectMapper.readValue(r, (Class<T>) type.rawType());
    } catch (IOException | RuntimeException e) {
      throw new JacksonRuntimeException("Failed to deserialize JSON to " + type, e);
    }
  }

  private Charset contentTypeCharset(Headers headers) {
    return headers
        .contentType()
        .flatMap(HttpMediaType::charset)
        .map(Charset::forName)
        .orElse(StandardCharsets.UTF_8);
  }
}
