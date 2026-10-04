/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.repl.protocol.RpcError;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jdk.jshell.JShell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * No test depends on how long an evaluation takes. An execute's timeout is {@link
 * #BEYOND_HANG_GUARD_MS} unless the timeout is what the test exercises, and a stopped snippet's
 * grace is beyond the hang guard unless its expiry is what the test exercises. A test that answers
 * a host call first takes the request line: the bootstrap writes it after registering the pending
 * call, so the answer cannot arrive too early to be matched.
 */
class JvmSandboxBootstrapTest {

  private static final long BEYOND_HANG_GUARD_MS = Await.HANG_GUARD.multipliedBy(5).toMillis();

  private static final long TIMEOUT_MS = 300;

  private static final Duration SHORT_STOP_GRACE = Duration.ofMillis(300);

  private static final String BLOCK_UNTIL_INTERRUPTED =
      "new java.util.concurrent.CountDownLatch(1).await();";

  private static final String LOOP_FOREVER = "while (true) { }";

  private static final String SNIPPET_THREAD_GROUP = "JShell process local execution";

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
  void blockedSnippetIsStoppedAtTimeout() {
    var result =
        bootstrap.handleExecute(Map.of("code", BLOCK_UNTIL_INTERRUPTED, "timeoutMs", TIMEOUT_MS));

    assertTimedOut(result);
    assertEquals(List.of(), liveSnippetThreads());
  }

  @Test
  void loopingSnippetIsStoppedAtTimeout() {
    var result = bootstrap.handleExecute(Map.of("code", LOOP_FOREVER, "timeoutMs", TIMEOUT_MS));

    assertTimedOut(result);
    assertEquals(List.of(), liveSnippetThreads());
  }

  /**
   * A timeout this short usually lands while JShell is still compiling the statement, when {@link
   * JShell#stop()} does nothing and before JShell clears its stop flag to start the statement. The
   * snippet must be stopped all the same once it runs; if the timeout lands first, the statement
   * never starts. Either way round, the outcome is the same.
   */
  @ParameterizedTest
  @ValueSource(strings = {LOOP_FOREVER, BLOCK_UNTIL_INTERRUPTED})
  void timeoutBeforeTheSnippetRunsStillStopsIt(String snippet) {
    var result = bootstrap.handleExecute(Map.of("code", snippet, "timeoutMs", 1));

    assertTimedOut(result);
    assertEquals(List.of(), liveSnippetThreads());
  }

  /**
   * A stop that fails cannot end the snippet, so the snippet is given up as unstoppable, and the
   * failure is reported once rather than on every retry. The host call proves the snippet is
   * running before the timeout can act.
   */
  @Test
  void failingStopIsReportedOnceAndTheSnippetGivenUp() {
    env.close();
    env =
        BootstrapEnvironment.failingStop(
            SHORT_STOP_GRACE, new IllegalStateException("the engine failed to stop"));
    bootstrap = env.bootstrap();

    bootstrap.dispatch(
        new RpcMessage.Request(
            "1",
            "execute",
            Map.of(
                "code",
                "predict(\"running\", \"now\"); " + BLOCK_UNTIL_INTERRUPTED,
                "timeoutMs",
                TIMEOUT_MS)));
    var running = env.nextRequest();
    bootstrap.dispatch(new RpcMessage.Response(running.id(), Map.of("output", "go on")));

    var result = responseResult(env.nextMessage());
    assertUnstoppable(result);
    assertEquals(
        1,
        Pattern.compile("the engine failed to stop")
            .matcher((String) result.get("stderr"))
            .results()
            .count());
    assertEquals(3, Await.value("the sandbox's exit", env.exitStatus()));
    liveSnippetThreads()
        .forEach(
            thread -> {
              thread.interrupt();
              Await.termination("a snippet thread the failed stop left behind", thread);
            });
  }

  @Test
  void installPreludeIsAnsweredThroughTheDispatcher() {
    bootstrap.dispatch(
        new RpcMessage.Request("1", "installPrelude", Map.of("snippet", "int fromPrelude = 7;")));

    var response = assertInstanceOf(RpcMessage.Response.class, env.nextMessage());
    assertEquals("1", response.id());
    assertEquals(Map.of("success", true), response.result());
  }

