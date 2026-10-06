/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.gemini;

import com.standardapplied.helios.core.model.Citation;
import com.standardapplied.helios.gemini.api.OutputAnnotation;
import java.util.List;

/** The citations of Gemini output: its {@code url_citation} annotations, in document order. */
final class GeminiCitations {

  private GeminiCitations() {}

  /** The citations among {@code annotations}. */
  static List<Citation> of(List<OutputAnnotation> annotations) {
    return annotations.stream()
        .filter(annotation -> "url_citation".equals(annotation.type()))
        .map(
            annotation ->
                Citation.newBuilder()
                    .withSourceId(annotation.url())
                    .withTitle(annotation.title())
                    .withStartIndex(annotation.startIndex())
                    .withEndIndex(annotation.endIndex())
                    .build())
        .toList();
  }
}
