/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.core.context;

import ai.singlr.core.model.Message;
import java.util.List;

/**
 * Estimates the token cost of a conversation history.
 *
 * <p>Used by the agent loop to detect when running history approaches the model's context window so
 * it can emit a {@code ContextWarning} and (past a higher watermark) trigger compaction. The
 * interface stays provider-agnostic on purpose: the default {@link #charBased() char-based} impl is
 * a cheap heuristic that runs every turn; provider-specific tokenizers (tiktoken for OpenAI, the
 * Anthropic tokenizer for Claude) can be wired in when accuracy matters more than the per-turn
 * cost.
 *
 * <p>Implementations must be safe to call from the single-virtual-thread agent loop. They should
 * never block on I/O — the loop calls {@code count} after every model turn.
 */
public interface TokenCounter {

  /**
   * Estimate the total token count for a conversation history.
   *
   * @param messages the conversation history; non-null, may be empty
   * @return a non-negative token estimate
   * @throws NullPointerException if {@code messages} is null
   */
  long count(List<Message> messages);

  /**
   * The default char-based estimator: roughly {@code ceil(chars/4)} per message plus a small
   * per-message overhead. Conservative (overestimates) for typical English text so the watermark
   * fires slightly earlier than the model's true context fill. Cheap — pure JDK, no allocations
   * beyond the iteration itself.
   *
   * @return a shared, thread-safe counter; never null
   */
  static TokenCounter charBased() {
    return CharBasedTokenCounter.INSTANCE;
  }
}
