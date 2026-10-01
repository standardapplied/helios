/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SSE event envelope from Claude Messages API streaming.
 *
 * <p>The {@code type} field discriminates between event kinds: message_start, content_block_start,
 * content_block_delta, content_block_stop, message_delta, message_stop, error.
 *
 * @param type event type discriminator
 * @param message full message object (for message_start)
 * @param index content block index (for content_block_start/delta/stop)
 * @param contentBlock the content block (for content_block_start)
 * @param delta incremental content (for content_block_delta and message_delta)
 * @param usage token usage (for message_delta)
 * @param error the failure the API reported after the stream opened (for error)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiStreamEvent(
    String type,
    MessagesResponse message,
    Integer index,
    @JsonProperty("content_block") ContentBlock contentBlock,
    ContentDelta delta,
    ApiUsage usage,
    ApiError error) {

  /**
   * Failure reported mid-stream, after the HTTP 200 was already sent.
   *
   * @param type the API error type (e.g. {@code overloaded_error}, {@code invalid_request_error})
   * @param message human-readable description
   */
  public record ApiError(String type, String message) {

    /**
     * Whether re-issuing the same request can succeed: the API was overloaded, failed internally,
     * timed out, or rate limited the caller. Mirrors the HTTP statuses {@link
     * ai.singlr.core.model.ProviderException#isRetryable()} treats as retryable (529, 500, 504,
     * 429).
     *
     * @return true for {@code overloaded_error}, {@code api_error}, {@code timeout_error} and
     *     {@code rate_limit_error}
     */
    public boolean isTransient() {
      return "overloaded_error".equals(type)
          || "api_error".equals(type)
          || "timeout_error".equals(type)
          || "rate_limit_error".equals(type);
    }
  }

  public boolean hasTypeMessageStart() {
    return "message_start".equals(type);
  }

  public boolean hasTypeContentBlockStart() {
    return "content_block_start".equals(type);
  }

  public boolean hasTypeContentBlockDelta() {
    return "content_block_delta".equals(type);
  }

  public boolean hasTypeContentBlockStop() {
    return "content_block_stop".equals(type);
  }

  public boolean hasTypeMessageDelta() {
    return "message_delta".equals(type);
  }

  public boolean hasTypeMessageStop() {
    return "message_stop".equals(type);
  }

  public boolean hasTypeError() {
    return "error".equals(type);
  }
}
