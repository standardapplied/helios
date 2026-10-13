/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcError;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@link ExecutionResult} the host makes of each reply the sandbox bootstrap sends to an
 * execute, in the shapes the bootstrap produces: a value, a thrown exception, a compile error, a
 * timeout and a policy denial; and of the request it sends.
 */
class ExecutionResultMappingTest {

  private static final String CODE = "var big = \"x\".repeat(500); big.length()";

  @Test
  void executeRequestCarriesTheCodeAndTimeout() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(reply("", "", 0));

      fake.sandbox()
          .execute(
              ExecutionRequest.newBuilder()
                  .withCode(CODE)
                  .withTimeout(Duration.ofMillis(1500))
                  .build());

      var request =
          (RpcMessage.Request) ProcessTransport.deserializeMessage(fake.answeredRequest());
      assertEquals("1", request.id());
      assertEquals("execute", request.method());
      var params = (Map<?, ?>) request.params();
      assertEquals(List.of("code", "language", "timeoutMs"), List.copyOf(params.keySet()));
      assertEquals(Map.of("code", CODE, "language", "java", "timeoutMs", 1500), params);
    }
  }

  @Test
  void value() throws Exception {
    var result = execute(reply("500\n", "", 0));

    assertEquals(new ExecutionResult("500\n", "", 0, false, Duration.ZERO), result);
  }

  @Test
  void thrownException() throws Exception {
    var stderr = "java.lang.IllegalStateException: boom\n\tat .(#1:1)\n";

    var result = execute(reply("", stderr, 1));

    assertEquals(new ExecutionResult("", stderr, 1, false, Duration.ZERO), result);
  }

  @Test
  void compileError() throws Exception {
    var stderr = "incompatible types: java.lang.String cannot be converted to int\n";

    var result = execute(reply("", stderr, 1));

    assertEquals(new ExecutionResult("", stderr, 1, false, Duration.ZERO), result);
  }

  @Test
  void timeoutWithOutputBeforeIt() throws Exception {
    var timedOut = reply("started\n", "Execution timed out\n", 1);
    timedOut.put("timedOut", true);

    var result = execute(timedOut, "captured line");

    assertEquals(
        new ExecutionResult(
            "captured line\nstarted\n", "Execution timed out\n", 1, true, Duration.ZERO),
        result);
  }

  @Test
  void timedOutIsTrueOnlyWhenTheReplySaysTrue() {
    assertTrue(timedOutOf(Map.of("timedOut", true)));
    assertFalse(timedOutOf(Map.of("timedOut", false)));
    assertFalse(timedOutOf(Map.of()));
    assertFalse(timedOutOf(Map.of("timedOut", "true")));
  }

  @Test
  void unansweredExecuteIsATimeoutWithTheCapturedOutput() {
    var result = ExecutionReplies.unanswered("partial output", Duration.ofMillis(3));

    assertEquals(
        new ExecutionResult(
            "partial output",
            "Sandbox did not answer the execute within PT0.003S; the sandbox is closed",
            1,
            true,
            Duration.ZERO),
        result);
  }

  @Test
  void policyDenial() throws Exception {
    var stderr =
        "SandboxPolicyException: denied java/lang/ProcessBuilder.<init>"
            + " (rule=deniedClasses:java.lang.ProcessBuilder)\n";

    var result = execute(reply("", stderr, 0));

    assertEquals(new ExecutionResult("", stderr, 0, false, Duration.ZERO), result);
  }

  @Test
  void replyWithoutTheBootstrapsFields() throws Exception {
    var result = execute(Map.of("unexpected", true));

    assertEquals(new ExecutionResult("", "", 0, false, Duration.ZERO), result);
  }

  @Test
  void replyThatIsNotAMap() throws Exception {
    assertEquals(
        new ExecutionResult("[1, 2]", "", 0, false, Duration.ZERO), execute(List.of(1, 2)));
    assertEquals(new ExecutionResult("null", "", 0, false, Duration.ZERO), execute(null));
  }

  @Test
  void errorReplyBecomesAFailureWithTheCapturedOutput() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNextWith(
          id -> new RpcMessage.ErrorResponse(id, RpcError.internalError("eval crashed")),
          "partial output");

      var result = fake.sandbox().execute(ExecutionRequest.java(CODE));

      assertEquals(
          new ExecutionResult(
              "partial output", "Remote error [-32603]: eval crashed", 1, false, null),
          result);
    }
  }

  private static boolean timedOutOf(Map<String, Object> reply) {
    return ExecutionReplies.toExecutionResult(reply, "").timedOut();
  }

  private static ExecutionResult execute(Object reply, String... plainOutput) throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(reply, plainOutput);
      return fake.sandbox().execute(ExecutionRequest.java(CODE));
    }
  }

  private static Map<String, Object> reply(String stdout, String stderr, int exitCode) {
    var reply = new LinkedHashMap<String, Object>();
    reply.put("stdout", stdout);
    reply.put("stderr", stderr);
    reply.put("exitCode", exitCode);
    return reply;
  }
}
