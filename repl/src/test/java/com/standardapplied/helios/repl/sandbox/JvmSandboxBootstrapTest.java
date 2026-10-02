/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.repl.protocol.RpcError;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import jdk.jshell.JShell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * No test depends on how long an evaluation takes. An execute's timeout is {@link
 * #BEYOND_HANG_GUARD_MS} unless the timeout is what the test exercises, and a test that answers a
 * host call first takes the request line: the bootstrap writes it after registering the pending
 * call, so the answer cannot arrive too early to be matched.
 */
class JvmSandboxBootstrapTest {

  private static final long BEYOND_HANG_GUARD_MS = Await.HANG_GUARD.multipliedBy(5).toMillis();

  private static final String BLOCK_UNTIL_INTERRUPTED =
      "new java.util.concurrent.CountDownLatch(1).await();";

  private BootstrapEnvironment env;
  private JvmSandboxBootstrap bootstrap;

  @BeforeEach
  void setUp() {
    env = new BootstrapEnvironment();
    bootstrap = env.bootstrap();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void warnIfReducedIsolationFiresWhenSandboxPackageIsOpened() {
    // The Surefire harness runs with
    // --add-opens=com.standardapplied.helios.repl/com.standardapplied.helios.repl.sandbox=ALL-UNNAMED
    // (also propagated to the JvmSandbox subprocess via shouldPropagateJvmArg). That open is what
    // makes the reflection RPC forgery in JvmSandboxTest reproduce — so under this test JVM the
    // WARNING must fire. Real production launches without --add-opens, in modulepath mode, will
    // not trigger the WARNING.
    var buffer = new ByteArrayOutputStream();
    var stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);
    JvmSandboxBootstrap.warnIfReducedIsolation(stream);
    var written = buffer.toString(StandardCharsets.UTF_8);
    assertTrue(
        written.startsWith("WARNING:"),
        "expected a single WARNING line to surface the reduced-isolation regime, got: " + written);
    assertTrue(written.contains("setAccessible"), "must explain the attack mechanism: " + written);
    assertTrue(
        written.contains("OS-level"),
        "must point at the externally-arranged OS-level boundary: " + written);
  }

  @Test
  void warnIfReducedIsolationStaysSilentWhenIsolationIntact() {
    // Mirror the production-side detection: the WARNING fires when the module is unnamed OR the
    // sandbox package is open to the unnamed-module probe. When neither holds the early return
    // must produce zero output. Surefire opens the package via --add-opens here, so under this
    // JVM the precondition typically holds and the test self-skips; a clean modulepath launch
    // would exercise the silent path.
    var module = JvmSandboxBootstrap.class.getModule();
    var unnamedProbe = ClassLoader.getPlatformClassLoader().getUnnamedModule();
    var inReducedIsolation =
        !module.isNamed() || module.isOpen("com.standardapplied.helios.repl.sandbox", unnamedProbe);
    if (inReducedIsolation) {
      return;
    }
    var buffer = new ByteArrayOutputStream();
    JvmSandboxBootstrap.warnIfReducedIsolation(
        new PrintStream(buffer, true, StandardCharsets.UTF_8));
    assertEquals("", buffer.toString(StandardCharsets.UTF_8));
  }

  @Test
  void addHostBridgeToJShellClasspathIsSafe() {
    try (var fresh = JShell.builder().executionEngine("local").build()) {
      JvmSandboxBootstrap.addHostBridgeToJShellClasspath(fresh);
      var events = fresh.eval("import com.standardapplied.helios.repl.sandbox.HostBridge;");
      assertTrue(
          events.stream().allMatch(e -> e.status() == jdk.jshell.Snippet.Status.VALID),
          "import should succeed after addHostBridgeToJShellClasspath: " + events);
    }
  }

