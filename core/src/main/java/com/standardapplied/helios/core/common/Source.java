/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.common;

import java.util.List;

/**
 * A source citation attached to a {@link FieldProvenance} entry. Names the origin of the supporting
 * evidence for one field of a {@code Provenanced} output.
 *
 * <p>Distinct from {@link ai.singlr.core.model.Citation} which carries grounding citations returned
 * by a model during {@code chat()} (e.g. Google Search results). {@code Source} is for provenance
 * the agent <em>produces</em> as part of structured output, not for citations the model returns
 * inline with text.
 *
 * <p>Excerpts are short verbatim spans the model lifted from the source. They are advisory, not
 * fetched-and-verified — callers that need verification must dereference the {@code url}
 * themselves.
 *
 * @param title human-readable title; may be {@code null} when the source has no obvious title
 * @param url canonical URL of the source; required; may be a non-HTTP URI scheme (e.g. {@code
 *     cdisc-ct://CL.AGEU/YEARS}) for non-web provenance
 * @param excerpts verbatim excerpts from the source supporting the field value; never {@code null}
 *     (use {@link List#of()} for no excerpts)
 */
public record Source(String title, String url, List<String> excerpts) {

  /**
   * Default cap on a single excerpt's length, in characters. Excerpts longer than this fail
   * validation.
   */
  public static final int DEFAULT_EXCERPT_MAX_CHARS = 2000;

  public Source {
    if (Strings.isBlank(url)) {
      throw new IllegalArgumentException("source url must not be blank");
    }
    if (excerpts != null) {
      for (var excerpt : excerpts) {
        if (excerpt == null) {
          throw new IllegalArgumentException("source excerpts must not contain null");
        }
      }
    }
    excerpts = excerpts == null ? List.of() : List.copyOf(excerpts);
  }

  /**
   * Convenience factory for a source with no title and no excerpts.
   *
   * @param url the canonical URL
   * @return a {@code Source} with only the URL set
   */
  public static Source ofUrl(String url) {
    return new Source(null, url, List.of());
  }

  /**
   * Convenience factory for a source with title, URL, and a single excerpt.
   *
   * @param title human-readable title
   * @param url the canonical URL
   * @param excerpt a single verbatim excerpt
   * @return a {@code Source} carrying all three
   */
  public static Source of(String title, String url, String excerpt) {
    return new Source(title, url, excerpt == null ? List.of() : List.of(excerpt));
  }
}
