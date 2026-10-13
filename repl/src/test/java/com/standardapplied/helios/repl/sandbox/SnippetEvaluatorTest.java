/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What an execute and a prelude install answer when the snippet or its parameters leave the usual
 * path: a prelude that is blank, incomplete or throws, an engine that fails outside the snippet,
 * and an execute interrupted while it waits for its snippet.
 */
class SnippetEvaluatorTest {

  private static final long BEYOND_HANG_GUARD_MS = Await.HANG_GUARD.multipliedBy(5).toMillis();

  private BootstrapEnvironment env;

  @BeforeEach
  void setUp() {
    env = new BootstrapEnvironment();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void preludeWithoutSourceSucceeds() {
    assertEquals(Map.of("success", true), env.evaluator().handleInstallPrelude(Map.of()));
    assertEquals(
        Map.of("success", true), env.evaluator().handleInstallPrelude(Map.of("snippet", "  ")));
  }

  @Test
  void incompletePreludeIsReported() {
    var result = env.evaluator().handleInstallPrelude(Map.of("snippet", "int x = "));

    assertEquals(
        Map.of("success", false, "errors", List.of("Incomplete snippet at: null")), result);
  }

  @Test
  void preludeThatThrowsIsReported() {
    var result = env.evaluator().handleInstallPrelude(Map.of("snippet", "int boom = 1 / 0;"));

    assertEquals(
        Map.of("success", false, "errors", List.of("jdk.jshell.EvalException: / by zero")), result);
  }

  @Test
  void engineThatFailsOutsideTheSnippetFailsTheExecute() {
    env.close();

    var result =
        env.evaluator().handleExecute(Map.of("code", "1 + 1", "timeoutMs", BEYOND_HANG_GUARD_MS));

    assertEquals(1, result.get("exitCode"));
    var stderr = (String) result.get("stderr");
    assertTrue(
        stderr.startsWith("java.lang.IllegalStateException: JShell (jdk.jshell.JShell@"), stderr);
    assertTrue(stderr.contains(") has been closed.\n\tat "), stderr);
  }

  /**
   * The snippet parks in a host call, so its eval thread is still running when the interrupted
   * execute stops waiting for it; the test lets it end before the next test.
   */
  @Test
  void executeInterruptedWhileWaitingForItsSnippetFails() {
    var evalThreadsBefore = evalThreads();
    var execute =
        env.inSandbox(
            () -> {
              Thread.currentThread().interrupt();
              var result =
                  env.evaluator()
                      .handleExecute(
                          Map.of(
                              "code",
                              "__call(\"hold\", null);",
                              "timeoutMs",
                              BEYOND_HANG_GUARD_MS));
              return Map.entry(result.get("exitCode"), Thread.currentThread().isInterrupted());
            });

    assertEquals(Map.entry(1, true), Await.value("the interrupted execute", execute));
    var started = evalThreads();
    started.removeAll(evalThreadsBefore);
    env.rpc().dispatch(new RpcMessage.Response(env.nextRequest().id(), Map.of("output", "done")));
    started.forEach(thread -> Await.termination("the released eval thread", thread));
  }

  private static Set<Thread> evalThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(thread -> thread.getName().equals("jshell-eval"))
        .collect(Collectors.toSet());
  }
}