  @Test
  void executeSimpleExpression() {
    var result =
        bootstrap.handleExecute(Map.of("code", "1 + 1", "timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals(0, result.get("exitCode"));
    assertTrue(((String) result.get("stdout")).contains("2"));
    assertEquals("", result.get("stderr"));
  }

  @Test
  void executePrintln() {
    var result =
        bootstrap.handleExecute(
            Map.of("code", "System.out.println(\"hello\")", "timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals(0, result.get("exitCode"));
    assertTrue(((String) result.get("stdout")).contains("hello"));
  }

  @Test
  void executeMultiSnippet() {
    var code = "var x = 5; var y = x + 10; y";
    var result = bootstrap.handleExecute(Map.of("code", code, "timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals(0, result.get("exitCode"));
    assertTrue(((String) result.get("stdout")).contains("15"));
  }

  @Test
  void executeCompilationError() {
    var result =
        bootstrap.handleExecute(
            Map.of("code", "int x = \"not-an-int\";", "timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals(1, result.get("exitCode"));
    assertFalse(((String) result.get("stderr")).isEmpty());
  }

  @Test
  void executeRuntimeException() {
    var result =
        bootstrap.handleExecute(
            Map.of(
                "code",
                "throw new RuntimeException(\"boom\");",
                "timeoutMs",
                BEYOND_HANG_GUARD_MS));

    assertEquals(1, result.get("exitCode"));
    assertTrue(((String) result.get("stderr")).contains("boom"));
  }

  @Test
  void executeTimeout() {
    var result = bootstrap.handleExecute(Map.of("code", BLOCK_UNTIL_INTERRUPTED, "timeoutMs", 500));

    assertEquals(1, result.get("exitCode"));
    assertTrue(((String) result.get("stderr")).contains("Execution timed out"));
  }

  @Test
  void executeStatefulSession() {
    var result1 =
        bootstrap.handleExecute(Map.of("code", "var x = 5;", "timeoutMs", BEYOND_HANG_GUARD_MS));
    assertEquals(0, result1.get("exitCode"));

    var result2 =
        bootstrap.handleExecute(Map.of("code", "x + 10", "timeoutMs", BEYOND_HANG_GUARD_MS));
    assertEquals(0, result2.get("exitCode"));
    assertTrue(((String) result2.get("stdout")).contains("15"));
  }

  @Test
  void handleUnknownMethod() {
    env.startReadLoop();

    env.feed(new RpcMessage.Request("1", "unknownMethod", null));

    var err = assertInstanceOf(RpcMessage.ErrorResponse.class, env.nextMessage());
    assertEquals("1", err.id());
    assertEquals(RpcError.METHOD_NOT_FOUND, err.error().code());
  }

  @Test
  void eofExitsLoop() {
    env.startReadLoop();

    env.endInput();

    env.awaitReadLoopEnd();
  }

  @Test
  void callHostRegistersAndWaits() {
    var result = env.inSandbox(() -> bootstrap.callHost("test", Map.of("key", "val")));
    var request = env.nextRequest();
    assertEquals("test", request.method());

    bootstrap.dispatch(new RpcMessage.Response(request.id(), Map.of("answer", 42)));

    assertEquals(Map.of("answer", 42), Await.value("the host call's result", result));
  }

  @Test
  void sendRpcPrefixesOutput() throws Exception {
    bootstrap.sendRpc(new RpcMessage.Response("42", Map.of("result", "ok")));

    var msg = assertInstanceOf(RpcMessage.Response.class, env.nextMessage());
    assertEquals("42", msg.id());
  }

  @Test
  void instanceAccessor() {
    assertEquals(bootstrap, JvmSandboxBootstrap.instance());
    JvmSandboxBootstrap.setInstance(null);
    assertNull(JvmSandboxBootstrap.instance());
  }

  @Test
  @SuppressWarnings("unchecked")
  void executeWithHostCallback() {
    env.startReadLoop();
    var code = "var result = predict(\"concise\", \"2+2?\"); result";
    env.feed(
        new RpcMessage.Request(
            "1", "execute", Map.of("code", code, "timeoutMs", BEYOND_HANG_GUARD_MS)));

    var predict = env.nextRequest();
    assertEquals("predict", predict.method());
    env.feed(new RpcMessage.Response(predict.id(), Map.of("output", "4")));

    var resp = assertInstanceOf(RpcMessage.Response.class, env.nextMessage());
    assertEquals("1", resp.id());
    var resultMap = (Map<String, Object>) resp.result();
    assertEquals(0, resultMap.get("exitCode"));
    assertTrue(((String) resultMap.get("stdout")).contains("4"));
  }

  @Test
  void parseErrorSendsErrorResponse() {
    env.feedLine("not valid json at all");
    env.endInput();

    bootstrap.readLoop();

    var err = assertInstanceOf(RpcMessage.ErrorResponse.class, env.nextMessage());
    assertNull(err.id());
    assertEquals(RpcError.PARSE_ERROR, err.error().code());
  }

  @Test
  void dispatchErrorResponseCompletesExceptionally() {
    var result = env.inSandbox(() -> bootstrap.callHost("test", Map.of("key", "val")));
    var request = env.nextRequest();

    bootstrap.dispatch(
        new RpcMessage.ErrorResponse(request.id(), RpcError.internalError("something broke")));

    var failure = Await.failure("the host call answered with an error", result);
    assertInstanceOf(RuntimeException.class, failure);
    assertTrue(failure.getMessage().contains("something broke"));
  }

  @Test
  void dispatchResponseWithUnknownIdIgnored() {
    bootstrap.dispatch(new RpcMessage.Response("unknown-id", "data"));
  }

  @Test
  void dispatchErrorResponseWithNullIdIgnored() {
    bootstrap.dispatch(new RpcMessage.ErrorResponse(null, RpcError.internalError("orphan")));
  }

  @Test
  void dispatchNotificationIgnored() {
    bootstrap.dispatch(new RpcMessage.Notification("event", Map.of("key", "val")));
  }

  @Test
  void executeWithMissingCodeParam() {
    var result = bootstrap.handleExecute(Map.of("timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals(0, result.get("exitCode"));
    assertEquals("", result.get("stdout"));
  }

  @Test
  void executeWithNonStringCode() {
    var result = bootstrap.handleExecute(Map.of("code", 12345, "timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals(0, result.get("exitCode"));
    assertEquals("", result.get("stdout"));
  }

  @Test
  void executeWithMissingTimeout() {
    var result = bootstrap.handleExecute(Map.of("code", "1 + 1"));

    assertEquals(0, result.get("exitCode"));
    assertTrue(((String) result.get("stdout")).contains("2"));
  }

  @Test
  void executeEmptyCode() {
    var result = bootstrap.handleExecute(Map.of("code", "", "timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals(0, result.get("exitCode"));
    assertEquals("", result.get("stdout"));
    assertEquals("", result.get("stderr"));
  }

  @Test
  void executeWhitespaceOnlyCode() {
    var result =
        bootstrap.handleExecute(Map.of("code", "   \n  ", "timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals(0, result.get("exitCode"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void executeViaDispatchWithNullParams() {
    bootstrap.dispatch(new RpcMessage.Request("1", "execute", null));

    var resp = assertInstanceOf(RpcMessage.Response.class, env.nextMessage());
    assertEquals("1", resp.id());
    var resultMap = (Map<String, Object>) resp.result();
    assertEquals(0, resultMap.get("exitCode"));
  }

  @Test
  void submittedValueResetOnExecute() {
    bootstrap.setSubmittedValue("old");
    bootstrap.handleExecute(Map.of("code", "1 + 1", "timeoutMs", BEYOND_HANG_GUARD_MS));
    assertNull(bootstrap.submittedValue());
  }

  @Test
  void readLoopCleansPendingCallbacksOnEof() {
    var call = env.inSandbox(() -> bootstrap.callHost("test", Map.of("key", "val")));
    env.nextRequest();
    env.endInput();

    bootstrap.readLoop();

    var failure = Await.failure("the host call pending at end of input", call);
    assertInstanceOf(RuntimeException.class, failure);
    assertTrue(failure.getMessage().contains("Sandbox stdin closed"));
  }

  @Test
  void systemStreamsRestoredAfterExecute() {
    var before = System.out;
    var beforeErr = System.err;

    bootstrap.handleExecute(
        Map.of("code", "System.out.println(\"x\")", "timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals(before, System.out);
    assertEquals(beforeErr, System.err);
  }

  @Test
  void systemStreamsRestoredAfterTimeout() {
    var before = System.out;
    var beforeErr = System.err;

    bootstrap.handleExecute(
        Map.of("code", "while (!Thread.currentThread().isInterrupted()) {}", "timeoutMs", 200));

    assertEquals(before, System.out);
    assertEquals(beforeErr, System.err);
  }

  /**
   * The first execute calls the host and is not answered until the second has been rejected, so it
   * holds the execute lock for exactly that long. Its request line is the event that shows it got
   * there.
   */
  @Test
  void concurrentExecuteRejected() {
    var first =
        env.inSandbox(
            () ->
                bootstrap.handleExecute(
                    Map.of(
                        "code",
                        "predict(\"hold\", \"the lock\")",
                        "timeoutMs",
                        BEYOND_HANG_GUARD_MS)));
    var heldBy = env.nextRequest();

    var result =
        bootstrap.handleExecute(Map.of("code", "1 + 1", "timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals(1, result.get("exitCode"));
    assertTrue(((String) result.get("stderr")).contains("Concurrent execution rejected"));
    bootstrap.dispatch(new RpcMessage.Response(heldBy.id(), Map.of("output", "released")));
    assertEquals(0, Await.value("the first execute", first).get("exitCode"));
  }
}
