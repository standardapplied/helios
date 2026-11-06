/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.core.model;

import java.util.Map;

/**
 * A citation referencing the source of information in a model response.
 *
 * <p>When models retrieve information from vector stores, knowledge bases, or other sources,
 * citations provide attribution back to the original content.
 *
 * @param sourceId identifier for the source (document ID, URL, etc.)
 * @param title optional title of the cited source
 * @param content the cited text or snippet
 * @param startIndex character offset where citation begins in the response (optional)
 * @param endIndex character offset where citation ends in the response (optional)
 * @param metadata additional source-specific metadata
 */
public record Citation(
    String sourceId,
    String title,
    String content,
    Integer startIndex,
    Integer endIndex,
    Map<String, String> metadata) {

  /**
   * Starts a builder with empty metadata and every other component {@code null}.
   *
   * @return a new builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Creates a citation naming only its source and the cited text, with no title, no offsets and
   * empty metadata.
   *
   * @param sourceId the source's identifier, such as a URL or document id; may be null
   * @param content the cited text; may be null
   * @return the citation
   */
  public static Citation of(String sourceId, String content) {
    return new Builder().withSourceId(sourceId).withContent(content).build();
  }

  /**
   * Builds a {@link Citation}. Every setting is optional and kept as given; nothing is validated.
   * Not thread-safe.
   */
  public static class Builder {
    private String sourceId;
    private String title;
    private String content;
    private Integer startIndex;
    private Integer endIndex;
    private Map<String, String> metadata = Map.of();

    private Builder() {}

    /**
     * Sets what identifies the cited source, such as a URL or a document id. {@code null} by
     * default.
     *
     * @param sourceId the source's identifier; may be null
     * @return this builder
     */
    public Builder withSourceId(String sourceId) {
      this.sourceId = sourceId;
      return this;
    }

    /**
     * Sets the cited source's title, for display. {@code null} by default, meaning the provider
     * named none.
     *
     * @param title the title; may be null
     * @return this builder
     */
    public Builder withTitle(String title) {
      this.title = title;
      return this;
    }

    /**
     * Sets the cited text or snippet. {@code null} by default.
     *
     * @param content the cited text; may be null
     * @return this builder
     */
    public Builder withContent(String content) {
      this.content = content;
      return this;
    }

    /**
     * Sets the character offset in the response text where the cited span begins. {@code null} by
     * default, meaning the provider gave no position.
     *
     * @param startIndex the start offset; may be null
     * @return this builder
     */
    public Builder withStartIndex(Integer startIndex) {
      this.startIndex = startIndex;
      return this;
    }

    /**
     * Sets the character offset in the response text where the cited span ends. {@code null} by
     * default, meaning the provider gave no position.
     *
     * @param endIndex the end offset; may be null
     * @return this builder
     */
    public Builder withEndIndex(Integer endIndex) {
      this.endIndex = endIndex;
      return this;
    }

    /**
     * Sets source-specific details the provider attached to the citation. Empty by default.
     *
     * @param metadata the metadata, copied; {@code null} is treated as empty
     * @return this builder
     * @throws NullPointerException if {@code metadata} holds a null key or value
     */
    public Builder withMetadata(Map<String, String> metadata) {
      this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
      return this;
    }

    /**
     * Builds the citation from the settings as they stand.
     *
     * @return the citation
     */
    public Citation build() {
      return new Citation(sourceId, title, content, startIndex, endIndex, metadata);
    }
  }
}
