/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.anthropic.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Content block in Claude Messages API.
 *
 * <p>Represents text, tool_use, tool_result, thinking, image, or document content blocks used in
 * both request messages and response content. Annotating a block with {@link #cacheControl()} marks
 * the prefix up to and including this block as a prompt-cache breakpoint.
 *
 * @param type the block type: {@code "text"}, {@code "tool_use"}, {@code "tool_result"}, {@code
 *     "thinking"}, {@code "image"}, or {@code "document"}
 * @param text text content (for type "text")
 * @param id tool use ID (for type "tool_use")
 * @param name tool name (for type "tool_use")
 * @param input tool arguments (for type "tool_use")
 * @param toolUseId the tool_use ID this result responds to (for type "tool_result")
 * @param content result content — a {@link String} for client tool results, or the raw list shape
 *     for server-tool result blocks parsed from responses (for type "tool_result" and server-tool
 *     result types)
 * @param thinking thinking text (for type "thinking")
 * @param signature cryptographic signature for thinking round-trip (for type "thinking")
 * @param source nested source object (for "image" / "document" blocks) carrying the base64-encoded
 *     bytes plus media type
 * @param cacheControl optional prompt-cache breakpoint anchored to this block
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentBlock(
    String type,
    String text,
    String id,
    String name,
    Map<String, Object> input,
    @JsonProperty("tool_use_id") String toolUseId,
    Object content,
    String thinking,
    String signature,
    Source source,
    @JsonProperty("cache_control") CacheControl cacheControl) {

  public static ContentBlock text(String text) {
    return new ContentBlock("text", text, null, null, null, null, null, null, null, null, null);
  }

  public static ContentBlock toolUse(String id, String name, Map<String, Object> input) {
    return new ContentBlock("tool_use", null, id, name, input, null, null, null, null, null, null);
  }

  public static ContentBlock toolResult(String toolUseId, String content) {
    return new ContentBlock(
        "tool_result", null, null, null, null, toolUseId, content, null, null, null, null);
  }

  public static ContentBlock thinking(String thinking, String signature) {
    return new ContentBlock(
        "thinking", null, null, null, null, null, null, thinking, signature, null, null);
  }

  /**
   * Image content block. The Anthropic API expects {@code source.type=="base64"} with the
   * base64-encoded bytes and matching media type (e.g. {@code "image/png"}).
   *
   * @param mediaType the IANA media type; non-blank
   * @param base64Data the base64-encoded image bytes; non-blank
   * @return a fresh image block
   */
  public static ContentBlock image(String mediaType, String base64Data) {
    return new ContentBlock(
        "image",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Source.base64(mediaType, base64Data),
        null);
  }

  /**
   * Document content block. The Anthropic API renders PDFs natively via this content type.
   *
   * @param mediaType typically {@code "application/pdf"}; non-blank
   * @param base64Data the base64-encoded document bytes; non-blank
   * @return a fresh document block
   */
  public static ContentBlock document(String mediaType, String base64Data) {
    return new ContentBlock(
        "document",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Source.base64(mediaType, base64Data),
        null);
  }

  /**
   * Return a copy of this block carrying the given prompt-cache breakpoint. The Anthropic server
   * treats every block at-or-before this one as the cache prefix.
   *
   * @param cacheControl the breakpoint; non-null
   * @return a copy with {@code cache_control} set
   */
  public ContentBlock withCacheControl(CacheControl cacheControl) {
    java.util.Objects.requireNonNull(cacheControl, "cacheControl must not be null");
    return new ContentBlock(
        type, text, id, name, input, toolUseId, content, thinking, signature, source, cacheControl);
  }

  public boolean hasTypeText() {
    return "text".equals(type);
  }

  public boolean hasTypeToolUse() {
    return "tool_use".equals(type);
  }

  public boolean hasTypeToolResult() {
    return "tool_result".equals(type);
  }

  public boolean hasTypeThinking() {
    return "thinking".equals(type);
  }

  public boolean hasTypeImage() {
    return "image".equals(type);
  }

  public boolean hasTypeDocument() {
    return "document".equals(type);
  }

  /**
   * Nested source object for {@code image} and {@code document} content blocks.
   *
   * @param type the source kind; always {@code "base64"} in the current API surface
   * @param mediaType the IANA media type
   * @param data the base64-encoded bytes
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Source(String type, @JsonProperty("media_type") String mediaType, String data) {

    /**
     * Base64 source factory.
     *
     * @param mediaType the IANA media type; non-blank
     * @param data the base64-encoded payload; non-blank
     * @return a fresh source
     */
    public static Source base64(String mediaType, String data) {
      return new Source("base64", mediaType, data);
    }
  }
}
