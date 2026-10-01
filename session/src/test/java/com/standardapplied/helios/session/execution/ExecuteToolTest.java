/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.core.runtime.CancellationToken;
import ai.singlr.core.runtime.SessionContext;
import ai.singlr.core.tool.ToolContext;
import ai.singlr.session.tools.ToolCategory;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class ExecuteToolTest {

  /** Recording provider that captures the last request and returns a configurable result. */
  private static class RecordingProvider implements ExecutionProvider {
    ExecutionRequest lastRequest;
    final ExecutionResult result;
    boolean throwOnExecute;
    Set<Runtime> supportedRuntimes = EnumSet.allOf(Runtime.class);

    RecordingProvider(ExecutionResult result) {
      this.result = result;
    }

    @Override
    public ExecutionCapabilities capabilities() {
      return ExecutionCapabilities.newBuilder().withSupportedRuntimes(supportedRuntimes).build();
    }

    SessionContext lastSession;

    @Override
    public CompletionStage<ExecutionResult> execute(
        SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
      this.lastSession = session;
      this.lastRequest = request;
      if (throwOnExecute) {
        return CompletableFuture.failedFuture(new RuntimeException("boom"));
      }
      return CompletableFuture.completedFuture(result);
    }
  }

  private static ExecutionResult ok(String stdout) {
    return new ExecutionResult(0, stdout, "", Duration.ofMillis(10), false, Map.of());
  }

  private static ToolContext ctx() {
    return ToolContext.of(SessionContext.forTesting("execute-tool-test"), Duration.ofSeconds(5));
  }

  // ── binding shape ────────────────────────────────────────────────────────

  @Test
  void bindingRejectsNullProvider() {
    var ex = assertThrows(NullPointerException.class, () -> ExecuteTool.binding(null));
    assertEquals("provider must not be null", ex.getMessage());
  }

  @Test
  void bindingHasExecutionCategoryAndExpectedName() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    assertEquals(ExecuteTool.NAME, binding.tool().name());
    assertEquals(ToolCategory.EXECUTION, binding.category());
  }

  @Test
  void bindingDeclaresExpectedParameters() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var names = binding.tool().parameters().stream().map(p -> p.name()).toList();
    assertTrue(names.contains("runtime"));
    assertTrue(names.contains("script"));
    assertTrue(names.contains("args"));
    assertTrue(names.contains("workingDirectory"));
    assertTrue(names.contains("timeoutSeconds"));
    assertTrue(names.contains("environment"));
    assertTrue(names.contains("stdin"));
  }

  // ── permission key ───────────────────────────────────────────────────────

  @Test
  void permissionKeyEncodesRuntimeAndFirstToken() {
    var key = ExecuteTool.permissionKey(Map.of("runtime", "bash", "script", "rm -rf /tmp/foo"));
    assertEquals(ExecuteTool.NAME, key.toolName());
    assertEquals("BASH/rm", key.canonicalArgs());
  }

  @Test
  void permissionKeyHandlesSingleTokenScript() {
    var key = ExecuteTool.permissionKey(Map.of("runtime", "BASH", "script", "true"));
    assertEquals("BASH/true", key.canonicalArgs());
  }

  @Test
  void permissionKeyHandlesMissingRuntime() {
    var key = ExecuteTool.permissionKey(Map.of("script", "echo hi"));
    assertEquals("UNKNOWN/echo", key.canonicalArgs());
  }

  @Test
  void permissionKeyHandlesBlankScript() {
    var key = ExecuteTool.permissionKey(Map.of("runtime", "BASH", "script", ""));
    assertEquals("BASH/", key.canonicalArgs());
  }

  // ── arg validation ───────────────────────────────────────────────────────

  @Test
  void missingRuntimeReturnsFailure() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("script", "echo hi"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'runtime'"));
  }

  @Test
  void unknownRuntimeReturnsFailure() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "DELPHI", "script", "x"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("unknown runtime"));
  }

  @Test
  void unsupportedRuntimeShortCircuitsBeforeProviderDispatch() {
    var provider = new RecordingProvider(ok(""));
    provider.supportedRuntimes = EnumSet.of(Runtime.BASH); // PYTHON not supported
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "PYTHON", "script", "print('x')"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("not supported by this provider"));
    org.junit.jupiter.api.Assertions.assertNull(
        provider.lastRequest, "provider should not have been called");
  }

  @Test
  void supportedRuntimeProceedsThroughCapabilityCheck() {
    var provider = new RecordingProvider(ok("ok"));
    provider.supportedRuntimes = EnumSet.of(Runtime.BASH);
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "BASH", "script", "x"), ctx());
    assertTrue(result.success());
    assertEquals(Runtime.BASH, provider.lastRequest.runtime());
  }

  @Test
  void runtimeCaseInsensitiveUnderUnusualLocale() {
    // Turkish locale lowercases "I" to "ı" by default; without Locale.ROOT a Turkish-locale host
    // would refuse to match "python" -> Runtime.PYTHON. Pinning Locale.ROOT keeps the wire-form
    // match stable regardless of the JVM's default locale.
    var defaultLocale = java.util.Locale.getDefault();
    try {
      java.util.Locale.setDefault(java.util.Locale.of("tr"));
      var provider = new RecordingProvider(ok(""));
      provider.supportedRuntimes = EnumSet.allOf(Runtime.class);
      var binding = ExecuteTool.binding(provider);
      var result = binding.tool().execute(Map.of("runtime", "python", "script", "x"), ctx());
      assertTrue(result.success(), "lowercase python should resolve under tr locale");
      assertEquals(Runtime.PYTHON, provider.lastRequest.runtime());
    } finally {
      java.util.Locale.setDefault(defaultLocale);
    }
  }

  @Test
  void missingScriptReturnsFailure() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "BASH"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("missing required 'script'"));
  }

  @Test
  void argsMustBeArrayOfStrings() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(Map.of("runtime", "BASH", "script", "echo", "args", List.of(1, 2, 3)), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("array of strings"));
  }

  @Test
  void argsCannotBeNonArray() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(Map.of("runtime", "BASH", "script", "echo", "args", "not-array"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("array of strings"));
  }

  @Test
  void invalidTimeoutSecondsReturnsFailure() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(Map.of("runtime", "BASH", "script", "echo", "timeoutSeconds", 0), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("positive integer"));
  }

  @Test
  void environmentMustBeStringMap() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(
                Map.of("runtime", "BASH", "script", "echo", "environment", Map.of("FOO", 123)),
                ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("string→string"));
  }

  @Test
  void environmentMustNotBeNonMap() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(
                Map.of("runtime", "BASH", "script", "echo", "environment", "not-a-map"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("string→string"));
  }

  // ── successful dispatch ──────────────────────────────────────────────────

  @Test
  void successfulCallFormatsResultAndCarriesStructuredData() {
    var provider = new RecordingProvider(ok("hello\n"));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(
                Map.of(
                    "runtime",
                    "BASH",
                    "script",
                    "echo hello",
                    "args",
                    List.of("a", "b"),
                    "workingDirectory",
                    "/tmp",
                    "timeoutSeconds",
                    7,
                    "environment",
                    Map.of("FOO", "bar"),
                    "stdin",
                    "in"),
                ctx());
    assertTrue(result.success());
    assertTrue(result.output().contains("runtime BASH"));
    assertTrue(result.output().contains("exit 0"));
    assertTrue(result.output().contains("hello"));
    assertInstanceOf(ExecutionResult.class, result.data());
    assertSame(provider.result, result.data());
    assertEquals(Runtime.BASH, provider.lastRequest.runtime());
    assertEquals("echo hello", provider.lastRequest.script());
    assertEquals(List.of("a", "b"), provider.lastRequest.args());
    assertEquals(Path.of("/tmp"), provider.lastRequest.workingDirectory());
    assertEquals(Duration.ofSeconds(7), provider.lastRequest.timeout());
    assertEquals(Map.of("FOO", "bar"), provider.lastRequest.environment());
    assertEquals("in", provider.lastRequest.stdin().orElseThrow());
  }

  @Test
  void timeoutFlagPropagatedToOutput() {
    var timedOut = new ExecutionResult(-1, "", "", Duration.ofMillis(50), true, Map.of());
    var provider = new RecordingProvider(timedOut);
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(Map.of("runtime", "BASH", "script", "sleep 5", "timeoutSeconds", 1), ctx());
    assertTrue(result.success(), "tool dispatch should succeed even when execution timed out");
    assertTrue(result.output().contains("TIMEOUT"));
    var data = assertInstanceOf(ExecutionResult.class, result.data());
    assertTrue(data.timedOut());
  }

  @Test
  void formatIncludesStderrWhenPresent() {
    var withErr = new ExecutionResult(2, "out\n", "err\n", Duration.ofMillis(5), false, Map.of());
    var provider = new RecordingProvider(withErr);
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "BASH", "script", "x"), ctx());
    assertTrue(result.output().contains("[stderr]"));
    assertTrue(result.output().contains("err"));
  }

  // ── provider failure paths ───────────────────────────────────────────────

  @Test
  void providerExceptionalCompletionReturnsToolFailure() {
    var provider = new RecordingProvider(ok(""));
    provider.throwOnExecute = true;
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "BASH", "script", "x"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("provider failed"));
    assertTrue(result.output().contains("boom"));
  }

  @Test
  void emptyWorkingDirectoryIsTreatedAsUnset() {
    var provider = new RecordingProvider(ok("ok"));
    var binding = ExecuteTool.binding(provider);
    var result =
        binding
            .tool()
            .execute(Map.of("runtime", "BASH", "script", "x", "workingDirectory", ""), ctx());
    assertTrue(result.success());
    // The request must NOT carry an explicit Path — empty string is treated as "no working dir".
    org.junit.jupiter.api.Assertions.assertNull(provider.lastRequest.workingDirectory());
  }

  @Test
  void providerFutureCompletedWithCancellationExceptionSurfacesAsCancelled() {
    var provider =
        new RecordingProvider(ok("")) {
          @Override
          public CompletionStage<ExecutionResult> execute(
              SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
            this.lastSession = session;
            this.lastRequest = request;
            return CompletableFuture.failedFuture(
                new java.util.concurrent.CancellationException("cancelled-mid-flight"));
          }
        };
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "BASH", "script", "x"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("Execute: cancelled"));
  }

  @Test
  void providerFutureCompletedWithNullCauseExceptionSurfacesSimpleName() {
    var provider =
        new RecordingProvider(ok("")) {
          @Override
          public CompletionStage<ExecutionResult> execute(
              SessionContext session, ExecutionRequest request, CancellationToken cancellation) {
            this.lastSession = session;
            this.lastRequest = request;
            // RuntimeException with null message — the formatter must fall back to the class's
            // simple name rather than print "null".
            return CompletableFuture.failedFuture(new RuntimeException((String) null));
          }
        };
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "BASH", "script", "x"), ctx());
    assertFalse(result.success());
    assertTrue(result.output().contains("RuntimeException"));
  }

  @Test
  void formatAddsNewlineBetweenStdoutWithoutTrailingNewlineAndStderr() {
    var withErr =
        new ExecutionResult(2, "out-no-newline", "err\n", Duration.ofMillis(5), false, Map.of());
    var provider = new RecordingProvider(withErr);
    var binding = ExecuteTool.binding(provider);
    var result = binding.tool().execute(Map.of("runtime", "BASH", "script", "x"), ctx());
    assertTrue(result.output().contains("out-no-newline"));
    assertTrue(result.output().contains("[stderr]"));
    // The formatter inserts a newline between non-newline-terminated stdout and the stderr block.
    var idx = result.output().indexOf("[stderr]");
    assertTrue(idx > 0);
    var charBefore = result.output().charAt(idx - 1);
    assertEquals('\n', charBefore);
  }

  @Test
  void cancellationBeforeDispatchReturnsFailure() {
    var provider = new RecordingProvider(ok(""));
    var binding = ExecuteTool.binding(provider);
    var token = new CancellationToken();
    token.cancel("test");
    var toolCtx =
        ToolContext.of(
            new SessionContext("cancel-test", token, Clock.systemUTC()), Duration.ofSeconds(1));
    var result = binding.tool().execute(Map.of("runtime", "BASH", "script", "x"), toolCtx);
    assertFalse(result.success());
    assertTrue(result.output().contains("test"));
  }
}
