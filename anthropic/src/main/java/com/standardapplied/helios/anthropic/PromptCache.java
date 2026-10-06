/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.anthropic;

import com.standardapplied.helios.anthropic.api.CacheControl;
import com.standardapplied.helios.anthropic.api.ContentBlock;
import com.standardapplied.helios.anthropic.api.MessagesRequest;
import com.standardapplied.helios.anthropic.api.MessagesRequest.MessageEntry;
import com.standardapplied.helios.anthropic.api.SystemContent;
import com.standardapplied.helios.anthropic.api.ToolDefinition;
import com.standardapplied.helios.core.common.Strings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Places a request's prompt-cache breakpoints under a {@link CachePolicy}: one on the system
 * prompt, one on the last client tool, and one each on the last two messages — Anthropic's
 * four-breakpoint budget on the canonical agent-loop shape.
 *
 * <p>The second-to-last message matters because Anthropic resolves each breakpoint within a
 * 20-block lookback window. Past about five tool-calling turns a lone last-message breakpoint can
 * no longer see the system and tools prefix and every turn misses the cache; a breakpoint on the
 * penultimate message gives the next turn's lookback a stable rolling write to find.
 */
final class PromptCache {

  private PromptCache() {}

  /** Sets {@code system} and {@code tools} on {@code request} and marks {@code messages}. */
  static void apply(
      CachePolicy policy,
      MessagesRequest.Builder request,
      String system,
      AnthropicTools tools,
      List<MessageEntry> messages) {
    if (!policy.enabled()) {
      request.withSystem(system);
      request.withTools(tools.all());
      return;
    }
    var breakpoint = policy.breakpoint();
    request.withSystem(cachedSystem(system, breakpoint));
    request.withTools(tools.followedByServerTools(withCachedTail(tools.client(), breakpoint)));
    markLastMessages(messages, breakpoint);
  }

  /**
   * A non-blank system prompt in the array shape, its one block carrying the breakpoint: the API
   * honours {@code cache_control} only there, so the plain-string form would never hit the cache.
   */
  private static List<SystemContent> cachedSystem(String system, CacheControl breakpoint) {
    if (Strings.isBlank(system)) {
      return null;
    }
    return List.of(SystemContent.text(system).withCacheControl(breakpoint));
  }

  /** {@code tools} with the breakpoint on the last, which covers the whole tools section. */
  private static List<ToolDefinition> withCachedTail(
      List<ToolDefinition> tools, CacheControl breakpoint) {
    if (tools == null || tools.isEmpty()) {
      return tools;
    }
    var copy = new ArrayList<ToolDefinition>(tools.subList(0, tools.size() - 1));
    copy.add(tools.getLast().withCacheControl(breakpoint));
    return List.copyOf(copy);
  }

  private static void markLastMessages(List<MessageEntry> messages, CacheControl breakpoint) {
    if (messages.isEmpty()) {
      return;
    }
    var last = messages.size() - 1;
    mark(messages, last, breakpoint);
    if (messages.size() >= 2) {
      mark(messages, last - 1, breakpoint);
    }
  }

  /**
   * Puts the breakpoint on the last block of the message at {@code index}, first turning a string
   * message into one text block, since {@code cache_control} needs a block. An empty message is
   * left alone: an empty cache block is never synthesized.
   */
  private static void mark(List<MessageEntry> messages, int index, CacheControl breakpoint) {
    var entry = messages.get(index);
    if (entry.content() instanceof String text) {
      if (!text.isEmpty()) {
        var block = ContentBlock.text(text).withCacheControl(breakpoint);
        messages.set(index, new MessageEntry(entry.role(), List.of(block)));
      }
      return;
    }
    if (entry.content() instanceof List<?> blocks && !blocks.isEmpty()) {
      var tail = cachedTail(blocks.getLast(), breakpoint);
      if (tail != null) {
        var marked = new ArrayList<Object>(blocks.subList(0, blocks.size() - 1));
        marked.add(tail);
        messages.set(index, new MessageEntry(entry.role(), List.copyOf(marked)));
      }
    }
  }

  /**
   * The block carrying the breakpoint, or {@code null} when it must stay untouched. A typed block
   * always takes it; a verbatim block only when it is a client {@code tool_use}, the tail of every
   * tool-calling turn and wire-identical to its typed form. Server-tool results and cited text go
   * back exactly as they came.
   */
  @SuppressWarnings("unchecked")
  private static Object cachedTail(Object last, CacheControl breakpoint) {
    if (last instanceof ContentBlock block) {
      return block.withCacheControl(breakpoint);
    }
    if (last instanceof Map<?, ?> raw && "tool_use".equals(raw.get("type"))) {
      var marked = new LinkedHashMap<String, Object>((Map<String, Object>) raw);
      marked.put("cache_control", breakpoint);
      return marked;
    }
    return null;
  }
}
