/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.trace;

/**
 * Classifies who or what authored an {@link Annotation}.
 *
 * <p>This is a deliberately small, generic taxonomy: Helios does not model fine-grained author
 * roles. Consumers map their own author classes onto these three and keep the precise identity in
 * {@link Annotation#authorId()} (e.g. a reviewer and an end user both map to {@link #HUMAN}, an LLM
 * judge maps to {@link #MODEL}, an automated pipeline maps to {@link #SYSTEM}).
 */
public enum AuthorKind {

  /** A person authored the annotation (reviewer, end user, operator). */
  HUMAN,

  /** A model authored the annotation (e.g. an LLM-as-judge score). */
  MODEL,

  /** An automated, non-model process authored the annotation. */
  SYSTEM
}
