/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.sandbox.ExecutionRequest;
import ai.singlr.repl.sandbox.ExecutionResult;
import ai.singlr.repl.sandbox.Sandbox;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class CodeExecutionToolTest {

  @Test
  void nullSessionThrows() {
    assertThrows(IllegalArgumentException.class, () -> CodeExecutionTool.create(null));
  }

  @Test
  void createReturnsTool() {
    var session = createSession(new StubSandbox(ExecutionResult.success("hello")));
    var tool = CodeExecutionTool.create(session);

    assertEquals("execute_code", tool.name());
    assertNotNull(tool.description());
    assertEquals(1, tool.parameters().size());
    assertEquals("code", tool.parameters().get(0).name());
    assertTrue(tool.parameters().get(0).required());

    session.close();
  }

  @Test
  void executeCodeSuccess() {
    var session = createSession(new StubSandbox(ExecutionResult.success("42")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "System.out.println(42)"));

    assertTrue(result.success());
    assertEquals("42", result.output());

    session.close();
  }

  @Test
  void executeCodeWithStderr() {
    var session = createSession(new StubSandbox(new ExecutionResult("out", "warn", 0, null)));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "code"));

    assertTrue(result.success());
    assertTrue(result.output().contains("out"));
    assertTrue(result.output().contains("STDERR: warn"));

    session.close();
  }

  @Test
  void executeCodeWithExitCode() {
    var session = createSession(new StubSandbox(ExecutionResult.failure("error", 1)));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "bad code"));

    assertTrue(result.success());
    assertTrue(result.output().contains("STDERR: error"));
    assertTrue(result.output().contains("[exit code 1]"));

    session.close();
  }

  @Test
  void executeCodeWithSubmittedValue() {
    var session = createSession(new StubSandbox(ExecutionResult.success("out", "answer")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "submit('answer')"));

    assertTrue(result.success());
    assertTrue(result.output().contains("[submitted: answer]"));

    session.close();
  }

  @Test
  void executeCodeNoOutput() {
    var session = createSession(new StubSandbox(ExecutionResult.success("")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "noop"));

    assertTrue(result.success());
    assertEquals("(no output)", result.output());

    session.close();
  }

  @Test
  void missingCodeParameter() {
    var session = createSession(new StubSandbox(ExecutionResult.success("")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of());

    assertFalse(result.success());
    assertTrue(result.output().contains("'code' is required"));

    session.close();
  }

  @Test
  void blankCodeParameter() {
    var session = createSession(new StubSandbox(ExecutionResult.success("")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "  "));

    assertFalse(result.success());
    assertTrue(result.output().contains("'code' is required"));

    session.close();
  }

  @Test
  void nonStringCodeParameter() {
    var session = createSession(new StubSandbox(ExecutionResult.success("")));
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", 42));

    assertFalse(result.success());
    assertTrue(result.output().contains("'code' is required"));

    session.close();
  }

  @Test
  void replExceptionReturnedAsSuccess() {
    var sandbox =
        new Sandbox() {
          @Override
          public ExecutionResult execute(ExecutionRequest request) {
            throw new ReplException("sandbox died");
          }

          @Override
          public boolean isAlive() {
            return true;
          }

          @Override
          public void close() {}
        };
    var session = createSession(sandbox);
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "anything"));

    assertTrue(result.success());
    assertTrue(result.output().contains("Error: sandbox died"));

    session.close();
  }

  @Test
  void truncateReturnsInputUnchangedWhenUnderCap() {
    assertEquals("hello", CodeExecutionTool.truncate("hello", 100));
    assertEquals("hello", CodeExecutionTool.truncate("hello", 5));
  }

  @Test
  void truncateDisabledByZeroOrNegativeCap() {
    var big = "x".repeat(10_000);
    assertEquals(big, CodeExecutionTool.truncate(big, 0));
    assertEquals(big, CodeExecutionTool.truncate(big, -1));
  }

  @Test
  void truncateNullReturnsNull() {
    assertEquals(null, CodeExecutionTool.truncate(null, 100));
  }

  @Test
  void truncateClipsLongOutputAndAppendsMarker() {
    var big = "x".repeat(20_000);
    var truncated = CodeExecutionTool.truncate(big, 5_000);
    assertTrue(truncated.length() <= 5_000, "truncated length must respect cap");
    assertTrue(truncated.contains("output truncated"), "marker must be present");
    assertTrue(truncated.contains("20000 characters"), "marker must include real length");
    assertTrue(truncated.contains("Variables in the sandbox retain their full values"));
    assertTrue(truncated.startsWith("xxx"), "head of original output preserved");
  }

  @Test
  void executeCodeTruncatesLongStdoutForModelButKeepsFullInHistory() {
    var bigStdout = "y".repeat(20_000);
    var session =
        createSessionWithCap(new StubSandbox(ExecutionResult.success(bigStdout)), /* cap= */ 5_000);
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "print(x)"));

    assertTrue(result.success());
    assertTrue(
        result.output().length() <= 5_000, "model-facing output must respect the configured cap");
    assertTrue(result.output().contains("output truncated"));

    var historical = session.history().get(0);
    assertEquals(bigStdout, historical.stdout(), "full stdout retained in session history");

    session.close();
  }

  @Test
  void formatDurationRendersSubMillisecondAsLessThanOneMs() {
    assertEquals("<1ms", CodeExecutionTool.formatDuration(null));
    assertEquals("<1ms", CodeExecutionTool.formatDuration(Duration.ZERO));
    assertEquals("<1ms", CodeExecutionTool.formatDuration(Duration.ofNanos(500_000)));
    assertEquals(
        "<1ms",
        CodeExecutionTool.formatDuration(Duration.ofMillis(-3)),
        "negative durations are nonsensical and must not crash the header");
  }

  @Test
  void formatDurationRendersSubSecondAsMs() {
    assertEquals("1ms", CodeExecutionTool.formatDuration(Duration.ofMillis(1)));
    assertEquals("250ms", CodeExecutionTool.formatDuration(Duration.ofMillis(250)));
    assertEquals("999ms", CodeExecutionTool.formatDuration(Duration.ofMillis(999)));
  }

  @Test
  void executeCodeToolWiresMetadataOnlyResultCompactor() {
    var session = createSession(new StubSandbox(ExecutionResult.success("")));
    var tool = CodeExecutionTool.create(session);

    var compacted = tool.resultCompactor().apply("hello world");

    assertTrue(
        compacted.startsWith("[execute_code metadata:"),
        "execute_code must use the metadata-only compactor, not the constant placeholder; got: "
            + compacted);
    assertTrue(compacted.contains("length=11 chars"));
    session.close();
  }

  @Test
  void compactOldExecuteResultEmitsLengthAndPrefix() {
    var content = "hello world";
    var compacted = CodeExecutionTool.compactOldExecuteResult(content);
    assertEquals("[execute_code metadata: length=11 chars, prefix=\"hello world\"]", compacted);
  }

  @Test
  void compactOldExecuteResultTruncatesPrefixToCap() {
    var big = "x".repeat(500);
    var compacted = CodeExecutionTool.compactOldExecuteResult(big);
    var expectedPrefix = "x".repeat(CodeExecutionTool.COMPACT_PREFIX_CHARS);
    assertEquals(
        "[execute_code metadata: length=500 chars, prefix=\"" + expectedPrefix + "\"]", compacted);
  }

  @Test
  void compactOldExecuteResultEscapesControlChars() {
    var content = "line1\nline2\twith\ttabs\rand \"quotes\" and \\slashes";
    var compacted = CodeExecutionTool.compactOldExecuteResult(content);
    // The compacted form must remain a single line so the model sees it as one message line.
    assertFalse(
        compacted.contains("\n") && compacted.indexOf('\n') < compacted.length() - 1,
        "compacted form must not contain raw newlines mid-string; got:\n" + compacted);
    assertTrue(compacted.contains("\\n"), "newlines must be escaped");
    assertTrue(compacted.contains("\\t"), "tabs must be escaped");
    assertTrue(compacted.contains("\\r"), "CR must be escaped");
    assertTrue(compacted.contains("\\\""), "quotes inside the prefix must be escaped");
    assertTrue(compacted.contains("\\\\"), "backslashes must be escaped");
  }

  @Test
  void compactOldExecuteResultHandlesNullAndEmpty() {
    assertEquals(
        "[execute_code metadata: length=0 chars]", CodeExecutionTool.compactOldExecuteResult(null));
    assertEquals(
        "[execute_code metadata: length=0 chars]", CodeExecutionTool.compactOldExecuteResult(""));
  }

  @Test
  void compactOldExecuteResultPreservesFullContentBelowCap() {
    var exactlyAtCap = "y".repeat(CodeExecutionTool.COMPACT_PREFIX_CHARS);
    var compacted = CodeExecutionTool.compactOldExecuteResult(exactlyAtCap);
    assertTrue(
        compacted.contains("prefix=\"" + exactlyAtCap + "\""),
        "exact-cap input must keep its full text in the prefix without truncation");
  }

  @Test
  void formatDurationRendersWholeSecondsWithoutDecimal() {
    // Cleaner rendering for round numbers: "30s" reads better than "30.0s" in the budget header.
    assertEquals("1s", CodeExecutionTool.formatDuration(Duration.ofMillis(1000)));
    assertEquals("30s", CodeExecutionTool.formatDuration(Duration.ofSeconds(30)));
    assertEquals("120s", CodeExecutionTool.formatDuration(Duration.ofMinutes(2)));
  }

  @Test
  void formatDurationRendersFractionalSecondsWithOneDecimal() {
    assertEquals("2.4s", CodeExecutionTool.formatDuration(Duration.ofMillis(2400)));
    assertEquals(
        "1.5s",
        CodeExecutionTool.formatDuration(Duration.ofMillis(1499)),
        "1499ms should round to 1.5s under %.1f formatting; locale-independent");
    assertEquals("3.7s", CodeExecutionTool.formatDuration(Duration.ofMillis(3700)));
  }

  @Test
  void executeCodeNoTruncationWhenCapIsZero() {
    var bigStdout = "z".repeat(8_000);
    var session =
        createSessionWithCap(new StubSandbox(ExecutionResult.success(bigStdout)), /* cap= */ 0);
    var tool = CodeExecutionTool.create(session);

    var result = tool.execute(Map.of("code", "print(x)"));

    assertTrue(result.success());
    assertEquals(bigStdout, result.output(), "cap=0 disables truncation");

    session.close();
  }

  private static ReplSession createSession(Sandbox sandbox) {
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> sandbox)
            .withExecutionTimeout(Duration.ofSeconds(10))
            .build();
    return ReplSession.create(config, new Semaphore(10));
  }

  private static ReplSession createSessionWithCap(Sandbox sandbox, int cap) {
    var config =
        ReplConfig.newBuilder()
            .withSandboxFactory(registry -> sandbox)
            .withExecutionTimeout(Duration.ofSeconds(10))
            .withMaxOutputCharsToModel(cap)
            .build();
    return ReplSession.create(config, new Semaphore(10));
  }

  private static class StubSandbox implements Sandbox {
    private final ExecutionResult result;
    private final AtomicBoolean alive = new AtomicBoolean(true);

    StubSandbox(ExecutionResult result) {
      this.result = result;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
      return result;
    }

    @Override
    public boolean isAlive() {
      return alive.get();
    }

    @Override
    public void close() {
      alive.set(false);
    }
  }
}
