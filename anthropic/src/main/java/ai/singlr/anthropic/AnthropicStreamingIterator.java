/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic;

import ai.singlr.anthropic.api.ApiStreamEvent;
import ai.singlr.anthropic.api.ContentDelta;
import ai.singlr.core.common.Strings;
import ai.singlr.core.model.Citation;
import ai.singlr.core.model.CloseableIterator;
import ai.singlr.core.model.FinishReason;
import ai.singlr.core.model.Response;
import ai.singlr.core.model.StreamEvent;
import ai.singlr.core.model.ToolCall;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * SSE iterator over one Claude Messages API streaming response. Accumulates text, thinking, tool
 * calls, citations, usage, and — for server-tool turns — the raw content-block array required for
 * the verbatim echo on later turns. Extracted from {@code AnthropicModel} to keep both classes
 * within the class-size budget; instantiated by {@code AnthropicModel.openStream} and directly by
 * tests with canned SSE fixtures.
 */
final class AnthropicStreamingIterator implements CloseableIterator<StreamEvent> {
  private final InputStream rawStream;
  private final BufferedReader reader;
  private final ObjectMapper objectMapper;
  private final Duration streamIdleTimeout;
  private final ExecutorService readExecutor;
  private final StringBuilder contentBuilder = new StringBuilder();
  private final List<ToolCall> toolCalls = new ArrayList<>();
  private final Map<Integer, ToolCallAccumulator> toolCallAccumulators = new HashMap<>();
  private final TreeMap<Integer, StringBuilder> textAccumulators = new TreeMap<>();
  private final TreeMap<Integer, List<Map<String, Object>>> citationAccumulators = new TreeMap<>();
  private final TreeMap<Integer, Map<String, Object>> serverBlocks = new TreeMap<>();
  private final Map<Integer, StringBuilder> serverToolInputAccumulators = new HashMap<>();
  private final TreeMap<Integer, ToolCall> completedClientToolBlocks = new TreeMap<>();
  private final List<Citation> citations = new ArrayList<>();
  private boolean sawServerToolBlocks = false;
  // Per-content-block thinking accumulators keyed by block index. Each thinking block in a
  // multi-block message carries its own Anthropic signature; concatenating them into a single
  // buffer (the prior shape) yields a signature the API rejects on the next turn.
  private final LinkedHashMap<Integer, ThinkingAccumulator> thinkingAccumulators =
      new LinkedHashMap<>();
  private StreamEvent nextEvent = null;
  private boolean done = false;
  private int inputTokens = 0;
  private int outputTokens = 0;
  private int cacheCreationInputTokens = 0;
  private int cacheReadInputTokens = 0;
  private String stopReason = null;

