/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.core.runtime;

import ai.singlr.core.common.Ids;
import ai.singlr.core.common.Result;
import ai.singlr.core.common.Strings;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodic sweep over a {@link RunStore} that resumes stale {@link AgentRunStatus#RUNNING} or
 * {@link AgentRunStatus#SUSPENDED} runs. The intended use is wiring a single-sweep {@link #scan()}
 * call into a scheduler — Helidon's {@code io.helidon.scheduling}, plain {@code
 * ScheduledExecutorService}, or the cron skill of your choice.
 *
 * <pre>{@code
 * var scanner = DurableResumeScanner.builder(durability)
 *     .registerAgent("research-bot", researchAgent)
 *     .registerWorkflow("ingest-pipeline", ingestWorkflow)
 *     .withStaleAfter(Duration.ofMinutes(5))
 *     .withMaxConcurrent(4)
 *     .build();
 *
 * // Run once per minute via Helidon scheduling, ScheduledExecutorService, etc:
 * scheduler.scheduleAtFixedRate(() -> scanner.scan(), 0, 1, TimeUnit.MINUTES);
 * }</pre>
 *
 * <h2>Staleness</h2>
 *
 * <p>"Stale" means the run's {@code lastCheckpointAt} is older than {@code now - staleAfter}. Runs
 * that have been checkpointed recently are presumed in-flight; auto-resume could double-run them.
 * Pick a {@code staleAfter} larger than the longest expected step time.
 *
 * <h2>Concurrency</h2>
 *
 * <p>The scanner enforces a per-sweep maximum-concurrent-resumes via a {@link Semaphore}; runs over
 * the cap wait their turn. Each resume runs on a virtual thread.
 */
public class DurableResumeScanner {

  private static final Logger LOG = Logger.getLogger(DurableResumeScanner.class.getName());

  private final Durability durability;
  private final Map<String, Function<UUID, Result<?>>> resolvers;
  private final Duration staleAfter;
  private final int maxConcurrent;

  private DurableResumeScanner(
      Durability durability,
      Map<String, Function<UUID, Result<?>>> resolvers,
      Duration staleAfter,
      int maxConcurrent) {
    this.durability = durability;
    this.resolvers = Map.copyOf(resolvers);
    this.staleAfter = staleAfter;
    this.maxConcurrent = maxConcurrent;
  }

  /**
   * Result of a single sweep.
   *
   * @param scanned total non-terminal runs inspected
   * @param resumed runs that were actually resumed (passed the stale + agentId checks)
   * @param recovered resumed runs whose result was {@link Result.Success}
   * @param failed resumed runs whose result was {@link Result.Failure}
   * @param skippedFresh runs skipped because they were checkpointed within {@code staleAfter}
   * @param skippedUnknownAgent runs skipped because no resolver matched the run's {@code agentId}
   */
  public record ScanResult(
      int scanned,
      int resumed,
      int recovered,
      int failed,
      int skippedFresh,
      int skippedUnknownAgent) {}

  /** Run a single sweep. Inspects RUNNING runs first, then SUSPENDED. */
  public ScanResult scan() {
    var scanned = new AtomicInteger();
    var resumed = new AtomicInteger();
    var recovered = new AtomicInteger();
    var failed = new AtomicInteger();
    var skippedFresh = new AtomicInteger();
    var skippedUnknownAgent = new AtomicInteger();

    var semaphore = new Semaphore(maxConcurrent);
    var cutoff = Ids.now().minus(staleAfter);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (var status : new AgentRunStatus[] {AgentRunStatus.RUNNING, AgentRunStatus.SUSPENDED}) {
        for (var run : durability.runStore().findByStatus(status)) {
          scanned.incrementAndGet();
          if (run.lastCheckpointAt() != null && run.lastCheckpointAt().isAfter(cutoff)) {
            skippedFresh.incrementAndGet();
            continue;
          }
          var resolver = resolvers.get(run.agentId());
          if (resolver == null) {
            skippedUnknownAgent.incrementAndGet();
            LOG.log(
                Level.FINE,
                () ->
                    "DurableResumeScanner: no resolver registered for agentId='"
                        + run.agentId()
                        + "', skipping run "
                        + run.runId());
            continue;
          }
          executor.submit(
              () -> {
                try {
                  semaphore.acquire();
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  return;
                }
                try {
                  resumed.incrementAndGet();
                  var result = resolver.apply(run.runId());
                  if (result.isSuccess()) {
                    recovered.incrementAndGet();
                  } else {
                    failed.incrementAndGet();
                  }
                } catch (RuntimeException e) {
                  failed.incrementAndGet();
                  LOG.log(
                      Level.WARNING,
                      "DurableResumeScanner: resume threw for run " + run.runId(),
                      e);
                } finally {
                  semaphore.release();
                }
              });
        }
      }
    }
    return new ScanResult(
        scanned.get(),
        resumed.get(),
        recovered.get(),
        failed.get(),
        skippedFresh.get(),
        skippedUnknownAgent.get());
  }

  public static Builder builder(Durability durability) {
    return new Builder(durability);
  }

  public static class Builder {
    private final Durability durability;
    private final Map<String, Function<UUID, Result<?>>> resolvers = new HashMap<>();
    private Duration staleAfter = Duration.ofMinutes(5);
    private int maxConcurrent = 4;

    private Builder(Durability durability) {
      this.durability = Objects.requireNonNull(durability, "durability");
    }

    /**
     * Register a custom resume resolver for runs with the given {@code agentId}. The resolver
     * receives the runId and returns a {@link Result}.
     */
    public Builder register(String agentId, Function<UUID, Result<?>> resolver) {
      if (Strings.isBlank(agentId)) {
        throw new IllegalArgumentException("agentId must not be blank");
      }
      Objects.requireNonNull(resolver, "resolver");
      resolvers.put(agentId, resolver);
      return this;
    }

    /**
     * Convenience: register an {@code Agent}-shaped resumable. The {@code agentId} must match the
     * agent's {@code config.name()}; otherwise scanned runs won't route to this resolver.
     */
    public Builder registerAgent(String agentId, AgentResumable agent) {
      Objects.requireNonNull(agent, "agent");
      return register(agentId, agent::resume);
    }

    /**
     * Convenience: register a workflow-shaped resumable. The runs are correlated by {@code
     * "workflow." + workflowName} — workflows that journal under this naming convention surface
     * here on resume.
     */
    public Builder registerWorkflow(String workflowName, WorkflowResumable workflow) {
      Objects.requireNonNull(workflow, "workflow");
      return register("workflow." + workflowName, workflow::resume);
    }

    /**
     * Skip runs whose {@code lastCheckpointAt} is more recent than {@code now - staleAfter}. This
     * prevents the scanner from racing an agent that's actively iterating. Default 5 minutes.
     */
    public Builder withStaleAfter(Duration staleAfter) {
      Objects.requireNonNull(staleAfter, "staleAfter");
      if (staleAfter.isNegative()) {
        throw new IllegalArgumentException("staleAfter must be non-negative");
      }
      this.staleAfter = staleAfter;
      return this;
    }

    /** Maximum number of concurrent resumes per sweep. Defaults to 4. */
    public Builder withMaxConcurrent(int maxConcurrent) {
      if (maxConcurrent < 1) {
        throw new IllegalArgumentException("maxConcurrent must be >= 1");
      }
      this.maxConcurrent = maxConcurrent;
      return this;
    }

    public DurableResumeScanner build() {
      if (resolvers.isEmpty()) {
        throw new IllegalStateException(
            "At least one resumable must be registered (register / registerAgent / registerWorkflow)");
      }
      return new DurableResumeScanner(durability, resolvers, staleAfter, maxConcurrent);
    }
  }

  /**
   * Functional interface for an agent-shaped resumable: a function from {@link UUID runId} to a
   * {@link Result}. Sessions and other top-level execution shapes implement this so the scanner can
   * drive them on resume without taking a dependency on their concrete type.
   */
  @FunctionalInterface
  public interface AgentResumable {
    Result<?> resume(UUID runId);
  }

  /**
   * Functional interface for a workflow-shaped resumable; same shape as {@link AgentResumable},
   * distinct type for naming clarity at the registration call site.
   */
  @FunctionalInterface
  public interface WorkflowResumable {
    Result<?> resume(UUID runId);
  }
}
