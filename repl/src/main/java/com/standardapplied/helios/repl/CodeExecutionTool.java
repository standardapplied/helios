/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import ai.singlr.core.tool.ParameterType;
import ai.singlr.core.tool.Tool;
import ai.singlr.core.tool.ToolParameter;
import ai.singlr.core.tool.ToolResult;
import ai.singlr.repl.sandbox.ExecutionResult;
import java.time.Duration;

/**
 * Factory for creating the {@code execute_code} tool backed by a {@link ReplSession}.
 *
 * <p>Sandbox exceptions are returned as {@link ToolResult#success} so the model sees tracebacks and
 * can self-correct, rather than {@link ToolResult#failure} which signals framework-level errors.
 *
 * <p><b>Output truncation.</b> The formatted output returned to the model is capped at {@link
 * ReplConfig#maxOutputCharsToModel()} characters. Variables in the sandbox always persist in full;
 * only the {@code print()}ed output the model observes on the next turn is bounded. This is the
 * load-bearing context-rot fix for long RLM trajectories: a {@code predict()} call that returns 50K
 * of text stays in a JShell variable at full fidelity but only contributes its truncated head (plus
 * a marker) to the next turn's transcript.
 */
public final class CodeExecutionTool {

  /** Stable tool name; matched by hooks, permission rules, and audit. */
  public static final String NAME = "execute_code";

  private CodeExecutionTool() {}

  /**
   * Create an {@code execute_code} tool bound to the given session.
   *
   * @param session the REPL session to execute code in
   * @return a tool that accepts a {@code code} parameter
   */
  public static Tool create(ReplSession session) {
    if (session == null) {
      throw new IllegalArgumentException("Session must not be null");
    }
    return Tool.newBuilder()
        .withName(NAME)
        .withDescription(
            """
            Execute code in a sandboxed environment. State persists across calls within \
            the same session. Use print() for output. Available host functions: \
            predict(instructions, input) for sub-LM calls with fresh context, \
            submit(output) to signal the final result.""")
        .withParameter(
            ToolParameter.newBuilder()
                .withName("code")
                .withType(ParameterType.STRING)
                .withDescription("The code to execute")
                .withRequired(true)
                .build())
        .withResultCompactor(CodeExecutionTool::compactOldExecuteResult)
        .withExecutor(
            (args, ctx) -> {
              var code = args.get("code");
              if (!(code instanceof String codeStr) || codeStr.isBlank()) {
                return ToolResult.failure(
                    "Parameter 'code' is required and must be a non-blank string");
              }
              try {
                var result = session.execute(codeStr);
                var output = formatOutput(result);
                output = truncate(output, session.config().maxOutputCharsToModel());
                return ToolResult.success(output);
              } catch (ReplException e) {
                return ToolResult.success("Error: " + e.getMessage());
              }
            })
        .build();
  }

  /**
   * Per-tool result compactor for {@code execute_code}. Older turns keep length and a short prefix
   * instead of the constant {@code [result omitted]} so the model can tell which earlier call
   * produced what — Prime Intellect's "metadata-only stdout" pattern, applied at compaction time
   * only. Variables in the sandbox itself remain untouched.
   *
   * <p>Format: {@code [execute_code metadata: length=N chars, prefix="P"]}. The prefix is the first
   * {@value #COMPACT_PREFIX_CHARS} characters of the original tool result with newlines and tabs
   * escaped so the line stays single-line and parser-friendly. Quotes inside the prefix are escaped
   * to keep the surrounding {@code "..."} unambiguous. Null and empty inputs render as {@code
   * [execute_code metadata: length=0 chars]} (no prefix clause).
   */
  static String compactOldExecuteResult(String content) {
    if (content == null || content.isEmpty()) {
      return "[execute_code metadata: length=0 chars]";
    }
    var len = content.length();
    var head =
        content.length() <= COMPACT_PREFIX_CHARS
            ? content
            : content.substring(0, COMPACT_PREFIX_CHARS);
    return "[execute_code metadata: length="
        + len
        + " chars, prefix=\""
        + escapeForPrefix(head)
        + "\"]";
  }

  /** Number of characters of the original tool result preserved in the metadata-only form. */
  static final int COMPACT_PREFIX_CHARS = 80;

  /**
   * Escape a string so it can sit unambiguously inside {@code prefix="..."} on a single line.
   * Quotes are escaped to keep the closing delimiter unambiguous; backslashes are escaped first to
   * preserve literal occurrences; control whitespace becomes its standard escape sequence so the
   * compacted line stays single-line.
   */
  private static String escapeForPrefix(String s) {
    var sb = new StringBuilder(s.length() + 8);
    for (var i = 0; i < s.length(); i++) {
      var c = s.charAt(i);
      switch (c) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Render a {@link Duration} compactly. Sub-millisecond durations render as {@code <1ms}
   * (System.nanoTime measurements that round to 0 ms via {@link Duration#toMillis()}). Sub-second
   * durations render as {@code Nms}. Whole-second durations render as {@code Ns}; mixed
   * second/millisecond durations render as {@code N.Ms} with one decimal of precision.
   */
  static String formatDuration(Duration d) {
    if (d == null || d.isNegative() || d.isZero()) {
      return "<1ms";
    }
    var ms = d.toMillis();
    if (ms == 0) {
      return "<1ms";
    }
    if (ms < 1000) {
      return ms + "ms";
    }
    if (ms % 1000 == 0) {
      return (ms / 1000) + "s";
    }
    var seconds = ms / 1000.0;
    return String.format(java.util.Locale.ROOT, "%.1fs", seconds);
  }

  private static String formatOutput(ExecutionResult result) {
    var sb = new StringBuilder();
    if (!result.stdout().isEmpty()) {
      sb.append(result.stdout());
    }
    if (!result.stderr().isEmpty()) {
      if (!sb.isEmpty()) {
        sb.append('\n');
      }
      sb.append("STDERR: ").append(result.stderr());
    }
    if (result.exitCode() != 0) {
      if (!sb.isEmpty()) {
        sb.append('\n');
      }
      sb.append("[exit code ").append(result.exitCode()).append(']');
    }
    if (result.hasSubmittedValue()) {
      if (!sb.isEmpty()) {
        sb.append('\n');
      }
      sb.append("[submitted: ").append(result.submitted()).append(']');
    }
    return sb.isEmpty() ? "(no output)" : sb.toString();
  }

  static String truncate(String output, int cap) {
    if (cap <= 0 || output == null || output.length() <= cap) {
      return output;
    }
    var marker =
        "\n... [output truncated; full output is "
            + output.length()
            + " characters. "
            + "Variables in the sandbox retain their full values — read them by name in your next "
            + "execute_code call.]";
    var headRoom = Math.max(0, cap - marker.length());
    return output.substring(0, headRoom) + marker;
  }
}