  AnthropicStreamingIterator(
      HttpResponse<InputStream> response, ObjectMapper objectMapper, Duration streamIdleTimeout) {
    this.rawStream = response.body();
    this.reader = new BufferedReader(new InputStreamReader(this.rawStream, StandardCharsets.UTF_8));
    this.objectMapper = objectMapper;
    this.streamIdleTimeout = streamIdleTimeout;
    this.readExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public boolean hasNext() {
    if (done) {
      return false;
    }
    if (nextEvent != null) {
      return true;
    }
    nextEvent = readNextEvent();
    return nextEvent != null;
  }

  @Override
  public StreamEvent next() {
    if (nextEvent == null) {
      nextEvent = readNextEvent();
    }
    var event = nextEvent;
    nextEvent = null;
    return event;
  }

  private String readLineWithTimeout() throws IOException {
    Future<String> future = readExecutor.submit((Callable<String>) () -> reader.readLine());
    try {
      return future.get(streamIdleTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new AnthropicException(
          "Stream idle timeout: no data received for " + streamIdleTimeout.toSeconds() + "s");
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException ioe) {
        throw ioe;
      }
      throw new IOException("Stream read failed", e.getCause());
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new IOException("Stream read interrupted", e);
    }
  }

  private StreamEvent readNextEvent() {
    try {
      String line;
      while ((line = readLineWithTimeout()) != null) {
        if (line.startsWith("data: ")) {
          var json = line.substring(6).trim();
          if (json.isEmpty() || json.equals("[DONE]")) {
            continue;
          }
          var event = parseStreamEvent(json);
          if (event != null) {
            return event;
          }
        }
      }
      done = true;
      close();
      return buildDoneEvent();
    } catch (AnthropicException e) {
      done = true;
      close();
      return new StreamEvent.Error(e.getMessage(), e);
    } catch (IOException e) {
      done = true;
      close();
      return new StreamEvent.Error("Stream read error", e);
    }
  }

  private StreamEvent parseStreamEvent(String json) {
    try {
      var event = objectMapper.readValue(json, ApiStreamEvent.class);

      if (event.hasTypeMessageStart()) {
        if (event.message() != null && event.message().usage() != null) {
          var usage = event.message().usage();
          if (usage.inputTokens() != null) {
            inputTokens = usage.inputTokens();
          }
          // Cache tokens are first reported on message_start (initial accounting of cache
          // writes/reads triggered by this turn's prefix). message_delta later carries the
          // running output_tokens; cache counts do not change after start.
          if (usage.cacheCreationInputTokens() != null) {
            cacheCreationInputTokens = usage.cacheCreationInputTokens();
          }
          if (usage.cacheReadInputTokens() != null) {
            cacheReadInputTokens = usage.cacheReadInputTokens();
          }
        }
        return null;
      }

      if (event.hasTypeContentBlockStart()) {
        var block = event.contentBlock();
        var index = event.index();
        if (block != null && index != null) {
          if (block.hasTypeToolUse()) {
            toolCallAccumulators.put(
                index, new ToolCallAccumulator(block.id(), block.name(), new StringBuilder()));
          } else if (block.hasTypeThinking()) {
            thinkingAccumulators.put(
                index, new ThinkingAccumulator(new StringBuilder(), new StringBuilder()));
          } else if (block.hasTypeText()) {
            textAccumulators.put(
                index, new StringBuilder(block.text() == null ? "" : block.text()));
          } else if ("server_tool_use".equals(block.type())) {
            sawServerToolBlocks = true;
            var raw = new LinkedHashMap<String, Object>();
            raw.put("type", "server_tool_use");
            raw.put("id", block.id());
            raw.put("name", block.name());
            serverBlocks.put(index, raw);
            serverToolInputAccumulators.put(index, new StringBuilder());
          } else if ("web_search_tool_result".equals(block.type())
              || "web_fetch_tool_result".equals(block.type())) {
            sawServerToolBlocks = true;
            captureRawServerBlock(index, json);
          }
        }
        return null;
      }

      if (event.hasTypeContentBlockDelta() && event.delta() != null) {
        return handleContentBlockDelta(event.index(), event.delta());
      }

      if (event.hasTypeContentBlockStop()) {
        return handleContentBlockStop(event.index());
      }

      if (event.hasTypeMessageDelta()) {
        if (event.delta() != null && event.delta().stopReason() != null) {
          stopReason = event.delta().stopReason();
        }
        if (event.usage() != null && event.usage().outputTokens() != null) {
          outputTokens = event.usage().outputTokens();
        }
        return null;
      }

      if (event.hasTypeMessageStop()) {
        done = true;
        close();
        return buildDoneEvent();
      }

      if (event.hasTypeError()) {
        return new StreamEvent.Error("API stream error: " + json, null);
      }

      return null;
    } catch (Exception e) {
      return new StreamEvent.Error("Failed to parse stream event", e);
    }
  }

  private StreamEvent handleContentBlockDelta(Integer index, ContentDelta delta) {
    if (delta.hasTypeTextDelta() && delta.text() != null) {
      contentBuilder.append(delta.text());
      if (index != null) {
        textAccumulators.computeIfAbsent(index, i -> new StringBuilder()).append(delta.text());
      }
      return new StreamEvent.TextDelta(delta.text());
    }

    if (delta.hasTypeInputJsonDelta() && delta.partialJson() != null && index != null) {
      var serverInput = serverToolInputAccumulators.get(index);
      if (serverInput != null) {
        serverInput.append(delta.partialJson());
        return null;
      }
      var accumulator = toolCallAccumulators.get(index);
      if (accumulator != null) {
        accumulator.jsonBuilder().append(delta.partialJson());
      }
      return null;
    }

    if (delta.hasTypeCitationsDelta() && delta.citation() != null && index != null) {
      citationAccumulators.computeIfAbsent(index, i -> new ArrayList<>()).add(delta.citation());
      harvestCitation(delta.citation());
      return null;
    }

    if (delta.hasTypeThinkingDelta() && delta.thinking() != null && index != null) {
      // Anthropic may emit thinking_delta events before the corresponding content_block_start
      // (rare but seen in practice). Lazily create the accumulator so we never lose a delta.
      thinkingAccumulators
          .computeIfAbsent(
              index, i -> new ThinkingAccumulator(new StringBuilder(), new StringBuilder()))
          .text()
          .append(delta.thinking());
      // Surface each delta as a token-level event so live UIs can render the model's reasoning
      // as it arrives, not only as the aggregated terminal block.
      return new StreamEvent.ThinkingDelta(delta.thinking());
    }

    if (delta.hasTypeSignatureDelta() && delta.signature() != null && index != null) {
      thinkingAccumulators
          .computeIfAbsent(
              index, i -> new ThinkingAccumulator(new StringBuilder(), new StringBuilder()))
          .signature()
          .append(delta.signature());
      return null;
    }

    return null;
  }

  @SuppressWarnings("unchecked")
  private StreamEvent handleContentBlockStop(Integer index) {
    if (index == null) {
      return null;
    }
    var serverInput = serverToolInputAccumulators.remove(index);
    if (serverInput != null) {
      var jsonStr = serverInput.toString();
      Map<String, Object> input = Map.of();
      if (!jsonStr.isEmpty()) {
        try {
          input = objectMapper.readValue(jsonStr, Map.class);
        } catch (Exception e) {
          input = Map.of("_raw", jsonStr);
        }
      }
      var raw = serverBlocks.get(index);
      if (raw != null) {
        raw.put("input", input);
      }
      return null;
    }
    // Thinking block closing: surface the terminal aggregation so consumers can capture the
    // full reasoning text plus replay signature (used for prefix-cache reuse on subsequent
    // turns). We keep the accumulator in place — buildDoneEvent reads it for Response.thinking.
    var thinking = thinkingAccumulators.get(index);
    if (thinking != null) {
      var fullText = thinking.text().toString();
      var signature = thinking.signature().length() == 0 ? null : thinking.signature().toString();
      if (Strings.isBlank(fullText)) {
        return null;
      }
      return new StreamEvent.ThinkingComplete(fullText, signature);
    }
    var accumulator = toolCallAccumulators.remove(index);
    if (accumulator == null) {
      return null;
    }

    var jsonStr = accumulator.jsonBuilder().toString();
    Map<String, Object> arguments = Map.of();
    if (!jsonStr.isEmpty()) {
      try {
        arguments = objectMapper.readValue(jsonStr, Map.class);
      } catch (Exception e) {
        arguments = Map.of("_raw", jsonStr);
      }
    }

    var tc =
        ToolCall.newBuilder()
            .withId(accumulator.id())
            .withName(accumulator.name())
            .withArguments(arguments)
            .build();
    toolCalls.add(tc);
    completedClientToolBlocks.put(index, tc);
    return new StreamEvent.ToolCallComplete(tc);
  }

  /**
   * Re-parse the raw SSE data line generically and capture the {@code content_block} node verbatim.
   * The typed {@link ContentBlock} record silently drops fields it does not model (e.g. {@code
   * encrypted_content}), which would corrupt the mandatory verbatim echo of server-tool result
   * blocks on later turns.
   */
  @SuppressWarnings("unchecked")
  private void captureRawServerBlock(Integer index, String json) {
    try {
      var eventMap = (Map<String, Object>) objectMapper.readValue(json, Map.class);
      var blockMap = (Map<String, Object>) eventMap.get("content_block");
      if (blockMap != null) {
        serverBlocks.put(index, blockMap);
      }
    } catch (Exception ignored) {
      // Capture failure leaves the block out of RAW_CONTENT; a later echo or pause resume then
      // fails loudly at the API rather than silently sending corrupted content.
    }
  }

  private void harvestCitation(Map<String, Object> citation) {
    var url = citation.get("url");
    var title = citation.get("title");
    var citedText = citation.get("cited_text");
    if (url == null && citedText == null) {
      return;
    }
    citations.add(
        Citation.newBuilder()
            .withSourceId(url != null ? url.toString() : null)
            .withTitle(title != null ? title.toString() : null)
            .withContent(citedText != null ? citedText.toString() : null)
            .build());
  }

  private StreamEvent buildDoneEvent() {
    var content = contentBuilder.toString();
    var calls = toolCalls.isEmpty() ? List.<ToolCall>of() : List.copyOf(toolCalls);

    var finishReason = AnthropicModel.mapStopReason(stopReason);
    if (!calls.isEmpty() && finishReason == FinishReason.STOP) {
      finishReason = FinishReason.TOOL_CALLS;
    }

    Response.Usage usage = null;
    if (inputTokens > 0
        || outputTokens > 0
        || cacheCreationInputTokens > 0
        || cacheReadInputTokens > 0) {
      usage =
          Response.Usage.of(
              inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens);
    }

    // Collect every thinking block in arrival order. The LinkedHashMap iteration order matches
    // the Anthropic content-block index sequence, which the API expects on the next turn.
    var thinkingBlocks = new ArrayList<AnthropicModel.ThinkingBlock>(thinkingAccumulators.size());
    var combinedThinking = new StringBuilder();
    for (var acc : thinkingAccumulators.values()) {
      var sigStr = acc.signature().toString();
      if (sigStr.isEmpty()) {
        continue; // Signature-less thinking blocks cannot round-trip; skip.
      }
      var textStr = acc.text().toString();
      thinkingBlocks.add(new AnthropicModel.ThinkingBlock(textStr, sigStr));
      if (!combinedThinking.isEmpty()) {
        combinedThinking.append("\n\n");
      }
      combinedThinking.append(textStr);
    }
    String thinking = combinedThinking.isEmpty() ? null : combinedThinking.toString();

    var metadata = new HashMap<String, String>();
    if (!thinkingBlocks.isEmpty()) {
      try {
        var arr = new ArrayList<Map<String, String>>(thinkingBlocks.size());
        for (var tb : thinkingBlocks) {
          arr.add(Map.of("text", tb.text(), "signature", tb.signature()));
        }
        metadata.put(AnthropicModel.THINKING_BLOCKS_KEY, objectMapper.writeValueAsString(arr));
      } catch (Exception ignored) {
        // Encoding failure is non-fatal; the legacy single-block keys below are the fallback.
      }
      // Legacy single-block keys — preserved when exactly one thinking block was seen, so older
      // consumers (and tests) continue to observe the same metadata shape.
      if (thinkingBlocks.size() == 1) {
        metadata.put(AnthropicModel.THINKING_KEY, thinkingBlocks.getFirst().text());
        metadata.put(AnthropicModel.THINKING_SIGNATURE_KEY, thinkingBlocks.getFirst().signature());
      }
    }
    if (stopReason != null) {
      metadata.put(AnthropicModel.STOP_REASON_KEY, stopReason);
    }
    // Raw content is needed for the verbatim echo after server-tool turns AND for resuming a
    // pause that landed before the first server-tool block streamed (the protocol permits it).
    if (sawServerToolBlocks || "pause_turn".equals(stopReason)) {
      var rawContent = assembleRawContent();
      if (rawContent != null) {
        metadata.put(AnthropicModel.RAW_CONTENT_KEY, rawContent);
      }
    }

    var response =
        Response.newBuilder()
            .withContent(content)
            .withToolCalls(calls)
            .withFinishReason(finishReason)
            .withUsage(usage)
            .withThinking(thinking)
            .withCitations(citations.isEmpty() ? List.of() : List.copyOf(citations))
            .withMetadata(metadata.isEmpty() ? Map.of() : Map.copyOf(metadata))
            .build();

    return new StreamEvent.Done(response);
  }

  /**
   * Rebuild the assistant turn's content-block array in original stream-index order for the
   * verbatim echo the API requires after server-tool turns. Server blocks are the raw captured
   * JSON; text, thinking, and client tool_use blocks are reconstructed from their accumulators.
   * Returns {@code null} when serialization fails — the metadata key is then absent and a later
   * echo or resume fails loudly at the API instead of sending corrupted content.
   */
  private String assembleRawContent() {
    var indices = new TreeSet<Integer>();
    indices.addAll(textAccumulators.keySet());
    indices.addAll(thinkingAccumulators.keySet());
    indices.addAll(serverBlocks.keySet());
    indices.addAll(completedClientToolBlocks.keySet());

    var blocks = new ArrayList<Map<String, Object>>();
    for (var index : indices) {
      var server = serverBlocks.get(index);
      if (server != null) {
        blocks.add(server);
        continue;
      }
      var text = textAccumulators.get(index);
      if (text != null) {
        if (text.isEmpty()) {
          continue;
        }
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "text");
        block.put("text", text.toString());
        var blockCitations = citationAccumulators.get(index);
        if (blockCitations != null && !blockCitations.isEmpty()) {
          block.put("citations", blockCitations);
        }
        blocks.add(block);
        continue;
      }
      var thinkingAcc = thinkingAccumulators.get(index);
      if (thinkingAcc != null) {
        var signature = thinkingAcc.signature().toString();
        if (signature.isEmpty()) {
          continue;
        }
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "thinking");
        block.put("thinking", thinkingAcc.text().toString());
        block.put("signature", signature);
        blocks.add(block);
        continue;
      }
      var toolCall = completedClientToolBlocks.get(index);
      if (toolCall != null) {
        var block = new LinkedHashMap<String, Object>();
        block.put("type", "tool_use");
        block.put("id", toolCall.id());
        block.put("name", toolCall.name());
        block.put("input", toolCall.arguments());
        blocks.add(block);
      }
    }
    try {
      return objectMapper.writeValueAsString(blocks);
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public void close() {
    done = true;
    readExecutor.shutdownNow();
    try {
      rawStream.close();
    } catch (IOException ignored) {
    }
    try {
      reader.close();
    } catch (IOException ignored) {
    }
  }

  private record ToolCallAccumulator(String id, String name, StringBuilder jsonBuilder) {}

  /**
   * Per-content-block thinking accumulator. {@code text} buffers thinking deltas; {@code signature}
   * buffers signature deltas. Each thinking block in a multi-block message gets its own pair so the
   * Anthropic-issued signature stays attached to the matching text.
   */
  private record ThinkingAccumulator(StringBuilder text, StringBuilder signature) {}
}
