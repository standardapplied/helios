/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.helidon.common.GenericType;
import io.helidon.common.media.type.MediaTypes;
import io.helidon.http.HeaderNames;
import io.helidon.http.WritableHeaders;
import io.helidon.http.media.MediaSupport.SupportLevel;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

final class JacksonSupportTest {

  private static final GenericType<Map<String, Object>> MAP_TYPE = new GenericType<>() {};

  @Test
  void createDefaultSupplyDefaultMapper() {
    var support = JacksonSupport.create();
    assertEquals("jackson", support.name());
    assertEquals("jackson", support.type());
    assertNotNull(support.objectMapper());
  }

  @Test
  void createWithMapperRetainsMapperReference() {
    var mapper = JsonMapper.builder().build();
    var support = JacksonSupport.create(mapper);
    assertSame(mapper, support.objectMapper());
  }

  @Test
  void readerReturnsSupportedForJsonContentType() {
    var headers = WritableHeaders.create();
    headers.contentType(MediaTypes.APPLICATION_JSON);
    var response = JacksonSupport.create().reader(MAP_TYPE, headers);
    assertEquals(SupportLevel.SUPPORTED, response.support());
    assertNotNull(response.supplier().get());
  }

  @Test
  void readerReturnsCompatibleForObjectTypeWithoutJsonContentType() {
    var headers = WritableHeaders.create();
    var response = JacksonSupport.create().reader(MAP_TYPE, headers);
    assertEquals(SupportLevel.COMPATIBLE, response.support());
  }

  @Test
  void readerReturnsUnsupportedForStringType() {
    var headers = WritableHeaders.create();
    var response = JacksonSupport.create().reader(GenericType.create(String.class), headers);
    assertEquals(SupportLevel.NOT_SUPPORTED, response.support());
  }

  @Test
  void readerWithRequestAndResponseHeaders() {
    var requestHeaders = WritableHeaders.create();
    var responseHeaders = WritableHeaders.create();
    responseHeaders.contentType(MediaTypes.APPLICATION_JSON);
    var response = JacksonSupport.create().reader(MAP_TYPE, requestHeaders, responseHeaders);
    assertEquals(SupportLevel.SUPPORTED, response.support());
  }

  @Test
  void readerWithRequestAndResponseHeadersNoJsonReturnsCompatible() {
    var requestHeaders = WritableHeaders.create();
    var responseHeaders = WritableHeaders.create();
    var response = JacksonSupport.create().reader(MAP_TYPE, requestHeaders, responseHeaders);
    assertEquals(SupportLevel.COMPATIBLE, response.support());
  }

  @Test
  void readerWithRequestAndResponseHeadersStringTypeUnsupported() {
    var requestHeaders = WritableHeaders.create();
    var responseHeaders = WritableHeaders.create();
    var response =
        JacksonSupport.create()
            .reader(GenericType.create(String.class), requestHeaders, responseHeaders);
    assertEquals(SupportLevel.NOT_SUPPORTED, response.support());
  }

  @Test
  void writerReturnsSupportedForJsonAcceptedRequest() {
    var requestHeaders = WritableHeaders.create();
    requestHeaders.set(HeaderNames.ACCEPT, "application/json");
    var responseHeaders = WritableHeaders.create();
    var response = JacksonSupport.create().writer(MAP_TYPE, requestHeaders, responseHeaders);
    assertEquals(SupportLevel.SUPPORTED, response.support());
  }

  @Test
  void writerReturnsCompatibleForObjectTypeWithoutJsonAccept() {
    var requestHeaders = WritableHeaders.create();
    var responseHeaders = WritableHeaders.create();
    var response = JacksonSupport.create().writer(MAP_TYPE, requestHeaders, responseHeaders);
    assertEquals(SupportLevel.COMPATIBLE, response.support());
  }

  @Test
  void writerReturnsUnsupportedForStringType() {
    var requestHeaders = WritableHeaders.create();
    var responseHeaders = WritableHeaders.create();
    var response =
        JacksonSupport.create()
            .writer(GenericType.create(String.class), requestHeaders, responseHeaders);
    assertEquals(SupportLevel.NOT_SUPPORTED, response.support());
  }

  @Test
  void writerSingleHeaderOverloadReturnsCompatibleForObjectType() {
    var responseHeaders = WritableHeaders.create();
    var response = JacksonSupport.create().writer(MAP_TYPE, responseHeaders);
    assertEquals(SupportLevel.COMPATIBLE, response.support());
  }

  @Test
  void writerSingleHeaderOverloadReturnsUnsupportedForStringType() {
    var responseHeaders = WritableHeaders.create();
    var response =
        JacksonSupport.create().writer(GenericType.create(String.class), responseHeaders);
    assertEquals(SupportLevel.NOT_SUPPORTED, response.support());
  }
}
