/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.repl.ReplException;
import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import com.standardapplied.helios.repl.host.HostParameter;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcChannel;
import com.standardapplied.helios.repl.protocol.RpcError;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What the host makes of the sandbox's answer to installing the wrappers for the registry's custom
 * host functions: a failed call and a rejection each fail with their own message, an acceptance
 * installs them, and a launch whose wrappers the sandbox rejects leaves no subprocess behind.
 */
class SandboxPreludeInstallTest {

  private static final String SNIPPET = "static Object lookup() { return null; }";

  private static final JvmSandboxConfig CONFIG =
      JvmSandboxConfig.newBuilder()
          .withSubprocessStartupTimeout(Await.HANG_GUARD)
          .withCallTimeout(Await.HANG_GUARD)
          .build();

  @AfterEach
  void leaveNoProcessBehind() {
    ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
  }

  @Test
  void theSnippetTravelsAsTheInstallPreludeCall() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(Map.of("success", true));

      assertDoesNotThrow(() -> fake.sandbox().rpc().installPrelude(SNIPPET));

      var request =
          (RpcMessage.Request) ProcessTransport.deserializeMessage(fake.answeredRequest());
      assertEquals("installPrelude", request.method());
      assertEquals(Map.of("snippet", SNIPPET), request.params());
    }
  }

  @Test
  void anAnswerThatIsNotAMapInstallsTheWrappers() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext("installed");

      assertDoesNotThrow(() -> fake.sandbox().rpc().installPrelude(SNIPPET));
    }
  }

  @Test
  void aFailedCallNamesTheRemoteError() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNextWith(
          id -> new RpcMessage.ErrorResponse(id, RpcError.internalError("jshell is gone")));

      var thrown =
          assertThrows(ReplException.class, () -> fake.sandbox().rpc().installPrelude(SNIPPET));

      assertEquals(
          "Failed to install custom host-function wrappers in sandbox:"
              + " Remote error [-32603]: jshell is gone",
          thrown.getMessage());
      assertInstanceOf(RpcChannel.RpcException.class, thrown.getCause());
    }
  }

  @Test
  void aRejectionListsTheSandboxsErrors() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(Map.of("success", false, "errors", List.of("cannot find symbol")));

      var thrown =
          assertThrows(ReplException.class, () -> fake.sandbox().rpc().installPrelude(SNIPPET));

      assertEquals(
          "Sandbox rejected custom host-function wrappers: [cannot find symbol]",
          thrown.getMessage());
    }
  }

  @Test
  void aRejectionWithoutAnErrorListReportsAnEmptyOne() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(Map.of("success", false, "errors", "not a list"));

      var thrown =
          assertThrows(ReplException.class, () -> fake.sandbox().rpc().installPrelude(SNIPPET));

      assertEquals("Sandbox rejected custom host-function wrappers: []", thrown.getMessage());
    }
  }

  @Test
  void aLaunchWhoseWrappersAreRejectedFailsAndLeavesNoSubprocess() {
    var registry = new HostFunctionRegistry();
    registry.register(
        new HostFunction(
            "lookup",
            "Looks a key up.",
            List.of(HostParameter.required("int", ParameterType.STRING, "Named after a keyword.")),
            params -> null));
    var earlierChildren = livePids();

    var thrown = assertThrows(ReplException.class, () -> JvmSandbox.create(CONFIG, registry));

    assertTrue(
        thrown.getMessage().startsWith("Sandbox rejected custom host-function wrappers: ["),
        thrown.getMessage());
    Await.until(
        "the rejected sandbox's subprocess to end", () -> earlierChildren.containsAll(livePids()));
  }

  private static Set<Long> livePids() {
    return ProcessHandle.current()
        .children()
        .filter(ProcessHandle::isAlive)
        .map(ProcessHandle::pid)
        .collect(Collectors.toSet());
  }
}
