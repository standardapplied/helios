/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.anthropic.api.MessagesRequest;
import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.model.StreamEvent;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AnthropicWebToolsTest {

  private final ObjectMapper objectMapper =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private static AnthropicModel model(ModelConfig config) {
    return new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_8, config);
  }

  // ── request emission ──────────────────────────────────────────────────────

  @Test
  void webSearchToggleEmitsServerTool() {
    var config = ModelConfig.newBuilder().withApiKey("k").withWebSearch(true).build();

    var request = model(config).buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertNotNull(request.tools());
    var search = request.tools().getLast();
    assertEquals("web_search_20260318", search.type());
    assertEquals("web_search", search.name());
    assertNull(search.inputSchema(), "server tools carry no input_schema");
  }

  @Test
  void webFetchToggleEmitsServerTool() {
    var config = ModelConfig.newBuilder().withApiKey("k").withWebFetch(true).build();

    var request = model(config).buildRequest(List.of(Message.user("Hi")), List.of(), null);

    var fetch = request.tools().getLast();
    assertEquals("web_fetch_20260318", fetch.type());
    assertEquals("web_fetch", fetch.name());
  }

  @Test
  void webTogglesOffEmitNoServerTools() {
    var config = ModelConfig.newBuilder().withApiKey("k").build();

    var request = model(config).buildRequest(List.of(Message.user("Hi")), List.of(), null);

    assertNull(request.tools());
  }

  @Test
  void serverToolsAppendAfterClientTools() {
    var config = ModelConfig.newBuilder().withApiKey("k").withWebSearch(true).build();
    var clientTool =
        ai.singlr.core.tool.Tool.newBuilder()
            .withName("lookup")
            .withDescription("Look something up")
            .withExecutor((args, ctx) -> ai.singlr.core.tool.ToolResult.success("ok"))
            .build();

    var request =
        model(config).buildRequest(List.of(Message.user("Hi")), List.of(clientTool), null);

    assertEquals(2, request.tools().size());
    assertEquals("lookup", request.tools().getFirst().name());
    assertEquals("web_search", request.tools().getLast().name());
  }

  // ── streaming capture ─────────────────────────────────────────────────────

  private static final String SEARCH_TURN_SSE =
      """
      event: message_start
      data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-opus-4-8","stop_reason":null,"usage":{"input_tokens":25,"output_tokens":1}}}

      event: content_block_start
      data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"I'll search. "}}

      event: content_block_stop
      data: {"type":"content_block_stop","index":0}

      event: content_block_start
      data: {"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search"}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"query\\":\\"helios\\"}"}}

      event: content_block_stop
      data: {"type":"content_block_stop","index":1}

      event: content_block_start
      data: {"type":"content_block_start","index":2,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","url":"https://example.com","title":"Example","encrypted_content":"ENC123","page_age":"May 1, 2026"}]}}

      event: content_block_stop
      data: {"type":"content_block_stop","index":2}

      event: content_block_start
      data: {"type":"content_block_start","index":3,"content_block":{"type":"text","text":""}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"Answer."}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":3,"delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://example.com","title":"Example","encrypted_index":"EI1","cited_text":"snippet"}}}

      event: content_block_stop
      data: {"type":"content_block_stop","index":3}

      event: message_delta
      data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":15}}

      event: message_stop
      data: {"type":"message_stop"}

      """;

  @Test
  void capturesServerToolBlocksCitationsAndRawContent() throws Exception {
    var done = drainDone(SEARCH_TURN_SSE);
    var response = done.response();

    assertEquals("I'll search. Answer.", response.content());
    assertTrue(response.toolCalls().isEmpty(), "server tool use is not a client tool call");

    assertEquals(1, response.citations().size());
    var citation = response.citations().getFirst();
    assertEquals("https://example.com", citation.sourceId());
    assertEquals("Example", citation.title());
    assertEquals("snippet", citation.content());

    assertEquals("end_turn", response.metadata().get(AnthropicModel.STOP_REASON_KEY));

    var raw = response.metadata().get(AnthropicModel.RAW_CONTENT_KEY);
    assertNotNull(raw, "server-tool turns must carry raw content for verbatim echo");
    @SuppressWarnings("unchecked")
    var blocks = (List<Map<String, Object>>) objectMapper.readValue(raw, List.class);
    assertEquals(4, blocks.size());
    assertEquals("text", blocks.get(0).get("type"));
    assertEquals("I'll search. ", blocks.get(0).get("text"));
    assertEquals("server_tool_use", blocks.get(1).get("type"));
    assertEquals(Map.of("query", "helios"), blocks.get(1).get("input"));
    assertEquals("web_search_tool_result", blocks.get(2).get("type"));
    assertTrue(raw.contains("ENC123"), "encrypted_content must round-trip verbatim");
    assertEquals("text", blocks.get(3).get("type"));
    assertNotNull(blocks.get(3).get("citations"));
  }

  @Test
  void plainTurnCarriesNoRawContent() throws Exception {
    var sse =
        """
        event: message_start
        data: {"type":"message_start","message":{"id":"m","type":"message","role":"assistant","content":[],"model":"claude-opus-4-8","stop_reason":null,"usage":{"input_tokens":5,"output_tokens":1}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}

        event: message_stop
        data: {"type":"message_stop"}

        """;
    var done = drainDone(sse);

    assertNull(done.response().metadata().get(AnthropicModel.RAW_CONTENT_KEY));
  }

  // ── verbatim echo on the next turn ────────────────────────────────────────

  @Test
  void assistantMessageWithRawContentEchoesVerbatim() throws Exception {
    var rawJson =
        "[{\"type\":\"text\",\"text\":\"I'll search.\"},"
            + "{\"type\":\"server_tool_use\",\"id\":\"srv1\",\"name\":\"web_search\","
            + "\"input\":{\"query\":\"x\"}},"
            + "{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srv1\","
            + "\"content\":[{\"type\":\"web_search_result\",\"encrypted_content\":\"ENC\"}]}]";
    var message =
        Message.assistant(
            "I'll search.", List.of(), Map.of(AnthropicModel.RAW_CONTENT_KEY, rawJson));

    var entry = AnthropicModel.convertAssistantMessage(message);

    assertEquals("assistant", entry.role());
    @SuppressWarnings("unchecked")
    var blocks = (List<Map<String, Object>>) entry.content();
    assertEquals(3, blocks.size());
    assertEquals("server_tool_use", blocks.get(1).get("type"));
    assertEquals(
        "ENC",
        ((List<Map<String, Object>>) blocks.get(2).get("content"))
            .getFirst()
            .get("encrypted_content"));
  }

  @Test
  void cachingAnnotationSkipsRawEchoMessages() {
    var rawJson = "[{\"type\":\"server_tool_use\",\"id\":\"srv1\",\"name\":\"web_search\"}]";
    var config = ModelConfig.newBuilder().withApiKey("k").withWebSearch(true).build();
    var assistant =
        Message.assistant("searching", List.of(), Map.of(AnthropicModel.RAW_CONTENT_KEY, rawJson));

    var request =
        model(config)
            .buildRequest(
                List.of(Message.user("go"), assistant, Message.user("more")), List.of(), null);

    assertNotNull(request, "raw-echo assistant content must not break cache annotation");
  }

  // ── pause_turn auto-continuation ──────────────────────────────────────────

  private static final String PAUSED_SEGMENT_SSE =
      """
      event: message_start
      data: {"type":"message_start","message":{"id":"m1","type":"message","role":"assistant","content":[],"model":"claude-opus-4-8","stop_reason":null,"usage":{"input_tokens":10,"output_tokens":1}}}

      event: content_block_start
      data: {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srv1","name":"web_search"}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"query\\":\\"q\\"}"}}

      event: content_block_stop
      data: {"type":"content_block_stop","index":0}

      event: message_delta
      data: {"type":"message_delta","delta":{"stop_reason":"pause_turn","stop_sequence":null},"usage":{"output_tokens":4}}

      event: message_stop
      data: {"type":"message_stop"}

      """;

  private static final String FINAL_SEGMENT_SSE =
      """
      event: message_start
      data: {"type":"message_start","message":{"id":"m2","type":"message","role":"assistant","content":[],"model":"claude-opus-4-8","stop_reason":null,"usage":{"input_tokens":20,"output_tokens":1}}}

      event: content_block_start
      data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Done."}}

      event: content_block_stop
      data: {"type":"content_block_stop","index":0}

      event: message_delta
      data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":3}}

      event: message_stop
      data: {"type":"message_stop"}

      """;

  @Test
  void pauseTurnContinuesAutomaticallyAndMergesSegments() {
    var config = ModelConfig.newBuilder().withApiKey("k").withWebSearch(true).build();
    var m = model(config);
    var initial = m.buildRequest(List.of(Message.user("research this")), List.of(), null);

    var openedRequests = new ArrayList<MessagesRequest>();
    var segments = new ArrayList<>(List.of(PAUSED_SEGMENT_SSE, FINAL_SEGMENT_SSE));

    var response =
        m.drainWithContinuation(
            initial,
            request -> {
              openedRequests.add(request);
              return iterator(segments.removeFirst());
            });

    assertEquals(2, openedRequests.size(), "paused turn must re-open exactly one continuation");
    assertEquals("Done.", response.content());
    assertEquals("end_turn", response.metadata().get(AnthropicModel.STOP_REASON_KEY));
    assertEquals(10 + 20, response.usage().inputTokens());
    assertEquals(4 + 3, response.usage().outputTokens());

    var continuation = openedRequests.get(1);
    var echoed = continuation.messages().getLast();
    assertEquals("assistant", echoed.role());
    assertInstanceOf(List.class, echoed.content(), "continuation echoes the paused raw content");
  }

  @Test
  void pauseTurnContinuationIsBounded() {
    var config = ModelConfig.newBuilder().withApiKey("k").withWebSearch(true).build();
    var m = model(config);
    var initial = m.buildRequest(List.of(Message.user("research this")), List.of(), null);

    var ex =
        assertThrows(
            AnthropicException.class,
            () -> m.drainWithContinuation(initial, request -> iterator(PAUSED_SEGMENT_SSE)));
    assertTrue(ex.getMessage().contains("pause_turn"));
  }

  // ── fixtures ──────────────────────────────────────────────────────────────

  private StreamEvent.Done drainDone(String sse) {
    try (var it = iterator(sse)) {
      StreamEvent.Done done = null;
      while (it.hasNext()) {
        if (it.next() instanceof StreamEvent.Done d) {
          done = d;
        }
      }
      assertNotNull(done);
      return done;
    }
  }

  private AnthropicModel.StreamingIterator iterator(String sse) {
    var in = new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8));
    return new AnthropicModel.StreamingIterator(
        fakeResponse(in), objectMapper, Duration.ofSeconds(5));
  }

  private static HttpResponse<InputStream> fakeResponse(InputStream body) {
    return new HttpResponse<>() {
      @Override
      public int statusCode() {
        return 200;
      }

      @Override
      public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (a, b) -> true);
      }

      @Override
      public InputStream body() {
        return body;
      }

      @Override
      public Optional<HttpResponse<InputStream>> previousResponse() {
        return Optional.empty();
      }

      @Override
      public HttpRequest request() {
        return null;
      }

      @Override
      public URI uri() {
        return URI.create("https://test");
      }

      @Override
      public HttpClient.Version version() {
        return HttpClient.Version.HTTP_2;
      }

      @Override
      public Optional<SSLSession> sslSession() {
        return Optional.empty();
      }
    };
  }
}
