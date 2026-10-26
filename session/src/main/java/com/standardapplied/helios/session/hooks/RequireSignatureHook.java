/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.hooks;

import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * A {@link PostToolUseHook} and {@link PreStopHook} that keeps the session from stopping until
 * every configured {@link Signature} has matched a tool call. After each tool call it records the
 * signatures the call matched; at the stop, while any signature is unmatched, it answers with
 * {@link HookOutcome.Inject Inject} and a corrective user message listing the missing signatures'
 * {@link Signature#description() descriptions}, so the model gets another turn, within the
 * session's turn budget, to make the calls. Once every signature has matched it answers {@link
 * HookOutcome.Continue Continue}.
 *
 * <p>The observations are the session's: register one instance per session, as both hooks. They are
 * held in a concurrent set, so the instance is safe to read from any thread.
 */
public final class RequireSignatureHook implements PostToolUseHook, PreStopHook {

  /**
   * One thing the model must do before it's allowed to stop. The {@code matches} predicate returns
   * {@code true} when a tool call satisfies the signature; {@link #description()} is rendered in
   * the corrective inject message when the signature is unmet.
   *
   * @param name stable identifier the hook uses to track observations; non-blank
   * @param description human-readable instruction the model sees in the corrective inject message;
   *     non-blank
   * @param matches predicate over a {@link ToolCall}; non-null
   */
  public record Signature(String name, String description, Predicate<ToolCall> matches) {

    /**
     * Canonical constructor.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if {@code name} or {@code description} is blank
     */
    public Signature {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(description, "description must not be null");
      Objects.requireNonNull(matches, "matches must not be null");
      if (Strings.isBlank(name)) {
        throw new IllegalArgumentException("name must not be blank");
      }
      if (Strings.isBlank(description)) {
        throw new IllegalArgumentException("description must not be blank");
      }
    }

    /**
     * Convenience factory for the most common case: any call to the named tool satisfies the
     * signature.
     *
     * @param toolName the tool to require; non-blank
     * @return a signature whose predicate matches by tool name
     */
    public static Signature ofToolName(String toolName) {
      Objects.requireNonNull(toolName, "toolName must not be null");
      if (Strings.isBlank(toolName)) {
        throw new IllegalArgumentException("toolName must not be blank");
      }
      return new Signature(
          toolName,
          "Call the " + toolName + " tool at least once before stopping.",
          call -> call.name().equals(toolName));
    }
  }

  private final List<Signature> signatures;
  private final java.util.Set<String> observed = ConcurrentHashMap.newKeySet();

  private RequireSignatureHook(List<Signature> signatures) {
    this.signatures = List.copyOf(signatures);
  }

  /**
   * Convenience for the single-tool requirement: stop is refused until the model calls the named
   * tool at least once.
   *
   * @param toolName the tool to require; non-blank
   * @return a fresh hook
   * @throws NullPointerException if {@code toolName} is null
   * @throws IllegalArgumentException if {@code toolName} is blank
   */
  public static RequireSignatureHook withToolName(String toolName) {
    return new RequireSignatureHook(List.of(Signature.ofToolName(toolName)));
  }

  /**
   * Start a builder for the multi-signature case.
   *
   * @return a fresh builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * The signatures this hook tracks, in registration order.
   *
   * @return immutable list
   */
  public List<Signature> signatures() {
    return signatures;
  }

  /**
   * The signatures observed so far in this session. Useful for tests and diagnostics.
   *
   * @return immutable snapshot
   */
  public java.util.Set<String> observed() {
    return java.util.Set.copyOf(observed);
  }

  @Override
  public HookOutcome.PostToolUse afterTool(ToolCall call, ToolResult result, HookContext ctx) {
    Objects.requireNonNull(call, "call must not be null");
    for (var sig : signatures) {
      if (observed.contains(sig.name())) {
        continue;
      }
      if (sig.matches().test(call)) {
        observed.add(sig.name());
      }
    }
    return HookOutcome.cont();
  }

  @Override
  public String name() {
    return "RequireSignatureHook";
  }

  @Override
  public HookOutcome.PreStop beforeStop(Response<?> stopResponse, HookContext ctx) {
    var unmet = new ArrayList<Signature>(signatures.size());
    for (var sig : signatures) {
      if (!observed.contains(sig.name())) {
        unmet.add(sig);
      }
    }
    if (unmet.isEmpty()) {
      return HookOutcome.cont();
    }
    return HookOutcome.inject(buildCorrection(unmet));
  }

  private static String buildCorrection(List<Signature> unmet) {
    var sb = new StringBuilder("Cannot stop yet — these required steps haven't happened:");
    for (var sig : unmet) {
      sb.append("\n  - ").append(sig.description());
    }
    sb.append("\nPerform the missing step(s) and then stop.");
    return sb.toString();
  }

  /**
   * Fluent builder for the multi-signature case. Construct via {@link
   * RequireSignatureHook#newBuilder()}.
   */
  public static final class Builder {

    private final ArrayList<Signature> signatures = new ArrayList<>();

    private Builder() {}

    /**
     * Append a signature.
     *
     * @param signature the signature to require; non-null
     * @return this builder
     * @throws NullPointerException if {@code signature} is null
     */
    public Builder withSignature(Signature signature) {
      Objects.requireNonNull(signature, "signature must not be null");
      signatures.add(signature);
      return this;
    }

    /**
     * Append a tool-name requirement.
     *
     * @param toolName the tool to require; non-blank
     * @return this builder
     */
    public Builder withToolName(String toolName) {
      return withSignature(Signature.ofToolName(toolName));
    }

    /**
     * Append a custom predicate requirement.
     *
     * @param name stable identifier
     * @param description human-readable instruction shown to the model on unmet
     * @param matches predicate over a tool call
     * @return this builder
     */
    public Builder withSignature(String name, String description, Predicate<ToolCall> matches) {
      return withSignature(new Signature(name, description, matches));
    }

    /**
     * Build the immutable hook.
     *
     * @return a fresh hook
     * @throws IllegalStateException if no signatures were added
     */
    public RequireSignatureHook build() {
      if (signatures.isEmpty()) {
        throw new IllegalStateException("at least one signature is required");
      }
      return new RequireSignatureHook(signatures);
    }
  }
}
