/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.execution;

import com.standardapplied.helios.core.common.Result;
import com.standardapplied.helios.core.common.Strings;
import com.standardapplied.helios.session.tools.ToolArgs;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses the {@link ExecuteTool}'s arguments into an {@link ExecutionRequest}, validating them in a
 * fixed order and refusing a runtime the provider does not support before anything crosses the
 * provider boundary. Each failure is the message the model sees.
 */
final class ExecuteArguments {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private ExecuteArguments() {}

  /**
   * Parse and validate.
   *
   * @param provider the provider whose capabilities bound the runtime
   * @param args the tool's arguments
   * @return the request, or the first validation failure
   */
  static Result<ExecutionRequest> parse(ExecutionProvider provider, Map<String, Object> args) {
    return switch (runtime(provider, args)) {
      case Result.Failure<Runtime> failure -> new Result.Failure<>(failure.error());
      case Result.Success<Runtime> runtime -> request(runtime.value(), args);
    };
  }

  private static Result<Runtime> runtime(ExecutionProvider provider, Map<String, Object> args) {
    var runtimeArg = ToolArgs.stringArg(args, "runtime");
    if (Strings.isBlank(runtimeArg)) {
      return new Result.Failure<>("Execute: missing required 'runtime' argument");
    }
    Runtime runtime;
    try {
      runtime = Runtime.valueOf(runtimeArg.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return new Result.Failure<>(
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
      return new Result.Failure<>(
          "Execute: runtime '"
              + runtime
              + "' is not supported by this provider (supported: "
              + supported
              + ")");
    }
    return new Result.Success<>(runtime);
  }

  private static Result<ExecutionRequest> request(Runtime runtime, Map<String, Object> args) {
    var script = ToolArgs.stringArg(args, "script");
    if (Strings.isBlank(script)) {
      return new Result.Failure<>("Execute: missing required 'script' argument");
    }
    var positionalArgs = arrayArg(args, "args");
    if (positionalArgs == null) {
      return new Result.Failure<>("Execute: 'args' must be an array of strings");
    }
    var workingDirectory = ToolArgs.stringArgOrNull(args, "workingDirectory");
    var timeout = timeoutArg(args);
    if (timeout == null) {
      return new Result.Failure<>("Execute: 'timeoutSeconds' must be a positive integer");
    }
    var environment = environmentArg(args.get("environment"));
    if (environment == null) {
      return new Result.Failure<>("Execute: 'environment' must be an object of string→string");
    }
    var stdin = ToolArgs.stringArgOrNull(args, "stdin");
    var builder =
        ExecutionRequest.newBuilder()
            .withRuntime(runtime)
            .withScript(script)
            .withArgs(positionalArgs)
            .withTimeout(timeout)
            .withEnvironment(environment);
    if (workingDirectory != null && !workingDirectory.isEmpty()) {
      builder.withWorkingDirectory(Path.of(workingDirectory));
    }
    if (stdin != null) {
      builder.withStdin(stdin);
    }
    return new Result.Success<>(builder.build());
  }

  private static Duration timeoutArg(Map<String, Object> args) {
    if (args.get("timeoutSeconds") == null) {
      return DEFAULT_TIMEOUT;
    }
    var seconds = ToolArgs.intArg(args, "timeoutSeconds", 0);
    return seconds <= 0 ? null : Duration.ofSeconds(seconds);
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
}
