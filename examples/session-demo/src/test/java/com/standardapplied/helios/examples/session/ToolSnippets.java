/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.tool.CommandGrant;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.AgentSession;
import com.standardapplied.helios.session.QueryEvent;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.UserMessage;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.testing.ScriptedModel;
import com.standardapplied.helios.testing.ScriptedTool;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code docs/tools-and-permissions.md} and {@code docs/security.md} blocks that build tools
 * and secret registries, one method per block, so the build compiles every one of them. A block's
 * free variables are its method's parameters. {@link QuickStartsDocsTest} holds each block to its
 * method's body; none of these methods runs.
 */
final class ToolSnippets {

  private ToolSnippets() {}

  static void weatherTool() {
    var weatherTool =
        Tool.newBuilder()
            .withName("get_weather")
            .withDescription("Current weather for a city")
            .withParameter(
                ToolParameter.newBuilder()
                    .withName("city")
                    .withType(ParameterType.STRING)
                    .withRequired(true)
                    .build())
            .withExecutor((args, ctx) -> ToolResult.success("72°F in " + args.get("city")))
            .build();
  }

  static void scriptedTool() {
    var weather = ScriptedTool.newBuilder("get_weather").withSuccess("72°F in Paris").build();
    var model =
        ScriptedModel.newBuilder()
            .withToolCallsTurn(new ToolCall("c1", "get_weather", Map.of("city", "Paris")))
            .withTextTurn("It is 72°F in Paris.")
            .build();
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withTool(
                ToolBinding.newBuilder(weather.tool()).withCategory(ToolCategory.READ).build())
            .build();

    try (var session = AgentSession.open(options);
        var events = session.events()) {
      session.runBlocking(UserMessage.text("What is the weather in Paris?"));
      var results = new ArrayList<ToolResult>();
      for (var event : events) {
        if (event instanceof QueryEvent.ToolResult result) {
          results.add(result.result());
        } else if (event instanceof QueryEvent.RunEnded) {
          break;
        }
      }
      assertEquals(List.of(Map.of("city", "Paris")), weather.calls());
      assertEquals(List.of(ToolResult.success("72°F in Paris")), results);
    }
  }

  static void commandGrant(Model model) {
    var registry = new SecretRegistry();
    var gh =
        CommandGrant.builder("gh")
            .withSecretRegistry(registry)
            .withEnv("GH_TOKEN", System.getenv("GH_TOKEN"))
            .withTimeout(Duration.ofSeconds(30))
            .withMaxOutputBytes(50_000)
            .withArgValidator(
                args ->
                    args.isEmpty() || !"auth".equals(args.get(0))
                        ? Optional.empty()
                        : Optional.of("'gh auth' is not allowed via this grant"))
            .build();

    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSecretRegistry(registry)
            .withTool(
                ToolBinding.newBuilder(gh.toTool()).withCategory(ToolCategory.EXECUTION).build())
            .build();
  }

  static void curatedCorpus(Model model, SecretRegistry registry) {
    var corpus = WorkspaceRoot.of(Path.of("/var/kb/support"));
    var options =
        SessionOptions.newBuilder()
            .withModel(model)
            .withSecretRegistry(registry)
            .withTool(ReadTool.binding(corpus))
            .withTool(GrepTool.binding(corpus))
            .withTool(GlobTool.binding(corpus))
            .withPermission(Permission.planMode())
            .build();
  }

  static void secretRegistry(Model model) {
    var registry = new SecretRegistry();
    registry.register("STRIPE_KEY", System.getenv("STRIPE_KEY"));
    registry.register("GH_TOKEN", System.getenv("GH_TOKEN"));

    var options = SessionOptions.newBuilder().withModel(model).withSecretRegistry(registry).build();
  }
}
