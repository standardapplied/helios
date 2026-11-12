/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.examples.session;

import com.standardapplied.helios.core.common.SecretRegistry;
import com.standardapplied.helios.core.model.Model;
import com.standardapplied.helios.core.tool.CommandGrant;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.SessionOptions;
import com.standardapplied.helios.session.files.GlobTool;
import com.standardapplied.helios.session.files.GrepTool;
import com.standardapplied.helios.session.files.ReadTool;
import com.standardapplied.helios.session.files.WorkspaceRoot;
import com.standardapplied.helios.session.permissions.Permission;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import java.nio.file.Path;
import java.time.Duration;
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
