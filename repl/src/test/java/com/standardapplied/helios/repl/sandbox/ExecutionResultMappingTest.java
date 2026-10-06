/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * timeout and a policy denial, with the truncation markers of a capped bindings snapshot; and of
 * the request it sends.
 */
class ExecutionResultMappingTest {

  private static final String CODE = "var big = \"x\".repeat(500); big.length()";

  @Test
  void executeRequestCarriesTheCodeTimeoutAndBindingLimits() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(reply("", "", 0, null, Map.of()));

      fake.sandbox()
          .execute(
              ExecutionRequest.newBuilder()
                  .withCode(CODE)
                  .withTimeout(Duration.ofMillis(1500))
                  .build(),
              new ExecuteParams(false, 64, 2048));

      var request =
          (RpcMessage.Request) ProcessTransport.deserializeMessage(fake.answeredRequest());
      assertEquals("1", request.id());
      assertEquals("execute", request.method());
      var params = (Map<?, ?>) request.params();
      assertEquals(
          List.of(
              "code",
              "language",
              "timeoutMs",
              "captureBindings",
              "maxBindingValueChars",
              "maxBindingSnapshotChars"),
          List.copyOf(params.keySet()));
      assertEquals(
          Map.of(
              "code",
              CODE,
              "language",
              "java",
              "timeoutMs",
              1500,
              "captureBindings",
              false,
              "maxBindingValueChars",
              64,
              "maxBindingSnapshotChars",
              2048),
          params);
    }
  }

  @Test
  void valueWithTruncatedBindings() throws Exception {
    var bindings = new LinkedHashMap<String, Object>();
    bindings.put("big", "xxxxxxxx... (len=500)");
    bindings.put("$1", "500");
    bindings.put("__truncated__", "(snapshot exceeded 16384 chars; remaining vars dropped)");

    var result = execute(reply("500\n", "", 0, null, bindings));

    assertEquals(
        new ExecutionResult(
            CODE,
            "500\n",
            "",
            0,
            null,
            Map.of(
                "big", "xxxxxxxx... (len=500)",
                "$1", "500",
                "__truncated__", "(snapshot exceeded 16384 chars; remaining vars dropped)"),
            Duration.ZERO),
        result);
  }

  @Test
  void thrownException() throws Exception {
    var stderr = "java.lang.IllegalStateException: boom\n\tat .(#1:1)\n";

    var result = execute(reply("", stderr, 1, null, Map.of()));

    assertEquals(new ExecutionResult(CODE, "", stderr, 1, null, Map.of(), Duration.ZERO), result);
  }

  @Test
  void compileError() throws Exception {
    var stderr = "incompatible types: java.lang.String cannot be converted to int\n";

    var result = execute(reply("", stderr, 1, null, Map.of("ok", "1")));

    assertEquals(
        new ExecutionResult(CODE, "", stderr, 1, null, Map.of("ok", "1"), Duration.ZERO), result);
  }

  @Test
  void timeoutWithOutputBeforeIt() throws Exception {
    var result =
        execute(reply("started\n", "Execution timed out\n", 1, null, Map.of()), "captured line");

    assertEquals(
        new ExecutionResult(
            CODE,
            "captured line\nstarted\n",
            "Execution timed out\n",
            1,
            null,
            Map.of(),
            Duration.ZERO),
        result);
  }

  @Test
  void policyDenial() throws Exception {
    var stderr =
        "SandboxPolicyException: denied java/lang/ProcessBuilder.<init>"
            + " (rule=deniedClasses:java.lang.ProcessBuilder)\n";

    var result = execute(reply("", stderr, 0, null, Map.of()));

    assertEquals(new ExecutionResult(CODE, "", stderr, 0, null, Map.of(), Duration.ZERO), result);
  }

  @Test
  void submittedValueAndBindingValuesThatAreNotStrings() throws Exception {
    var bindings = new LinkedHashMap<String, Object>();
    bindings.put("n", 5);
    bindings.put("nothing", null);
    bindings.put("list", List.of(1, 2));

    var result = execute(reply("", "", 0, Map.of("answer", 42), bindings));

    assertEquals(
        new ExecutionResult(
            CODE,
            "",
            "",
            0,
            Map.of("answer", 42),
            Map.of("n", "5", "nothing", "null", "list", "[1, 2]"),
            Duration.ZERO),
        result);
  }

  @Test
  void replyWithoutTheBootstrapsFields() throws Exception {
    var result = execute(Map.of("unexpected", true));

    assertEquals(new ExecutionResult(CODE, "", "", 0, null, Map.of(), Duration.ZERO), result);
  }

  @Test
  void replyThatIsNotAMap() throws Exception {
    assertEquals(
        new ExecutionResult(CODE, "[1, 2]", "", 0, null, Map.of(), Duration.ZERO),
        execute(List.of(1, 2)));
    assertEquals(
        new ExecutionResult(CODE, "null", "", 0, null, Map.of(), Duration.ZERO), execute(null));
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
              CODE,
              "partial output",
              "Remote error [-32603]: eval crashed",
              1,
              null,
              Map.of(),
              null),
          result);
    }
  }

  private static ExecutionResult execute(Object reply, String... plainOutput) throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(reply, plainOutput);
      return fake.sandbox().execute(ExecutionRequest.java(CODE));
    }
  }

  private static Map<String, Object> reply(
      String stdout, String stderr, int exitCode, Object submitted, Map<String, ?> bindings) {
    var reply = new LinkedHashMap<String, Object>();
    reply.put("stdout", stdout);
    reply.put("stderr", stderr);
    reply.put("exitCode", exitCode);
    reply.put("submitted", submitted);
    reply.put("bindings", bindings);
    return reply;
  }
}