  /** A closed JShell makes the handler throw; the host still gets an answer to its request. */
  @Test
  void requestWhoseHandlerThrowsIsAnsweredWithAnInternalError() {
    env.close();

    bootstrap.dispatch(
        new RpcMessage.Request("1", "installPrelude", Map.of("snippet", "int fromPrelude = 7;")));

    var error = assertInstanceOf(RpcMessage.ErrorResponse.class, env.nextMessage());
    assertEquals("1", error.id());
    assertEquals(RpcError.INTERNAL_ERROR, error.error().code());
  }

  @Test
  void stopGraceTravelsThroughTheLaunchCommand() {
    var grace = Duration.ofNanos(1_500_001);
    var config = JvmSandboxConfig.newBuilder().withStopGrace(grace).build();

    var command = JvmSandbox.buildLaunchCommand("/fake/java", config, "/tmp/rpc.sock");

    assertEquals(grace, JvmSandboxBootstrap.parseStopGraceArg(command.toArray(String[]::new)));
  }

  @Test
  void stopGraceDefaultsWhenTheLaunchCommandHasNone() {
    var parsed = JvmSandboxBootstrap.parseStopGraceArg(new String[] {"--rpc-socket=/x"});

    assertEquals(JvmSandboxConfig.DEFAULT_STOP_GRACE, parsed);
  }

  @ParameterizedTest
  @CsvSource({"PT0S, is not positive", "-PT1S, is not positive", "5s, is not a duration"})
  void stopGraceThatIsNotAPositiveDurationIsRejected(String value, String problem) {
    var args = new String[] {"--stop-grace=" + value};

    var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> JvmSandboxBootstrap.parseStopGraceArg(args));

