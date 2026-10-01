/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import io.helidon.common.GenericType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.HeaderValues;
import io.helidon.http.Headers;
import io.helidon.http.HttpMediaType;
import io.helidon.http.WritableHeaders;
import io.helidon.http.media.EntityWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;

/** Jackson 3.x JSON {@link EntityWriter} for Helidon response bodies. */
final class JacksonWriter<T> implements EntityWriter<T> {

  private final ObjectMapper objectMapper;

  JacksonWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void write(
      GenericType<T> type,
      T object,
      OutputStream outputStream,
      Headers requestHeaders,
      WritableHeaders<?> responseHeaders) {

    responseHeaders.setIfAbsent(HeaderValues.CONTENT_TYPE_JSON);

    for (HttpMediaType acceptedType : requestHeaders.acceptedTypes()) {
      if (acceptedType.test(MediaTypes.APPLICATION_JSON)) {
        var charset = acceptedType.charset();
        if (charset.isPresent()) {
          write(type, object, new OutputStreamWriter(outputStream, Charset.forName(charset.get())));
        } else {
          write(type, object, outputStream);
        }
        return;
      }
    }
    write(type, object, outputStream);
  }

  @Override
  public void write(
      GenericType<T> type, T object, OutputStream outputStream, WritableHeaders<?> headers) {
    headers.setIfAbsent(HeaderValues.CONTENT_TYPE_JSON);
    write(type, object, outputStream);
  }

  private void write(GenericType<T> type, T object, Writer out) {
    try {
      writer(type).writeValue(out, object);
    } catch (Exception e) {
      throw new JacksonRuntimeException("Failed to serialize to JSON: " + type, e);
    }
  }

  private void write(GenericType<T> type, T object, OutputStream out) {
    try (out) {
      writer(type).writeValue(out, object);
    } catch (Exception e) {
      throw new JacksonRuntimeException("Failed to serialize to JSON: " + type, e);
    }
  }

  private ObjectWriter writer(GenericType<T> type) {
    Type t = type.type();
    if (t instanceof ParameterizedType) {
      JavaType javaType = objectMapper.getTypeFactory().constructType(t);
      return objectMapper.writerFor(javaType);
    }
    return objectMapper.writerFor(type.rawType());
  }
}
