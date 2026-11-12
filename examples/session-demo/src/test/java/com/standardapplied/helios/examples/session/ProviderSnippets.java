/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.anthropic.AnthropicModelId;
import com.standardapplied.helios.anthropic.AnthropicPricing;
import com.standardapplied.helios.anthropic.AnthropicProvider;
import com.standardapplied.helios.anthropic.CachePolicy;
import com.standardapplied.helios.core.common.Provenanced;
import com.standardapplied.helios.core.fault.Backoff;
import com.standardapplied.helios.core.fault.CircuitBreaker;
import com.standardapplied.helios.core.fault.FaultTolerance;
import com.standardapplied.helios.core.fault.RetryPolicy;
import com.standardapplied.helios.core.model.Message;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ModelChunk;
import com.standardapplied.helios.core.model.ModelConfig;
import com.standardapplied.helios.core.model.Reasoning;
import com.standardapplied.helios.core.model.Role;
import com.standardapplied.helios.core.schema.OutputSchema;
import com.standardapplied.helios.core.schema.RawOutputCapturePolicy;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.gemini.GeminiFilesClient;
import com.standardapplied.helios.gemini.GeminiModelId;
import com.standardapplied.helios.gemini.GeminiProvider;
import com.standardapplied.helios.session.SessionOptions;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The README and {@code docs/providers.md} blocks that use a {@code Model} directly, one method per
 * block, so the build compiles every one of them. A block's free variables are its method's
 * parameters. {@link QuickStartsDocsTest} holds each block to its method's body; none of these
 * methods runs.
 */
final class ProviderSnippets {

  /** The typed output {@link #provenanced} asks for. */
  record MappingProposal(String sourceField, String targetField) {}

  private ProviderSnippets() {}

  static void modelCall() {
    try (var model =
        new AnthropicProvider()
            .create(
                AnthropicModelId.CLAUDE_SONNET_5_5.id(),
                ModelConfig.newBuilder().withApiKey(System.getenv("ANTHROPIC_API_KEY")).build())) {
      var response =
          model.chat(
              List.of(
                  Message.system("You are a helpful assistant."),
                  Message.user("What is the capital of France?")),
              List.of());
      System.out.println(response.content());
    }
  }

  static void reasoning() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("ANTHROPIC_API_KEY"))
            .withReasoning(new Reasoning.Effort(Reasoning.Level.MEDIUM, Reasoning.Display.PROGRESS))
            .build();
    var model = new AnthropicProvider().create(AnthropicModelId.CLAUDE_OPUS_5_5.id(), config);
  }

  static void geminiVideo() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("GEMINI_API_KEY"))
            .withApiVersion("v1beta")
            .build();

    try (var files = new GeminiFilesClient(config);
        var video = files.upload(Path.of("meeting.mp4"), "video/mp4");
        var model = new GeminiProvider().create(GeminiModelId.GEMINI_3_8_FLASH.id(), config)) {
      var message =
          Message.newBuilder()
              .withRole(Role.USER)
              .withContent("Summarize this video and list the decisions.")
              .withFileReferences(List.of(video.awaitActive()))
              .build();

      System.out.println(model.chat(List.of(message), List.of()).content());
    }
  }

  static void geminiPrivacy() {
    var config =
        ModelConfig.newBuilder()
            .withApiKey(System.getenv("GEMINI_API_KEY"))
            .withApiVersion("v1beta")
            .withProviderContinuation(false)
            .withRawOutputCapture(RawOutputCapturePolicy.DISABLED)
            .build();

    try (var files = new GeminiFilesClient(config);
        var media = files.upload(Path.of("private-video.mp4"), "video/mp4");
        var model = new GeminiProvider().create(GeminiModelId.GEMINI_3_8_FLASH.id(), config)) {
      var input =
          Message.newBuilder()
              .withRole(Role.USER)
              .withContent("Analyze the video.")
              .withFileReferences(List.of(media.awaitActive()))
              .build();
      var response = model.chat(List.of(input), List.of());
    }
  }

  static void cachePolicies() {
    var provider = new AnthropicProvider();
    var config = ModelConfig.of(System.getenv("ANTHROPIC_API_KEY"));

    Model longLived =
        provider.create(AnthropicModelId.CLAUDE_OPUS_5.id(), config, CachePolicy.longLived());

    Model uncached =
        provider.create(AnthropicModelId.CLAUDE_OPUS_5.id(), config, CachePolicy.disabled());
  }

  static void listPrices(Model longLived) {
    var options =
        SessionOptions.newBuilder()
            .withModel(longLived)
            .withCostCalculator(AnthropicPricing.calculator(CachePolicy.longLived()))
            .build();
  }

  static void structuredOutput(Model model, List<Message> messages) {
    record Sentiment(String label, double confidence) {}

    var response = model.chat(messages, List.of(), OutputSchema.of(Sentiment.class));
    Sentiment s = response.parsed();
  }

  static void provenanced(Model model, List<Message> messages) {
    var schema = OutputSchema.provenancedOf(MappingProposal.class);
    var response = model.chat(messages, List.of(), schema);
    Provenanced<MappingProposal> result = response.parsed();
  }

  static void streaming(Model model, List<Message> messages, List<Tool> tools) {
    try (var stream = model.chatStream(messages, tools)) {
      while (stream.hasNext()) {
        switch (stream.next()) {
          case ModelChunk.TextDelta d -> System.out.print(d.text());
          case ModelChunk.ToolUseStop stop ->
              System.out.println("Called: " + stop.toolCall().name());
          case ModelChunk.MessageStop stop -> System.out.println("\n" + stop.finishReason());
          default -> {}
        }
      }
    }
  }

  static void faultTolerance(Model model, List<Message> messages) throws Exception {
    var ft =
        FaultTolerance.newBuilder()
            .withRetry(
                RetryPolicy.newBuilder()
                    .withMaxAttempts(3)
                    .withBackoff(Backoff.exponential(Duration.ofMillis(500), 2.0))
                    .build())
            .withCircuitBreaker(
                CircuitBreaker.newBuilder()
                    .withFailureThreshold(5)
                    .withHalfOpenAfter(Duration.ofSeconds(30))
                    .build())
            .withOperationTimeout(Duration.ofMinutes(5))
            .build();

    var result = ft.execute(() -> model.chat(messages, List.of()));
  }
}
