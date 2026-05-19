/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.protocol.ProcessTransport;
import ai.singlr.repl.protocol.RpcError;
import ai.singlr.repl.protocol.RpcMessage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import jdk.jshell.JShell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JvmSandboxBootstrapTest {

  private JShell jshell;
  private JvmSandboxBootstrap bootstrap;
  private ByteArrayOutputStream rpcOutput;

  @BeforeEach
  void setUp() {
    jshell = createJShell();
    rpcOutput = new ByteArrayOutputStream();
    var realOut = new PrintStream(rpcOutput, true, StandardCharsets.UTF_8);
    var emptyReader = new BufferedReader(new StringReader(""));
    bootstrap = new JvmSandboxBootstrap(jshell, emptyReader, realOut);
    JvmSandboxBootstrap.setInstance(bootstrap);
  }

  @AfterEach
  void tearDown() {
    JvmSandboxBootstrap.setInstance(null);
    jshell.close();
  }

  @Test
  void warnIfReducedIsolationFiresWhenSandboxPackageIsOpened() {
    // The Surefire harness runs with --add-opens=ai.singlr.repl/ai.singlr.repl.sandbox=ALL-UNNAMED
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
        written.contains("Incus") || written.contains("OS-level"),
        "must point at the load-bearing boundary: " + written);
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
        !module.isNamed() || module.isOpen("ai.singlr.repl.sandbox", unnamedProbe);
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
      var events = fresh.eval("import ai.singlr.repl.sandbox.HostBridge;");
      assertTrue(
          events.stream().allMatch(e -> e.status() == jdk.jshell.Snippet.Status.VALID),
          "import should succeed after addHostBridgeToJShellClasspath: " + events);
    }
  }

  @Test
  void executeSimpleExpression() {
    var result = bootstrap.handleExecute(Map.of("code", "1 + 1", "timeoutMs", 5000));

    assertEquals(0, result.get("exitCode"));
    assertTrue(((String) result.get("stdout")).contains("2"));
    assertEquals("", result.get("stderr"));
  }

  @Test
  void executePrintln() {
    var result =
        bootstrap.handleExecute(Map.of("code", "System.out.println(\"hello\")", "timeoutMs", 5000));

    assertEquals(0, result.get("exitCode"));
    assertTrue(((String) result.get("stdout")).contains("hello"));
  }

  @Test
  void executeMultiSnippet() {
    var code = "var x = 5; var y = x + 10; y";
    var result = bootstrap.handleExecute(Map.of("code", code, "timeoutMs", 5000));

    assertEquals(0, result.get("exitCode"));
    assertTrue(((String) result.get("stdout")).contains("15"));
  }

  @Test
  void executeCompilationError() {
    var result =
        bootstrap.handleExecute(Map.of("code", "int x = \"not-an-int\";", "timeoutMs", 5000));

    assertEquals(1, result.get("exitCode"));
    assertFalse(((String) result.get("stderr")).isEmpty());
  }

  @Test
  void executeRuntimeException() {
    var result =
        bootstrap.handleExecute(
            Map.of("code", "throw new RuntimeException(\"boom\");", "timeoutMs", 5000));

    assertEquals(1, result.get("exitCode"));
    assertTrue(((String) result.get("stderr")).contains("boom"));
  }

  @Test
  void executeTimeout() {
    var result = bootstrap.handleExecute(Map.of("code", "Thread.sleep(60000);", "timeoutMs", 500));

    assertEquals(1, result.get("exitCode"));
    assertTrue(
        ((String) result.get("stderr")).contains("timed out")
            || ((String) result.get("stderr")).contains("Execution timed out"));
  }

  @Test
  void executeStatefulSession() {
    var result1 = bootstrap.handleExecute(Map.of("code", "var x = 5;", "timeoutMs", 5000));
    assertEquals(0, result1.get("exitCode"));

    var result2 = bootstrap.handleExecute(Map.of("code", "x + 10", "timeoutMs", 5000));
    assertEquals(0, result2.get("exitCode"));
    assertTrue(((String) result2.get("stdout")).contains("15"));
  }

  @Test
  void handleUnknownMethod() throws Exception {
    try (var env = createPipedBootstrap()) {
      var readLoopThread = Thread.ofVirtual().start(env.bootstrap()::readLoop);

      var reqJson =
          ProcessTransport.serializeMessage(new RpcMessage.Request("1", "unknownMethod", null));
      env.writeLine(reqJson);

      var line = env.readLine();
      assertTrue(line.startsWith(ProcessTransport.RPC_PREFIX));
      var msg =
          ProcessTransport.deserializeMessage(line.substring(ProcessTransport.RPC_PREFIX.length()));
      assertInstanceOf(RpcMessage.ErrorResponse.class, msg);
      var err = (RpcMessage.ErrorResponse) msg;
      assertEquals("1", err.id());
      assertEquals(RpcError.METHOD_NOT_FOUND, err.error().code());

      env.stdinWriter().close();
      readLoopThread.join(Duration.ofSeconds(2));
    }
  }

  @Test
  void eofExitsLoop() throws Exception {
    try (var env = createPipedBootstrap()) {
      var readLoopThread = Thread.ofVirtual().start(env.bootstrap()::readLoop);

      env.stdinWriter().close();

      readLoopThread.join(Duration.ofSeconds(2));
      assertFalse(readLoopThread.isAlive());
    }
  }

  @Test
  void callHostRegistersAndWaits() throws Exception {
    var resultFuture = new CompletableFuture<Object>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                resultFuture.complete(bootstrap.callHost("test", Map.of("key", "val")));
              } catch (Exception e) {
                resultFuture.completeExceptionally(e);
              }
            });

    Thread.sleep(100);

    bootstrap.dispatch(new RpcMessage.Response("sub-1", Map.of("answer", 42)));

    var result = resultFuture.get(5, TimeUnit.SECONDS);
    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    var map = (Map<String, Object>) result;
    assertEquals(42, map.get("answer"));
  }

  @Test
  void sendRpcPrefixesOutput() throws Exception {
    var response = new RpcMessage.Response("42", Map.of("result", "ok"));
    bootstrap.sendRpc(response);

    var output = rpcOutput.toString(StandardCharsets.UTF_8);
    assertTrue(output.startsWith(ProcessTransport.RPC_PREFIX));
    assertTrue(output.endsWith("\n"));

    var json = output.substring(ProcessTransport.RPC_PREFIX.length()).trim();
    var msg = ProcessTransport.deserializeMessage(json);
    assertInstanceOf(RpcMessage.Response.class, msg);
    assertEquals("42", ((RpcMessage.Response) msg).id());
  }

  @Test
  void instanceAccessor() {
    assertEquals(bootstrap, JvmSandboxBootstrap.instance());
    JvmSandboxBootstrap.setInstance(null);
    assertNull(JvmSandboxBootstrap.instance());
  }

  @Test
  @SuppressWarnings("unchecked")
  void executeWithHostCallback() throws Exception {
    try (var env = createPipedBootstrap()) {
      JvmSandboxBootstrap.setInstance(env.bootstrap());
      var readLoopThread = Thread.ofVirtual().start(env.bootstrap()::readLoop);

      var code = "var result = predict(\"concise\", \"2+2?\"); result";
      var executeReq =
          ProcessTransport.serializeMessage(
              new RpcMessage.Request("1", "execute", Map.of("code", code, "timeoutMs", 10000)));
      env.writeLine(executeReq);

      // Read the reverse RPC request (predict call from sandbox to host)
      var predictLine = env.readLine();
      assertTrue(predictLine.startsWith(ProcessTransport.RPC_PREFIX));
      var predictMsg =
          ProcessTransport.deserializeMessage(
              predictLine.substring(ProcessTransport.RPC_PREFIX.length()));
      assertInstanceOf(RpcMessage.Request.class, predictMsg);
      var predictReq = (RpcMessage.Request) predictMsg;
      assertEquals("predict", predictReq.method());

      // Respond to the predict call
      var predictResp =
          ProcessTransport.serializeMessage(
              new RpcMessage.Response(predictReq.id(), Map.of("output", "4")));
      env.writeLine(predictResp);

      // Read the execute result
      var resultLine = env.readLine();
      assertTrue(resultLine.startsWith(ProcessTransport.RPC_PREFIX));
      var resultMsg =
          ProcessTransport.deserializeMessage(
              resultLine.substring(ProcessTransport.RPC_PREFIX.length()));
      assertInstanceOf(RpcMessage.Response.class, resultMsg);
      var resp = (RpcMessage.Response) resultMsg;
      assertEquals("1", resp.id());

      var resultMap = (Map<String, Object>) resp.result();
      assertEquals(0, resultMap.get("exitCode"));
      assertTrue(((String) resultMap.get("stdout")).contains("4"));

      env.stdinWriter().close();
      readLoopThread.join(Duration.ofSeconds(2));
    }
  }

  @Test
  void parseErrorSendsErrorResponse() throws Exception {
    var input = "not valid json at all\n";
    var stdinReader = new BufferedReader(new StringReader(input));
    var output = new ByteArrayOutputStream();
    var out = new PrintStream(output, true, StandardCharsets.UTF_8);
    var local = new JvmSandboxBootstrap(jshell, stdinReader, out);

    local.readLoop();

    var raw = output.toString(StandardCharsets.UTF_8);
    assertTrue(raw.startsWith(ProcessTransport.RPC_PREFIX));
    var json = raw.substring(ProcessTransport.RPC_PREFIX.length()).trim();
    var msg = ProcessTransport.deserializeMessage(json);
    assertInstanceOf(RpcMessage.ErrorResponse.class, msg);
    var err = (RpcMessage.ErrorResponse) msg;
    assertNull(err.id());
    assertEquals(RpcError.PARSE_ERROR, err.error().code());
  }

  @Test
  void dispatchErrorResponseCompletesExceptionally() throws Exception {
    var resultFuture = new CompletableFuture<Object>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                resultFuture.complete(bootstrap.callHost("test", Map.of("key", "val")));
              } catch (Exception e) {
                resultFuture.completeExceptionally(e);
              }
            });

    Thread.sleep(100);

    bootstrap.dispatch(
        new RpcMessage.ErrorResponse("sub-1", RpcError.internalError("something broke")));

    var ex = assertThrows(ExecutionException.class, () -> resultFuture.get(5, TimeUnit.SECONDS));
    assertInstanceOf(RuntimeException.class, ex.getCause());
    assertTrue(ex.getCause().getMessage().contains("something broke"));
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
    var result = bootstrap.handleExecute(Map.of("timeoutMs", 5000));

    assertEquals(0, result.get("exitCode"));
    assertEquals("", result.get("stdout"));
  }

  @Test
  void executeWithNonStringCode() {
    var result = bootstrap.handleExecute(Map.of("code", 12345, "timeoutMs", 5000));

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
    var result = bootstrap.handleExecute(Map.of("code", "", "timeoutMs", 5000));

    assertEquals(0, result.get("exitCode"));
    assertEquals("", result.get("stdout"));
    assertEquals("", result.get("stderr"));
  }

  @Test
  void executeWhitespaceOnlyCode() {
    var result = bootstrap.handleExecute(Map.of("code", "   \n  ", "timeoutMs", 5000));

    assertEquals(0, result.get("exitCode"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void executeViaDispatchWithNullParams() throws Exception {
    bootstrap.dispatch(new RpcMessage.Request("1", "execute", null));

    Thread.sleep(500);

    var raw = rpcOutput.toString(StandardCharsets.UTF_8);
    assertTrue(raw.contains(ProcessTransport.RPC_PREFIX));
    var json =
        raw.substring(
                raw.indexOf(ProcessTransport.RPC_PREFIX) + ProcessTransport.RPC_PREFIX.length())
            .trim();
    var msg = ProcessTransport.deserializeMessage(json);
    assertInstanceOf(RpcMessage.Response.class, msg);
    var resp = (RpcMessage.Response) msg;
    assertEquals("1", resp.id());
    var resultMap = (Map<String, Object>) resp.result();
    assertEquals(0, resultMap.get("exitCode"));
  }

  @Test
  void submittedValueResetOnExecute() {
    bootstrap.setSubmittedValue("old");
    bootstrap.handleExecute(Map.of("code", "1 + 1", "timeoutMs", 5000));
    assertNull(bootstrap.submittedValue());
  }

  @Test
  void readLoopCleansPendingCallbacksOnEof() throws Exception {
    var future = new CompletableFuture<Object>();
    var callFuture = new CompletableFuture<Void>();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                bootstrap.callHost("test", Map.of("key", "val"));
                callFuture.complete(null);
              } catch (Exception e) {
                future.complete(e);
                callFuture.complete(null);
              }
            });

    Thread.sleep(100);

    bootstrap.readLoop();

    callFuture.get(5, TimeUnit.SECONDS);
    var ex = future.get(5, TimeUnit.SECONDS);
    assertInstanceOf(RuntimeException.class, ex);
    assertTrue(((RuntimeException) ex).getMessage().contains("Sandbox stdin closed"));
  }

  @Test
  void systemStreamsRestoredAfterExecute() {
    var before = System.out;
    var beforeErr = System.err;

    bootstrap.handleExecute(Map.of("code", "System.out.println(\"x\")", "timeoutMs", 5000));

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

  @Test
  void concurrentExecuteRejected() throws Exception {
    var started = new CompletableFuture<Void>();
    var blocker = new CompletableFuture<Void>();

    Thread.ofVirtual()
        .start(
            () -> {
              started.complete(null);
              bootstrap.handleExecute(Map.of("code", "Thread.sleep(5000);", "timeoutMs", 10000));
              blocker.complete(null);
            });

    started.get(2, TimeUnit.SECONDS);
    Thread.sleep(100);

    var result = bootstrap.handleExecute(Map.of("code", "1 + 1", "timeoutMs", 5000));

    assertEquals(1, result.get("exitCode"));
    assertTrue(((String) result.get("stderr")).contains("Concurrent execution rejected"));
  }

  private static JShell createJShell() {
    var jshell = JShell.builder().executionEngine("local").build();
    // In JPMS test environments, JShell needs the module classes on its classpath
    var targetClasses = java.nio.file.Path.of("target", "classes").toAbsolutePath();
    if (java.nio.file.Files.isDirectory(targetClasses)) {
      jshell.addToClasspath(targetClasses.toString());
    }
    jshell.eval("import static ai.singlr.repl.sandbox.HostBridge.*;");
    jshell.eval("import ai.singlr.repl.sandbox.HostBridge;");
    return jshell;
  }

  private PipedBootstrapEnv createPipedBootstrap() throws Exception {
    var jshellLocal = createJShell();
    var stdinWriter = new PipedOutputStream();
    var stdinPipe = new PipedInputStream(stdinWriter);
    var stdoutPipe = new PipedOutputStream();
    var stdoutReader = new PipedInputStream(stdoutPipe);
    var realOut = new PrintStream(stdoutPipe, true, StandardCharsets.UTF_8);
    var stdinBufReader =
        new BufferedReader(new InputStreamReader(stdinPipe, StandardCharsets.UTF_8));
    var pipedBootstrap = new JvmSandboxBootstrap(jshellLocal, stdinBufReader, realOut);
    var stdoutBufReader =
        new BufferedReader(new InputStreamReader(stdoutReader, StandardCharsets.UTF_8));
    return new PipedBootstrapEnv(jshellLocal, pipedBootstrap, stdinWriter, stdoutBufReader);
  }

  private record PipedBootstrapEnv(
      JShell jshell,
      JvmSandboxBootstrap bootstrap,
      PipedOutputStream stdinWriter,
      BufferedReader stdoutBufReader)
      implements AutoCloseable {

    String readLine() throws Exception {
      return stdoutBufReader.readLine();
    }

    void writeLine(String content) throws Exception {
      stdinWriter.write((content + "\n").getBytes(StandardCharsets.UTF_8));
      stdinWriter.flush();
    }

    @Override
    public void close() throws Exception {
      jshell.close();
    }
  }
}
