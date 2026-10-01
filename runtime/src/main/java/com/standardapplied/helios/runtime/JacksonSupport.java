/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import io.helidon.common.GenericType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.Headers;
import io.helidon.http.HttpMediaType;
import io.helidon.http.WritableHeaders;
import io.helidon.http.media.EntityReader;
import io.helidon.http.media.EntityWriter;
import io.helidon.http.media.MediaSupport;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Helidon {@link MediaSupport} backed by Jackson 3.x. Wire via {@code WebServer.builder()
 * .mediaContext(it -> it.addMediaSupport(JacksonSupport.create(mapper)))}.
 */
public final class JacksonSupport implements MediaSupport {

  private final ObjectMapper objectMapper;
  private final JacksonReader<?> reader;
  private final JacksonWriter<?> writer;

  private JacksonSupport(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.reader = new JacksonReader<>(objectMapper);
    this.writer = new JacksonWriter<>(objectMapper);
  }

  /**
   * Create a {@code JacksonSupport} backed by a default {@link JsonMapper}.
   *
   * @return a fresh instance
   */
  public static JacksonSupport create() {
    return new JacksonSupport(JsonMapper.builder().build());
  }

  /**
   * Create a {@code JacksonSupport} backed by the caller's {@link ObjectMapper}.
   *
   * @param objectMapper non-null mapper
   * @return a fresh instance
   */
  public static JacksonSupport create(ObjectMapper objectMapper) {
    return new JacksonSupport(objectMapper);
  }

  /**
   * The wrapped mapper. Useful for handlers that need to (de)serialize directly without going
   * through Helidon's media context.
   *
   * @return the mapper this support owns
   */
  public ObjectMapper objectMapper() {
    return objectMapper;
  }

  @Override
  public String name() {
    return "jackson";
  }

  @Override
  public String type() {
    return "jackson";
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> ReaderResponse<T> reader(GenericType<T> type, Headers headers) {
    if (isJsonContentType(headers)) {
      return new ReaderResponse<>(SupportLevel.SUPPORTED, () -> (EntityReader<T>) reader);
    }
    if (isSupportedType(type)) {
      return new ReaderResponse<>(SupportLevel.COMPATIBLE, () -> (EntityReader<T>) reader);
    }
    return ReaderResponse.unsupported();
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> WriterResponse<T> writer(
      GenericType<T> type, Headers requestHeaders, WritableHeaders<?> responseHeaders) {
    if (isJsonAccepted(requestHeaders)) {
      return new WriterResponse<>(SupportLevel.SUPPORTED, () -> (EntityWriter<T>) writer);
    }
    if (isSupportedType(type)) {
      return new WriterResponse<>(SupportLevel.COMPATIBLE, () -> (EntityWriter<T>) writer);
    }
    return WriterResponse.unsupported();
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> ReaderResponse<T> reader(
      GenericType<T> type, Headers requestHeaders, Headers responseHeaders) {
    if (isJsonContentType(responseHeaders)) {
      return new ReaderResponse<>(SupportLevel.SUPPORTED, () -> (EntityReader<T>) reader);
    }
    if (isSupportedType(type)) {
      return new ReaderResponse<>(SupportLevel.COMPATIBLE, () -> (EntityReader<T>) reader);
    }
    return ReaderResponse.unsupported();
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> WriterResponse<T> writer(GenericType<T> type, WritableHeaders<?> requestHeaders) {
    if (isSupportedType(type)) {
      return new WriterResponse<>(SupportLevel.COMPATIBLE, () -> (EntityWriter<T>) writer);
    }
    return WriterResponse.unsupported();
  }

  private boolean isJsonContentType(Headers headers) {
    return headers.contentType().map(ct -> ct.test(MediaTypes.APPLICATION_JSON)).orElse(false);
  }

  private boolean isJsonAccepted(Headers headers) {
    for (HttpMediaType acceptedType : headers.acceptedTypes()) {
      if (acceptedType.test(MediaTypes.APPLICATION_JSON)) {
        return true;
      }
    }
    return false;
  }

  private <T> boolean isSupportedType(GenericType<T> type) {
    var rawType = type.rawType();
    return !rawType.equals(String.class) && !rawType.equals(byte[].class) && !rawType.isArray();
  }
}
