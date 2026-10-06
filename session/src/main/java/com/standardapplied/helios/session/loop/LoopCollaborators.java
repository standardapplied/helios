/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SteeringQueue;
import com.standardapplied.helios.session.hooks.HookContext;
import com.standardapplied.helios.session.hooks.HookRegistry;
import java.time.Clock;
import java.time.InstantSource;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The per-session collaborators the {@link AgentLoop} and its {@link TurnRunner} share: built once
 * by the session and handed to both, so each constructor names only the collaborators it alone
 * uses.
 *
 * @param hooks priority-sorted hook registry; non-null
 * @param toolDispatch dispatcher for the tool calls the model emits; non-null
 * @param steeringQueue per-session inbox the loop drains and into which {@code Inject} outcomes
 *     queue synthetic user messages; non-null
 * @param eventSink consumer for every {@link QueryEvent} the loop emits; non-null
 * @param hookContextFactory builds a per-fire {@link HookContext} from the session state; non-null
 * @param clock supplies event timestamps; non-null
 */
public record LoopCollaborators(
    HookRegistry hooks,
    ToolDispatch toolDispatch,
    SteeringQueue steeringQueue,
    Consumer<QueryEvent> eventSink,
    Function<SessionState, HookContext> hookContextFactory,
    InstantSource clock) {

  /**
   * Canonical constructor.
   *
   * @throws NullPointerException if any component is null
   */
  public LoopCollaborators {
    Objects.requireNonNull(hooks, "hooks must not be null");
    Objects.requireNonNull(toolDispatch, "toolDispatch must not be null");
    Objects.requireNonNull(steeringQueue, "steeringQueue must not be null");
    Objects.requireNonNull(eventSink, "eventSink must not be null");
    Objects.requireNonNull(hookContextFactory, "hookContextFactory must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * Start a builder.
   *
   * @return a fresh builder whose clock defaults to {@link Clock#systemUTC()}
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  HookContext hookContext(SessionState state) {
    return hookContextFactory.apply(state);
  }

  EventEmitter emitter() {
    return new EventEmitter(eventSink, hooks, hookContextFactory, clock);
  }

  /** Builder for {@link LoopCollaborators}; every component but the clock is required. */
  public static final class Builder {

    private HookRegistry hooks;
    private ToolDispatch toolDispatch;
    private SteeringQueue steeringQueue;
    private Consumer<QueryEvent> eventSink;
    private Function<SessionState, HookContext> hookContextFactory;
    private InstantSource clock = Clock.systemUTC();

    private Builder() {}

    /**
     * Set the hook registry.
     *
     * @param hooks non-null
     * @return this builder
     */
    public Builder withHooks(HookRegistry hooks) {
      this.hooks = hooks;
      return this;
    }

    /**
     * Set the tool dispatcher.
     *
     * @param toolDispatch non-null
     * @return this builder
     */
    public Builder withToolDispatch(ToolDispatch toolDispatch) {
      this.toolDispatch = toolDispatch;
      return this;
    }

    /**
     * Set the steering queue.
     *
     * @param steeringQueue non-null
     * @return this builder
     */
    public Builder withSteeringQueue(SteeringQueue steeringQueue) {
      this.steeringQueue = steeringQueue;
      return this;
    }

    /**
     * Set the event sink.
     *
     * @param eventSink non-null
     * @return this builder
     */
    public Builder withEventSink(Consumer<QueryEvent> eventSink) {
      this.eventSink = eventSink;
      return this;
    }

    /**
     * Set the hook-context factory.
     *
     * @param hookContextFactory non-null
     * @return this builder
     */
    public Builder withHookContextFactory(Function<SessionState, HookContext> hookContextFactory) {
      this.hookContextFactory = hookContextFactory;
      return this;
    }

    /**
     * Set the clock supplying event timestamps.
     *
     * @param clock non-null
     * @return this builder
     */
    public Builder withClock(InstantSource clock) {
      this.clock = clock;
      return this;
    }

    /**
     * Build the collaborators.
     *
     * @return the collaborators
     * @throws NullPointerException if a required component was not set
     */
    public LoopCollaborators build() {
      return new LoopCollaborators(
          hooks, toolDispatch, steeringQueue, eventSink, hookContextFactory, clock);
    }
  }
}
