/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.session.loop;

import com.standardapplied.helios.core.model.FinishReason;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.Response;
import com.standardapplied.helios.core.model.Response.Usage;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.testing.ScriptedModel;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Scripted models for loop tests: one-reply scripts, and a script that reaches into the loop on
 * each call (steering a message in mid-turn, say) or reports a context window.
 */
final class LoopModels {

  private LoopModels() {}

  /** A response of {@code content} that ends for {@code reason}, reporting {@code usage}. */
  static Response<Void> response(String content, FinishReason reason, Usage usage) {
    return Response.newBuilder()
        .withContent(content)
        .withFinishReason(reason)
        .withUsage(usage)
        .build();
  }

  /** A model scripted to answer one call with {@link #response}. */
  static ScriptedModel answering(String content, FinishReason reason, Usage usage) {
    return ScriptedModel.newBuilder().withResponseTurn(response(content, reason, usage)).build();
  }

  /**
   * A model that answers like {@code script}, first running {@code onCall} with the call's number,
   * counted from 1, on the calling thread.
   */
  static Model onEachCall(ScriptedModel script, IntConsumer onCall) {
    return new Observed(script, onCall, 0);
  }

  /** A model that answers like {@code script} and reports a context window of {@code tokens}. */
  static Model withContextWindow(ScriptedModel script, int tokens) {
    return new Observed(script, call -> {}, tokens);
  }

  private record Observed(ScriptedModel script, IntConsumer onCall, int contextWindow)
      implements Model {

    @Override
    public Response<Void> chat(List<Message> messages, List<Tool> tools) {
      onCall.accept(script.calls().size() + 1);
      return script.chat(messages, tools);
    }

    @Override
    public String id() {
      return script.id();
    }

    @Override
    public String provider() {
      return script.provider();
    }
  }
}
