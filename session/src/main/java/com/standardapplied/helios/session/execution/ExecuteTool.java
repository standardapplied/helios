/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import ai.singlr.core.common.Strings;
import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolContext;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.session.tools.ToolArgs;
import ai.singlr.session.tools.ToolBinding;
import ai.singlr.session.tools.ToolCategory;
import ai.singlr.session.tools.ToolPermissionKey;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Built-in {@code Execute} tool. Routes one execution request through the session's {@link
 * ExecutionProvider}, returning the result as both human-readable text (for the model) and the
 * structured {@link ExecutionResult} (for hooks / consumers via {@link ToolResult#data()}).
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code runtime} (required, string) — one of the {@link Runtime} constants, e.g. {@code
 *       "BASH"}, {@code "PYTHON"}. Case-insensitive.
 *   <li>{@code script} (required, string) — the script body to execute. For {@link Runtime#BASH}
 *       this is the snippet passed to {@code bash -c}; for {@link Runtime#PYTHON} the program body
 *       for {@code python3 -c}.
 *   <li>{@code args} (optional, array of strings) — positional script arguments.
 *   <li>{@code workingDirectory} (optional, string) — absolute path for the child's working
 *       directory. When absent, the provider supplies a per-call temp directory.
 *   <li>{@code timeoutSeconds} (optional, integer) — wall-clock budget for the invocation. When
 *       absent, defaults to 30 seconds. The provider may further clamp against its capabilities.
 *   <li>{@code environment} (optional, object of string→string) — env vars injected into the child.
 *       Cannot leak the JVM environment because the provider clears it before injecting.
 *   <li>{@code stdin} (optional, string) — stdin content to feed the child.
 * </ul>
 *
 * <p>Classified under {@link ToolCategory#EXECUTION}. The permission key carries {@code
 * <RUNTIME>/<first-token>} so deny rules can target specific runtime+binary pairs (e.g. {@code
 * withGlob(DENY, "Execute", "BASH/rm")}).
 *
 * <p>Spec: §11 + §8.3.
 */
public final class ExecuteTool {

  /** The stable tool name advertised to the model. */
  public static final String NAME = "Execute";

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private ExecuteTool() {}

  /**
   * Build a binding bound to the given provider.
   *
   * @param provider the execution provider; non-null
   * @return a fresh binding
   * @throws NullPointerException if {@code provider} is null
   */
  public static ToolBinding binding(ExecutionProvider provider) {
    Objects.requireNonNull(provider, "provider must not be null");
    var tool =
        Tool.newBuilder()
            .withName(NAME)
            .withDescription(
                "Executes a script in one of the configured runtimes (BASH, PYTHON, …) via the"
                    + " session's ExecutionProvider. Returns the redacted stdout, stderr, exit"
                    + " code, and a timedOut flag.")
            .withParameters(
                List.of(
                    ToolParameter.newBuilder()
                        .withName("runtime")
                        .withType(ParameterType.STRING)
                        .withDescription(
                            "Runtime dispatch target — one of BASH, PYTHON, SQL, JSHELL, R, NODE,"
                                + " or CUSTOM. Required.")
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("script")
                        .withType(ParameterType.STRING)
                        .withDescription(
                            "Script body to execute. For BASH/PYTHON this is the snippet passed"
                                + " to '-c'. Required.")
                        .withRequired(true)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("args")
                        .withType(ParameterType.ARRAY)
                        .withDescription(
                            "Positional script arguments. Interpretation is per-runtime.")
                        .withRequired(false)
                        .withItems(
                            ToolParameter.newBuilder().withType(ParameterType.STRING).build())
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("workingDirectory")
                        .withType(ParameterType.STRING)
                        .withDescription(
                            "Absolute working directory for the child. Omit for a per-call"
                                + " temp directory.")
                        .withRequired(false)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("timeoutSeconds")
                        .withType(ParameterType.INTEGER)
                        .withDescription(
                            "Wall-clock budget in seconds. Defaults to 30; the provider may"
                                + " further clamp against its capabilities.")
                        .withRequired(false)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("environment")
                        .withType(ParameterType.OBJECT)
                        .withDescription(
                            "Environment variables (string→string) injected into the child.")
                        .withRequired(false)
                        .build(),
                    ToolParameter.newBuilder()
                        .withName("stdin")
                        .withType(ParameterType.STRING)
                        .withDescription("Stdin content fed to the child.")
                        .withRequired(false)
                        .build()))
            .withExecutor((args, ctx) -> execute(provider, args, ctx))
            .build();
    return ToolBinding.newBuilder(tool)
        .withCategory(ToolCategory.EXECUTION)
        .withPermissionKeyExtractor(ExecuteTool::permissionKey)
        .build();
  }

  static ToolPermissionKey permissionKey(Map<String, Object> args) {
    var runtimeArg = ToolArgs.stringArg(args, "runtime");
    var script = ToolArgs.stringArg(args, "script");
    var runtime = Strings.isBlank(runtimeArg) ? "UNKNOWN" : runtimeArg.toUpperCase(Locale.ROOT);
    var firstToken = firstToken(script);
    var canonical = runtime + "/" + firstToken;
    return new ToolPermissionKey(NAME, canonical);
  }

  private static String firstToken(String script) {
    if (Strings.isBlank(script)) {
      return "";
    }
    var trimmed = script.strip();
    var idx = indexOfWhitespace(trimmed);
    return idx < 0 ? trimmed : trimmed.substring(0, idx);
  }

  private static int indexOfWhitespace(String s) {
    for (var i = 0; i < s.length(); i++) {
      if (Character.isWhitespace(s.charAt(i))) {
        return i;
      }
    }
    return -1;
  }

  private static ToolResult execute(
      ExecutionProvider provider, Map<String, Object> args, ToolContext ctx) {
    ctx.cancellation().throwIfCancelled();
    var runtimeArg = ToolArgs.stringArg(args, "runtime");
    if (Strings.isBlank(runtimeArg)) {
      return ToolResult.failure("Execute: missing required 'runtime' argument");
    }
    Runtime runtime;
    try {
      runtime = Runtime.valueOf(runtimeArg.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return ToolResult.failure(
          "Execute: unknown runtime '"
              + runtimeArg
              + "'. Expected one of: BASH, PYTHON, SQL, JSHELL, R, NODE, CUSTOM");
    }
    // Capability pre-check: short-circuit before building a request and crossing the provider
    // boundary when the provider has already declared the runtime as unsupported. Matches the
    // ExecutionProvider class javadoc which promises this check happens before dispatch, and
    // saves the provider an extra refusal-result construction.
    var supported = provider.capabilities().supportedRuntimes();
    if (!supported.contains(runtime)) {
      return ToolResult.failure(
          "Execute: runtime '"
              + runtime
              + "' is not supported by this provider (supported: "
              + supported
              + ")");
    }
    var script = ToolArgs.stringArg(args, "script");
    if (Strings.isBlank(script)) {
      return ToolResult.failure("Execute: missing required 'script' argument");
    }
    var positionalArgs = arrayArg(args, "args");
    if (positionalArgs == null) {
      return ToolResult.failure("Execute: 'args' must be an array of strings");
    }
    var workingDirectory = ToolArgs.stringArgOrNull(args, "workingDirectory");
    var timeoutSecondsRaw = args.get("timeoutSeconds");
    Duration timeout;
    if (timeoutSecondsRaw == null) {
      timeout = DEFAULT_TIMEOUT;
    } else {
      var seconds = ToolArgs.intArg(args, "timeoutSeconds", 0);
      if (seconds <= 0) {
        return ToolResult.failure("Execute: 'timeoutSeconds' must be a positive integer");
      }
      timeout = Duration.ofSeconds(seconds);
    }
    var environment = environmentArg(args.get("environment"));
    if (environment == null) {
      return ToolResult.failure("Execute: 'environment' must be an object of string→string");
    }
    var stdin = ToolArgs.stringArgOrNull(args, "stdin");

    var requestBuilder =
        ExecutionRequest.newBuilder()
            .withRuntime(runtime)
            .withScript(script)
            .withArgs(positionalArgs)
            .withTimeout(timeout)
            .withEnvironment(environment);
    if (workingDirectory != null && !workingDirectory.isEmpty()) {
      requestBuilder.withWorkingDirectory(Path.of(workingDirectory));
    }
    if (stdin != null) {
      requestBuilder.withStdin(stdin);
    }
    var request = requestBuilder.build();

    ExecutionResult result;
    try {
      result =
          provider
              .execute(ctx.sessionContext(), request, ctx.cancellation())
              .toCompletableFuture()
              .get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.failure("Execute: interrupted while waiting for provider");
    } catch (ExecutionException | CompletionException e) {
      var cause = e.getCause();
      if (cause instanceof CancellationException) {
        return ToolResult.failure("Execute: cancelled");
      }
      var msg =
          cause == null || cause.getMessage() == null
              ? (cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName())
              : cause.getMessage();
      return ToolResult.failure("Execute: provider failed: " + msg);
    } catch (CancellationException e) {
      return ToolResult.failure("Execute: cancelled");
    }
    return ToolResult.success(format(runtime, result), result);
  }

  private static List<String> arrayArg(Map<String, Object> args, String name) {
    var v = args.get(name);
    if (v == null) {
      return List.of();
    }
    if (!(v instanceof List<?> raw)) {
      return null;
    }
    var out = new ArrayList<String>(raw.size());
    for (var entry : raw) {
      if (!(entry instanceof String s)) {
        return null;
      }
      out.add(s);
    }
    return out;
  }

  private static Map<String, String> environmentArg(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      return null;
    }
    var out = new LinkedHashMap<String, String>(map.size());
    for (var entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
        return null;
      }
      out.put(key, value);
    }
    return out;
  }

  private static String format(Runtime runtime, ExecutionResult result) {
    var sb = new StringBuilder();
    sb.append("[runtime ").append(runtime).append(' ');
    sb.append("exit ").append(result.exitCode());
    if (result.timedOut()) {
      sb.append(" TIMEOUT");
    }
    sb.append(" duration ").append(result.duration().toMillis()).append("ms]\n");
    sb.append(result.stdout());
    if (!result.stderr().isEmpty()) {
      if (!result.stdout().isEmpty() && !result.stdout().endsWith("\n")) {
        sb.append('\n');
      }
      sb.append("[stderr]\n").append(result.stderr());
    }
    return sb.toString();
  }
}
