/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.test.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The exact frame the sandbox bootstrap writes back for each line the host can send: a request for
 * each method it serves, a request for a method it does not, a line that is not JSON-RPC, and the
 * messages it answers with nothing. A message that gets no answer is followed by one that does,
 * whose answer is the next line written.
 */
class BootstrapRepliesTest {

  private static final long BEYOND_HANG_GUARD_MS = Await.HANG_GUARD.multipliedBy(5).toMillis();

  private static final String PREFIX = "\0RPC:";

  private BootstrapEnvironment env;

  @BeforeEach
  void setUp() {
    env = BootstrapEnvironment.reading();
  }

  @AfterEach
  void tearDown() {
    env.close();
  }

  @Test
  void executeIsAnsweredWithItsOutputExitCodeAndBindings() {
    env.feedLine(
        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"execute\",\"params\":{\"code\":\"var x = 6"
            + " * 7; System.out.println(x);\",\"timeoutMs\":"
            + BEYOND_HANG_GUARD_MS
            + "}}");

    assertEquals(
        PREFIX
            + "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"stdout\":\"42\\n\",\"stderr\":\"\","
            + "\"exitCode\":0,\"bindings\":{\"x\":\"42\"}}}",
        env.nextLine());
  }

  @Test
  void executeThatFailsToCompileIsAnsweredWithTheDiagnosticAndExitCodeOne() {
    env.feedLine(
        "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"execute\",\"params\":{\"code\":\"int y ="
            + " \\\"text\\\";\",\"timeoutMs\":"
            + BEYOND_HANG_GUARD_MS
            + ",\"captureBindings\":false}}");

    assertEquals(
        PREFIX
            + "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"result\":{\"stdout\":\"\",\"stderr\":"
            + "\"incompatible types: java.lang.String cannot be converted to int\\n\","
            + "\"exitCode\":1}}",
        env.nextLine());
  }

  @Test
  void installPreludeIsAnsweredWithSuccess() {
    env.feedLine(
        "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"method\":\"installPrelude\",\"params\":{\"snippet\":"
            + "\"int fromPrelude = 7;\"}}");

    assertEquals(
        PREFIX + "{\"jsonrpc\":\"2.0\",\"id\":\"3\",\"result\":{\"success\":true}}",
        env.nextLine());
  }

  @Test
  void installPreludeThatIsRejectedIsAnsweredWithItsErrors() {
    env.feedLine(
        "{\"jsonrpc\":\"2.0\",\"id\":\"4\",\"method\":\"installPrelude\",\"params\":{\"snippet\":"
            + "\"int broken = \\\"no\\\";\"}}");

    assertEquals(
        PREFIX
            + "{\"jsonrpc\":\"2.0\",\"id\":\"4\",\"result\":{\"success\":false,\"errors\":"
            + "[\"incompatible types: java.lang.String cannot be converted to int\"]}}",
        env.nextLine());
  }

  @Test
  void unknownMethodIsAnsweredWithMethodNotFound() {
    env.feedLine("{\"jsonrpc\":\"2.0\",\"id\":\"5\",\"method\":\"frobnicate\",\"params\":{}}");

    assertEquals(
        PREFIX
            + "{\"jsonrpc\":\"2.0\",\"id\":\"5\",\"error\":{\"code\":-32601,\"message\":"
            + "\"Method not found: frobnicate\"}}",
        env.nextLine());
  }

  @Test
  void malformedLineIsAnsweredWithAParseErrorWithoutAnId() {
    env.feedLine("{not json");

    assertEquals(
        PREFIX
            + "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":"
            + "\"Malformed JSON-RPC message: Unexpected character ('n' (code 110)): was expecting"
            + " double-quote to start property name\\n"
            + " at [Source: REDACTED (`StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` disabled);"
            + " byte offset: #UNKNOWN]\"}}",
        env.nextLine());
  }

  @Test
  void jsonThatIsNoJsonRpcMessageIsAnsweredWithAParseError() {
    env.feedLine("{\"jsonrpc\":\"2.0\"}");

    assertEquals(
        PREFIX
            + "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":"
            + "\"Unrecognized JSON-RPC message: {\\\"jsonrpc\\\":\\\"2.0\\\"}\"}}",
        env.nextLine());
  }

  @Test
  void notificationsAndUnmatchedAnswersGetNoReply() {
    env.feedLine("{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{}}");
    env.feedLine("{\"jsonrpc\":\"2.0\",\"id\":\"sub-99\",\"result\":{}}");
    env.feedLine(
        "{\"jsonrpc\":\"2.0\",\"id\":\"sub-98\",\"error\":{\"code\":-32603,\"message\":\"x\"}}");
    env.feedLine("{\"jsonrpc\":\"2.0\",\"id\":\"6\",\"method\":\"frobnicate\"}");

    assertEquals(
        PREFIX
            + "{\"jsonrpc\":\"2.0\",\"id\":\"6\",\"error\":{\"code\":-32601,\"message\":"
            + "\"Method not found: frobnicate\"}}",
        env.nextLine());
  }
}
