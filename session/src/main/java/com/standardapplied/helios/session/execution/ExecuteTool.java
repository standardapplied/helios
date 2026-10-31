/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.execution;

import com.standardapplied.helios.core.common.Result;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.core.tool.Tool;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolParameter;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.tools.ToolArgs;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;

/**
 * Built-in {@code Execute} tool. Routes one execution request through the session's {@link
 * ExecutionProvider} and returns the {@link ExecutionResult} as text: runtime, exit code, timeout
 * flag, duration, stdout and stderr. The session redacts that text like any other tool output.
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
 *       absent, defaults to the tool call's deadline ({@code SessionLimits.toolTimeoutDefault()}).
 *       The provider may further clamp it to its maximum.
 *   <li>{@code environment} (optional, object of string→string) — env vars injected into the child.
 *       Cannot leak the JVM environment because the provider clears it before injecting.
 *   <li>{@code stdin} (optional, string) — stdin content to feed the child.
 * </ul>
 *
 * <p>Classified under {@link ToolCategory#EXECUTION}. The permission key is {@code
 * <RUNTIME>/<script>}: the {@link Runtime} constant the {@code runtime} argument names ({@code
 * UNKNOWN} when it names none, a call refused when it runs) and the whole script, stripped, so a
 * rule's glob matches the script text, every line of it. After it comes one line {@code +<name>}
 * for each of {@code args}, {@code environment}, {@code stdin} and {@code workingDirectory} the
 * call sets to anything but null, in that order, by name only: {@code ls} with {@code stdin} and
 * {@code environment} is keyed {@code BASH/ls\n+environment\n+stdin}, so a value never enters the
 * key, and an empty value is still named. An exact allow rule, such as {@code BASH/ls}, admits only
 * the bare call; {@code BASH/make\n+workingDirectory}, its {@code \n} a real line break, allows
 * that extra on purpose. An exact deny or ask rule stops at the bare call too, so one that must
 * hold whatever a call sets ends in {@code **}. A script may end in a line that reads like an
 * extra; it then matches such a rule but runs in the default directory, with that line as one more
 * command, which gives it less than the rule allows. A deny glob is a guard rail: {@code BASH/**rm
 * **} misses {@code rm<TAB>-rf} and {@code r''m}, and matches {@code echo farm }. A wildcard allow
 * rule on {@code Execute} is not a sandbox: {@code *} matches {@code ;} and the extras lines. Real
 * control is {@code ASK}, whose prompt shows every argument. A script that carries a registered
 * secret's value is refused before any rule or approver sees its key.
 *
 * <p>Spec: §11 + §8.3.
 */
public final class ExecuteTool {

  /** The stable tool name advertised to the model. */
  public static final String NAME = "Execute";

  private static final List<String> EXTRAS =
      List.of("args", "environment", "stdin", "workingDirectory");

  private ExecuteTool() {}

  /**
   * Build a binding bound to the given provider. A session built with {@code
   * SessionOptions.Builder.withExecutionProvider(provider)} registers this binding itself; a
   * session whose own tools include it fails to create, with or without a provider.
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
                            "Wall-clock budget in seconds. Defaults to the session's per-tool"
                                + " timeout; the provider may further clamp it to its"
                                + " maximum.")
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
    var script = ToolArgs.stringArg(args, "script");
    var extras =
        EXTRAS.stream()
            .filter(name -> args.get(name) != null)
            .map(name -> "\n+" + name)
            .collect(Collectors.joining());
    return new ToolPermissionKey(
        NAME, runtime(ToolArgs.stringArg(args, "runtime")) + "/" + script.strip() + extras);
  }

  private static String runtime(String argument) {
    try {
      return Runtime.valueOf(argument.toUpperCase(Locale.ROOT)).name();
    } catch (IllegalArgumentException e) {
      return "UNKNOWN";
    }
  }

  private static ToolResult execute(
      ExecutionProvider provider, Map<String, Object> args, ToolContext ctx) {
    ctx.cancellation().throwIfCancelled();
    return switch (ExecuteArguments.parse(provider, args, ctx.deadline())) {
      case Result.Failure<ExecutionRequest> failure -> ToolResult.failure(failure.error());
      case Result.Success<ExecutionRequest> request -> dispatch(provider, request.value(), ctx);
    };
  }

  private static ToolResult dispatch(
      ExecutionProvider provider, ExecutionRequest request, ToolContext ctx) {
    try {
      var result = provider.execute(ctx.sessionContext(), request, ctx.cancellation());
      return ToolResult.success(format(request.runtime(), result));
    } catch (CancellationException e) {
      return ToolResult.failure("Execute: cancelled");
    } catch (RuntimeException e) {
      var message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return ToolResult.failure("Execute: provider failed: " + message);
    }
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
