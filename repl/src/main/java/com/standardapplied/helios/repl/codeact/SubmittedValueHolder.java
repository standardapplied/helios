/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.repl.codeact;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe single-shot holder for the typed value submitted via {@link SubmitTool}.
 *
 * <p>Set-once semantics: the first successful {@link #submit(Object)} stores the value; subsequent
 * attempts return {@code false} and leave the stored value untouched. The {@link SubmitTool}'s
 * executor calls {@link #submit(Object)} after validation passes and turns a {@code false} return
 * into a tool-result failure so the model sees "submit already accepted" instead of overwriting the
 * earlier answer.
 *
 * <p>Reading via {@link #peek()} is lock-free and never blocks; the field is an {@link
 * AtomicReference} so cross-thread visibility holds even when the submit happens on the agent
 * loop's virtual thread and the read happens on the caller's wait thread.
 *
 * <p>This holder is intentionally untyped ({@code Object}) — the typed type lives in the {@link
 * ai.singlr.core.schema.OutputSchema} the {@code SubmitTool} was constructed with, and the
 * downstream consumer ({@code runBlocking} integration in PR 3) carries that type parameter.
 */
public final class SubmittedValueHolder {

  private final AtomicReference<Object> ref = new AtomicReference<>();

  /**
   * Try to store {@code value} as this holder's submitted value.
   *
   * @param value the typed value to store; non-null
   * @return {@code true} if this is the first submit and the value was stored; {@code false} if a
   *     prior submit already won
   * @throws NullPointerException if {@code value} is null
   */
  public boolean submit(Object value) {
    Objects.requireNonNull(value, "value must not be null");
    return ref.compareAndSet(null, value);
  }

  /**
   * Read the stored value, or empty if no submit has succeeded yet.
   *
   * @return the stored value, or {@link Optional#empty()}
   */
  public Optional<Object> peek() {
    return Optional.ofNullable(ref.get());
  }

  /**
   * Whether a submit has succeeded against this holder.
   *
   * @return {@code true} if a value is stored
   */
  public boolean isSubmitted() {
    return ref.get() != null;
  }
}
