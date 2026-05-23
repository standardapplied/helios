/* Copyright (c) 2026 Singular | SPDX-License-Identifier: MIT */

package ai.singlr.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.model.Message;
import ai.singlr.core.model.ModelConfig;
import ai.singlr.core.schema.OutputSchema;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the Light Grid bug report (2026-05-22): {@code outputSchema + tools} used
 * to terminate a tool-using session on turn 1 with {@code Failed to parse structured output}.
 *
 * <p>Two cooperating fixes landed in 2.3.3:
 *
 * <ol>
 *   <li>{@link AnthropicModel#chat(java.util.List, java.util.List,
 *       ai.singlr.core.schema.OutputSchema)} skips {@code parseStructuredContent} when {@code
 *       response.toolCalls()} is non-empty — tool-calling turns are intermediate, structured output
 *       is the deliverable of a later text-only turn.
 *   <li>{@link AnthropicModel#buildRequest} rephrases the schema instruction when tools are present
 *       so it stops fighting the deployer's "use tools first" guidance.
 * </ol>
 */
class SchemaPlusToolsBugReproTest {

  public record SimpleAnswer(String text) {}

  private static Tool searchTool() {
    return Tool.newBuilder()
        .withName("search")
        .withDescription("Search the knowledge base")
        .withExecutor((args, ctx) -> ToolResult.success("ok"))
        .build();
  }

  // ---------------------------------------------------------------------------------------------
  // Claim #1 — schema instruction is contextualised when tools are present
  // ---------------------------------------------------------------------------------------------

  @Test
  void buildRequestWithSchemaAndToolsAppendsTheTurnAwareInstruction() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    var schema = Map.<String, Object>of("type", "object", "properties", Map.of());

    var request =
        model.buildRequest(
            java.util.List.of(Message.user("Find me three matches.")),
            java.util.List.of(searchTool()),
            schema);

    var systemText = request.systemAsText();
    assertTrue(
        systemText.contains("You may call the available tools"),
        "tool-using schema instruction must acknowledge the loop; system=\n" + systemText);
    assertTrue(
        systemText.contains("When you are ready to emit your final answer"),
        "tool-using schema instruction must defer JSON to the final turn; system=\n" + systemText);
    assertFalse(
        systemText.contains("You must respond with valid JSON"),
        "the unconditional 'must respond with JSON' phrasing must not fire on a tool-using"
            + " session; system=\n"
            + systemText);
  }

  @Test
  void buildRequestWithSchemaButNoToolsKeepsTheBareJsonInstruction() {
    var config = ModelConfig.newBuilder().withApiKey("test-key").build();
    var model = new AnthropicModel(AnthropicModelId.CLAUDE_SONNET_4_6, config);
    var schema = Map.<String, Object>of("type", "object", "properties", Map.of());

    var request =
        model.buildRequest(java.util.List.of(Message.user("Extract")), java.util.List.of(), schema);

    var systemText = request.systemAsText();
    assertTrue(
        systemText.contains("You must respond with valid JSON"),
        "tool-less schema instruction stays bare; system=\n" + systemText);
    assertFalse(
        systemText.contains("You may call the available tools"),
        "the loop-aware phrasing only applies when tools are present; system=\n" + systemText);
  }

  // ---------------------------------------------------------------------------------------------
  // Claim #2 — chat(messages, tools, outputSchema) skips parse when toolCalls are present
  // ---------------------------------------------------------------------------------------------

  private ServerSocket server;
  private ExecutorService accepts;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
    accepts = Executors.newSingleThreadExecutor(r -> Thread.ofVirtual().unstarted(r));
    baseUrl = "http://127.0.0.1:" + server.getLocalPort() + "/v1/messages";
  }

  @AfterEach
  void stopServer() throws IOException {
    if (server != null) {
      server.close();
    }
    if (accepts != null) {
      accepts.shutdownNow();
    }
  }

  /**
   * Accept one connection, swallow the request, and reply with an HTTP/1.1 response carrying an SSE
   * body that emits a tool_use block (and optionally a prose preamble before it). Terminates the
   * message with {@code stop_reason=tool_use}.
   */
  private void respondWithToolUse(String preamble) {
    var sseBody = buildToolUseSse(preamble);
    accepts.submit(
        () -> {
          try (Socket sock = server.accept();
              var in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
              OutputStream out = sock.getOutputStream()) {
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
              // discard request line + headers
            }
            var bytes = sseBody.getBytes(StandardCharsets.UTF_8);
            var headers =
                "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream\r\n"
                    + "Content-Length: "
                    + bytes.length
                    + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
          } catch (IOException ignored) {
            // ServerSocket closed by @AfterEach — expected on shutdown.
          }
        });
  }

  private static String buildToolUseSse(String preamble) {
    var sb = new StringBuilder();
    sb.append("event: message_start\n")
        .append("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",")
        .append("\"type\":\"message\",\"role\":\"assistant\",\"content\":[],")
        .append("\"model\":\"claude-opus-4-7-20260101\",\"stop_reason\":null,")
        .append("\"usage\":{\"input_tokens\":50,\"output_tokens\":1}}}\n\n");

    int idx = 0;
    if (preamble != null && !preamble.isEmpty()) {
      sb.append("event: content_block_start\n")
          .append("data: {\"type\":\"content_block_start\",\"index\":")
          .append(idx)
          .append(",\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n");
      sb.append("event: content_block_delta\n")
          .append("data: {\"type\":\"content_block_delta\",\"index\":")
          .append(idx)
          .append(",\"delta\":{\"type\":\"text_delta\",\"text\":\"")
          .append(preamble.replace("\"", "\\\""))
          .append("\"}}\n\n");
      sb.append("event: content_block_stop\n")
          .append("data: {\"type\":\"content_block_stop\",\"index\":")
          .append(idx)
          .append("}\n\n");
      idx++;
    }

    sb.append("event: content_block_start\n")
        .append("data: {\"type\":\"content_block_start\",\"index\":")
        .append(idx)
        .append(",\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_42\",")
        .append("\"name\":\"search\",\"input\":{}}}\n\n");
    sb.append("event: content_block_delta\n")
        .append("data: {\"type\":\"content_block_delta\",\"index\":")
        .append(idx)
        .append(",\"delta\":{\"type\":\"input_json_delta\",")
        .append("\"partial_json\":\"{\\\"q\\\":\\\"matches\\\"}\"}}\n\n");
    sb.append("event: content_block_stop\n")
        .append("data: {\"type\":\"content_block_stop\",\"index\":")
        .append(idx)
        .append("}\n\n");

    sb.append("event: message_delta\n")
        .append("data: {\"type\":\"message_delta\",")
        .append("\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},")
        .append("\"usage\":{\"output_tokens\":20}}\n\n");
    sb.append("event: message_stop\n").append("data: {\"type\":\"message_stop\"}\n\n");
    return sb.toString();
  }

  /**
   * Direct reproduction of Light Grid's canary failure mode. The model returns prose preamble +
   * tool_use. Pre-2.3.3 this threw {@code AnthropicException("Failed to parse structured output:
   * I'll work through this carefully.")}; post-fix it returns a Response with tool calls present
   * and {@code parsed} null (the structured output is the deliverable of a later turn).
   */
  @Test
  void chatWithProsePreambleAndToolCallsReturnsToolCallsWithoutParsing() {
    respondWithToolUse("I'll work through this carefully.");
    var config = ModelConfig.newBuilder().withApiKey("test-key").withBaseUrl(baseUrl).build();
    try (var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config)) {
      var schema = OutputSchema.of(SimpleAnswer.class);
      var response =
          model.chat(
              java.util.List.of(Message.user("Find me three matches.")),
              java.util.List.of(searchTool()),
              schema);
      assertFalse(response.toolCalls().isEmpty(), "tool call must be surfaced");
      assertEquals("search", response.toolCalls().getFirst().name());
      assertNull(response.parsed(), "parsed slot stays null on a tool-calling turn");
      assertTrue(
          response.content().contains("I'll work through this carefully"),
          "prose preamble must still be preserved in the Response.content for history");
    }
  }

  /**
   * Tighter variant — tool_use response with no assistant text. Same shape as above: the tool call
   * is surfaced and {@code parsed} is null.
   */
  @Test
  void chatWithToolUseAndNoProseReturnsNullParsed() {
    respondWithToolUse(null);
    var config = ModelConfig.newBuilder().withApiKey("test-key").withBaseUrl(baseUrl).build();
    try (var model = new AnthropicModel(AnthropicModelId.CLAUDE_OPUS_4_7, config)) {
      var schema = OutputSchema.of(SimpleAnswer.class);
      var response =
          model.chat(
              java.util.List.of(Message.user("Find me three matches.")),
              java.util.List.of(searchTool()),
              schema);
      assertFalse(response.toolCalls().isEmpty(), "tool call must be surfaced");
      assertEquals("search", response.toolCalls().getFirst().name());
      assertNull(response.parsed(), "parsed slot stays null on a blank-prose tool-use turn");
    }
  }
}