    assertEquals("--stop-grace=" + value + " " + problem, thrown.getMessage());
  }

  /** The follow-up execute that reads the variable is what shows the second statement never ran. */
  @Test
  void noStatementAfterTheTimedOutOneRuns() {
    bootstrap.handleExecute(Map.of("code", "int after = 0;", "timeoutMs", BEYOND_HANG_GUARD_MS));
    var timedOut =
        bootstrap.handleExecute(
            Map.of("code", BLOCK_UNTIL_INTERRUPTED + " after = 1;", "timeoutMs", TIMEOUT_MS));
    assertTimedOut(timedOut);

    var read = bootstrap.handleExecute(Map.of("code", "after", "timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals("0", ((String) read.get("stdout")).strip());
  }

  /**
   * A snippet blocked entering a monitor held by a thread outside its thread group is out of reach
   * of {@link JShell#stop()}: the group interrupt misses the holder and a thread blocked on monitor
   * entry passes no stop check. A snippet that also holds the monitor of a captured system stream
   * must not keep the timeout handling from deciding and answering.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "synchronized (monitor) { }",
        "synchronized (System.err) { synchronized (monitor) { } }",
        "synchronized (System.out) { synchronized (monitor) { } }"
      })
  void unstoppableSnippetTerminatesTheSandbox(String unstoppable) {
    useShortStopGrace();
    var holding = holdMonitorOutsideTheSnippetGroup();

    bootstrap.dispatch(
        new RpcMessage.Request(
            "2", "execute", Map.of("code", unstoppable, "timeoutMs", TIMEOUT_MS)));

    assertUnstoppable(responseResult(env.nextMessage()));
    assertEquals(3, Await.value("the sandbox's exit", env.exitStatus()));
    releaseMonitor(holding);
  }

  /**
   * JShell joins only the snippet threads it saw right after starting the snippet, so the eval
   * thread ends while a thread the snippet starts on being stopped is still blocked. The park is a
   * host call, answered at the end in case the stop came before the snippet reached it.
   */
  @Test
  void threadStartedAfterJShellsSnapshotMustAlsoEnd() {
    useShortStopGrace();
    var holding = holdMonitorOutsideTheSnippetGroup();

    bootstrap.dispatch(
        new RpcMessage.Request(
            "2",
            "execute",
            Map.of(
                "code",
                """
                try {
                  predict("park", "until stopped");
                } catch (RuntimeException stopped) {
                  new Thread(monitor::clear).start();
                }
                """,
                "timeoutMs",
                TIMEOUT_MS,
                "captureBindings",
                false)));
    var parked = env.nextRequest();

    assertUnstoppable(responseResult(env.nextMessage()));
    assertEquals(3, Await.value("the sandbox's exit", env.exitStatus()));
    bootstrap.dispatch(new RpcMessage.Response(parked.id(), Map.of("output", "released")));
    releaseMonitor(holding);
  }

  /**
   * Any other request exiting the sandbox, a concurrent one rejected while the unstoppable execute
   * is still answering included, could end the process before that execute's response is sent.
   */
  @Test
  void onlyTheExecuteThatFoundTheSnippetUnstoppableExits() {
    useShortStopGrace();
    var holding = holdMonitorOutsideTheSnippetGroup();
    assertUnstoppable(
        bootstrap.handleExecute(
            Map.of("code", "synchronized (monitor) { }", "timeoutMs", TIMEOUT_MS)));

    Await.value(
        "another execute served",
        env.inSandbox(
            () -> {
              bootstrap.serveExecute(
                  new RpcMessage.Request(
                      "3", "execute", Map.of("code", "1 + 1", "timeoutMs", BEYOND_HANG_GUARD_MS)));
              return null;
            }));

    assertEquals(0, responseResult(env.nextMessage()).get("exitCode"));
    assertFalse(env.exitStatus().isDone());
    releaseMonitor(holding);
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

    bootstrap.handleExecute(Map.of("code", LOOP_FOREVER, "timeoutMs", TIMEOUT_MS));

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

  private void useShortStopGrace() {
    env.close();
    env = new BootstrapEnvironment(SHORT_STOP_GRACE);
    bootstrap = env.bootstrap();
  }

  /**
   * The holder is a virtual thread, never a member of a snippet's thread group, and it holds the
   * monitor until the host call the returned request stands for is answered. The monitor is a
   * {@code Vector} so a thread can block on it running JDK code alone, where no stop check is
   * woven; the bindings snapshot is off because the vector's {@code toString} needs it too.
   */
  private RpcMessage.Request holdMonitorOutsideTheSnippetGroup() {
    bootstrap.handleExecute(
        Map.of(
            "code",
            """
            var monitor = new java.util.Vector<Object>();
            Thread.startVirtualThread(() -> { synchronized (monitor) { predict("hold", "monitor"); } });
            """,
            "timeoutMs",
            BEYOND_HANG_GUARD_MS,
            "captureBindings",
            false));
    return env.nextRequest();
  }

  /** Lets every snippet thread blocked on the monitor end, so none is left for the next test. */
  private void releaseMonitor(RpcMessage.Request holding) {
    var stuck = liveSnippetThreads();
    bootstrap.dispatch(new RpcMessage.Response(holding.id(), Map.of("output", "released")));
    stuck.forEach(thread -> Await.termination("a snippet thread let into the monitor", thread));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> responseResult(RpcMessage message) {
    return (Map<String, Object>) assertInstanceOf(RpcMessage.Response.class, message).result();
  }

  private static void assertUnstoppable(Map<String, Object> result) {
    assertTimedOut(result);
    assertTrue(
        ((String) result.get("stderr")).contains("could not be stopped"),
        "stderr was: " + result.get("stderr"));
  }

  private static void assertTimedOut(Map<String, Object> result) {
    assertEquals(1, result.get("exitCode"));
    assertTrue(
        ((String) result.get("stderr")).contains("Execution timed out"),
        "stderr was: " + result.get("stderr"));
  }

  /**
   * Enumerates rather than taking {@link Thread#getAllStackTraces()}, which leaves out a thread
   * that has been started but has not run yet.
   */
  private static List<Thread> liveSnippetThreads() {
    var root = Thread.currentThread().getThreadGroup();
    while (root.getParent() != null) {
      root = root.getParent();
    }
    Thread[] live;
    int count;
    do {
      live = new Thread[root.activeCount() + 1];
      count = root.enumerate(live);
    } while (count == live.length);
    return Arrays.stream(live, 0, count)
        .filter(
            thread -> {
              var group = thread.getThreadGroup();
              return group != null && SNIPPET_THREAD_GROUP.equals(group.getName());
            })
        .toList();
  }
}
