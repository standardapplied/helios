/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.hooks;

import ai.singlr.core.model.Message;
import ai.singlr.session.DropMiddleToolResultsCompactor;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot passed to a {@link PostCompactHook} describing what just happened.
 *
 * <p>The fields together let callers archive the full pre-compaction transcript, audit how much was
 * lost, and inspect the produced summary — all without coupling to the {@code ContextCompactor}
 * implementation. The {@link #summary()} is extracted as the convenience accessor for the most
 * common use case (Claude SDK's {@code compact_summary}); callers wanting richer detail walk {@link
 * #historyAfter()} directly.
 *
 * @param historyBefore the conversation history at the moment the loop crossed the compaction
 *     watermark, before the compactor ran; non-null, immutable
 * @param historyAfter the conversation history the compactor returned, after the loop swapped it
 *     in; non-null, immutable. {@code historyAfter.size() < historyBefore.size()} is the invariant
 *     that gates this hook firing — if the compactor returned a no-shrink result, this hook is not
 *     fired
 * @param tokensBefore the {@code TokenCounter} estimate over {@code historyBefore}; non-negative
 * @param tokensAfter the {@code TokenCounter} estimate over {@code historyAfter}; non-negative
 * @param removedBlocks {@code historyBefore.size() - historyAfter.size()}; positive
 */
public record CompactionPayload(
    List<Message> historyBefore,
    List<Message> historyAfter,
    long tokensBefore,
    long tokensAfter,
    int removedBlocks) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if either history is null
   * @throws IllegalArgumentException if any numeric value violates its contract
   */
  public CompactionPayload {
    Objects.requireNonNull(historyBefore, "historyBefore must not be null");
    Objects.requireNonNull(historyAfter, "historyAfter must not be null");
    historyBefore = List.copyOf(historyBefore);
    historyAfter = List.copyOf(historyAfter);
    if (tokensBefore < 0L) {
      throw new IllegalArgumentException("tokensBefore must be non-negative, got " + tokensBefore);
    }
    if (tokensAfter < 0L) {
      throw new IllegalArgumentException("tokensAfter must be non-negative, got " + tokensAfter);
    }
    if (removedBlocks <= 0) {
      throw new IllegalArgumentException(
          "removedBlocks must be positive (hook only fires on actual shrink), got "
              + removedBlocks);
    }
  }

  /**
   * The compactor's summary text, when {@link ai.singlr.session.DropMiddleToolResultsCompactor
   * DropMiddleToolResultsCompactor} produced the result. Implementations follow the convention of
   * placing the summary in a single user-role message at index {@code historyAfter.size() - tail -
   * 1} prefixed with {@code "[Earlier context summary]\n"}; this accessor walks the after-history
   * looking for that marker. Returns the empty string when no summary marker is found (e.g. a
   * custom compactor that uses pure-trim semantics) — callers wanting compactor-agnostic detail
   * read {@link #historyAfter()} directly.
   *
   * @return the summary body (without the prefix), or the empty string if absent
   */
  public String summary() {
    var marker = DropMiddleToolResultsCompactor.SUMMARY_PREFIX;
    for (var m : historyAfter) {
      var content = m.content();
      if (content != null && content.startsWith(marker)) {
        return content.substring(marker.length());
      }
    }
    return "";
  }
}
