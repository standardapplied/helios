/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.model;

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

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Citation of(String sourceId, String content) {
    return new Builder().withSourceId(sourceId).withContent(content).build();
  }

  public static class Builder {
    private String sourceId;
    private String title;
    private String content;
    private Integer startIndex;
    private Integer endIndex;
    private Map<String, String> metadata = Map.of();

    private Builder() {}

    public Builder withSourceId(String sourceId) {
      this.sourceId = sourceId;
      return this;
    }

    public Builder withTitle(String title) {
      this.title = title;
      return this;
    }

    public Builder withContent(String content) {
      this.content = content;
      return this;
    }

    public Builder withStartIndex(Integer startIndex) {
      this.startIndex = startIndex;
      return this;
    }

    public Builder withEndIndex(Integer endIndex) {
      this.endIndex = endIndex;
      return this;
    }

    public Builder withMetadata(Map<String, String> metadata) {
      this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
      return this;
    }

    public Citation build() {
      return new Citation(sourceId, title, content, startIndex, endIndex, metadata);
    }
  }
}
