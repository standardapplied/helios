/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import ai.singlr.core.common.Strings;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration bundle for crash-safe agent execution. Single source of truth — wire one of these
 * into a durable loop and it checkpoints iterations, journals tool calls, and supports {@code
 * resume(runId)} after a JVM crash.
 *
 * <h2>One-line setup</h2>
 *
 * <pre>{@code
 * // Tests / single-process:
 * .withDurability(Durability.inMemory())
 *
 * // Production (Postgres impls live in helios-persistence):
 * .withDurability(PgDurability.of(pgConfig))
 * }</pre>
 *
 * <h2>Custom configuration</h2>
 *
 * <pre>{@code
 * .withDurability(Durability.newBuilder()
 *     .withRunStore(myStore)
 *     .withToolCallJournal(myJournal)
 *     .withUnsafeResumePolicy(UnsafeResumePolicy.AUTO_FAIL_AND_CONTINUE)
 *     .withIdempotentToolOverride("send_email", true)
 *     .build())
 * }</pre>
 *
 * @param runStore durable storage for {@link AgentRun} checkpoints
 * @param toolCallJournal durable journal of tool invocations within a run
 * @param unsafeResumePolicy how {@code resume(...)} handles a non-idempotent tool that was
 *     in-flight at crash; defaults to {@link UnsafeResumePolicy#FAIL_LOUD}
 * @param idempotentToolsOverride deployer-side override for the tool's own {@code idempotent} flag,
 *     keyed by tool name. Lets operators correct an author's classification without rebuilding the
 *     tool. The override wins when present
 * @param checkpointFrequency how often to write a {@code RUNNING} checkpoint during the agent loop.
 *     {@code 1} (default) means every iteration — the safest choice, recovery loses at most one
 *     in-flight iteration on a crash. Larger values cut DB round-trips proportionally at the cost
 *     of more replay on crash. Terminal states ({@code COMPLETED}, {@code FAILED}, initialization)
 *     are written unconditionally regardless of this knob
 */
public record Durability(
    RunStore runStore,
    ToolCallJournal toolCallJournal,
    UnsafeResumePolicy unsafeResumePolicy,
    Map<String, Boolean> idempotentToolsOverride,
    int checkpointFrequency) {

  public Durability {
    Objects.requireNonNull(runStore, "runStore");
    Objects.requireNonNull(toolCallJournal, "toolCallJournal");
    Objects.requireNonNull(unsafeResumePolicy, "unsafeResumePolicy");
    Objects.requireNonNull(idempotentToolsOverride, "idempotentToolsOverride");
    if (checkpointFrequency < 1) {
      throw new IllegalArgumentException(
          "checkpointFrequency must be >= 1 (got " + checkpointFrequency + ")");
    }
    idempotentToolsOverride = Map.copyOf(idempotentToolsOverride);
  }

  /**
   * Default-policy bundle with no idempotency overrides. Equivalent to {@code
   * newBuilder().withRunStore(s).withToolCallJournal(j).build()}.
   */
  public static Durability of(RunStore runStore, ToolCallJournal toolCallJournal) {
    return new Durability(runStore, toolCallJournal, UnsafeResumePolicy.FAIL_LOUD, Map.of(), 1);
  }

  /**
   * Fresh in-memory bundle suitable for tests and single-process deployments without crash-recovery
   * requirements. Each call returns new {@link InMemoryRunStore} and {@link
   * InMemoryToolCallJournal} instances so test isolation is preserved.
   */
  public static Durability inMemory() {
    return of(new InMemoryRunStore(), new InMemoryToolCallJournal());
  }

  /**
   * Returns the deployer-supplied idempotency override for the named tool, or {@code null} when no
   * override is configured. Callers should fall back to {@link
   * ai.singlr.core.tool.Tool#idempotent()} when this returns null.
   */
  public Boolean idempotentOverride(String toolName) {
    return toolName == null ? null : idempotentToolsOverride.get(toolName);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(Durability durability) {
    return new Builder(durability);
  }

  /** Fluent builder for {@link Durability}. */
  public static class Builder {
    private RunStore runStore;
    private ToolCallJournal toolCallJournal;
    private UnsafeResumePolicy unsafeResumePolicy = UnsafeResumePolicy.FAIL_LOUD;
    private Map<String, Boolean> idempotentToolsOverride = new HashMap<>();
    private int checkpointFrequency = 1;

    private Builder() {}

    private Builder(Durability d) {
      this.runStore = d.runStore;
      this.toolCallJournal = d.toolCallJournal;
      this.unsafeResumePolicy = d.unsafeResumePolicy;
      this.idempotentToolsOverride = new HashMap<>(d.idempotentToolsOverride);
      this.checkpointFrequency = d.checkpointFrequency;
    }

    public Builder withRunStore(RunStore runStore) {
      this.runStore = runStore;
      return this;
    }

    public Builder withToolCallJournal(ToolCallJournal toolCallJournal) {
      this.toolCallJournal = toolCallJournal;
      return this;
    }

    /**
     * Configure the resume policy for non-idempotent in-flight tool calls.
     *
     * @throws NullPointerException if {@code policy} is null
     */
    public Builder withUnsafeResumePolicy(UnsafeResumePolicy policy) {
      this.unsafeResumePolicy = Objects.requireNonNull(policy, "policy");
      return this;
    }

    /**
     * Override the idempotency flag for a named tool. Wins over the tool's own {@code idempotent}
     * default — the deployer-side escape hatch when the author got it wrong.
     *
     * @throws IllegalArgumentException if {@code toolName} is null or blank
     */
    public Builder withIdempotentToolOverride(String toolName, boolean idempotent) {
      if (Strings.isBlank(toolName)) {
        throw new IllegalArgumentException("toolName must not be blank");
      }
      this.idempotentToolsOverride.put(toolName, idempotent);
      return this;
    }

    /**
     * Replace the entire idempotency override map. Useful when populating from configuration.
     *
     * @throws NullPointerException if {@code overrides} is null; pass {@code Map.of()} to clear
     */
    public Builder withIdempotentToolsOverride(Map<String, Boolean> overrides) {
      Objects.requireNonNull(overrides, "overrides; pass Map.of() to clear");
      for (var entry : overrides.entrySet()) {
        if (Strings.isBlank(entry.getKey())) {
          throw new IllegalArgumentException("overrides keys must not be blank");
        }
        if (entry.getValue() == null) {
          throw new IllegalArgumentException(
              "overrides values must not be null (key: " + entry.getKey() + ")");
        }
      }
      this.idempotentToolsOverride = new HashMap<>(overrides);
      return this;
    }

    /**
     * Write a {@code RUNNING} checkpoint every {@code n} iterations. Default {@code 1} — every
     * iteration. Larger values trade replay-on-crash for fewer DB round-trips during long runs.
     *
     * @throws IllegalArgumentException if {@code n &lt; 1}
     */
    public Builder withCheckpointFrequency(int n) {
      if (n < 1) {
        throw new IllegalArgumentException("checkpointFrequency must be >= 1 (got " + n + ")");
      }
      this.checkpointFrequency = n;
      return this;
    }

    public Durability build() {
      if (runStore == null) {
        throw new IllegalStateException("runStore is required");
      }
      if (toolCallJournal == null) {
        throw new IllegalStateException("toolCallJournal is required");
      }
      return new Durability(
          runStore,
          toolCallJournal,
          unsafeResumePolicy,
          idempotentToolsOverride,
          checkpointFrequency);
    }
  }
}
