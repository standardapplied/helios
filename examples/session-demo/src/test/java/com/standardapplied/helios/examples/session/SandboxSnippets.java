/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.core.embedding.EmbeddingConfig;
import com.standardapplied.helios.core.embedding.EmbeddingModel;
import com.standardapplied.helios.core.embedding.EmbeddingProvider;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.onnx.OnnxModelId;
import com.standardapplied.helios.repl.ReplConfig;
import com.standardapplied.helios.repl.execution.JShellExecutionProvider;
import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.host.HostParameter;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.permissions.Permission;
import java.util.List;

/**
 * The {@code docs/sandbox-and-embeddings.md} blocks, one method per block, so the build compiles
 * every one of them. A block's free variables are its method's parameters. {@link
 * QuickStartsDocsTest} holds each block to its method's body; none of these methods runs.
 */
final class SandboxSnippets {

  /** The price feed {@link #hostFunction} reads. */
  interface Prices {

    double latest(String ticker);
  }

  private SandboxSnippets() {}

  static void hostFunction(Model model, Prices prices) {
    var quote =
        new HostFunction(
            "marketQuote",
            "Current price of a ticker",
            List.of(HostParameter.required("ticker", ParameterType.STRING, "Ticker symbol")),
            params -> prices.latest((String) params.get("ticker")));

    try (var executionProvider =
            JShellExecutionProvider.create(
                ReplConfig.newBuilder().withHostFunction(quote).build());
        var session =
            AgentSession.open(
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withExecutionProvider(executionProvider)
                    .withPermission(Permission.lockedDown())
                    .build())) {
      session.runBlocking(UserMessage.text("What is AAPL trading at?"));
    }
  }

  static void embeddings() {
    try (var model =
        EmbeddingProvider.resolve(OnnxModelId.NOMIC_EMBED_V1_5.id(), EmbeddingConfig.defaults())) {
      float[] vector = model.embed("A man is eating food.").getOrThrow();
    }
  }

  static void embeddingPrefixes(EmbeddingModel model, String query, String doc) {
    model.embedQuery(
        query, "Instruct: Given a brief professional summary, find the matching profile\nQuery: ");
    model.embedDocument(doc, null);
  }
}
