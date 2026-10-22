/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.standardapplied.helios.session.test.LogCapture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.LogRecord;
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

  /**
   * A parameter named {@code com} hides the {@code com} package the wrapper body names, so the
   * sandbox rejects the wrappers after the RPC channel is open. Closing the channel before its
   * socket is what keeps its reader from logging the closed socket: the channel is closed once
   * {@code create} throws, and its reader logs only while it is open.
   */
  @Test
  void aFailedLaunchClosesItsChannelAndLeavesNothingBehind() {
    var registry = new HostFunctionRegistry();
    registry.register(
        new HostFunction(
            "lookup",
            "Looks a key up.",
            List.of(HostParameter.required("com", ParameterType.STRING, "Hides a package.")),
            params -> null));
    var earlierChildren = livePids();
    var launched = new CompletableFuture<Launch>();
    var logged = new CopyOnWriteArrayList<LogRecord>();
    try (var ignored = LogCapture.of(RpcChannel.class, logged)) {
      var thrown =
          assertThrows(
              ReplException.class,
              () ->
                  JvmSandbox.create(
                      CONFIG, registry, rpc -> launched.complete(Launch.of(rpc, earlierChildren))));

      assertTrue(
          thrown.getMessage().startsWith("Sandbox rejected custom host-function wrappers: ["),
          thrown.getMessage());
      var launch = Await.value("the accepted connection", launched);
      var closed =
          assertThrows(RpcChannel.RpcException.class, () -> launch.channel().call("ping", null));
      assertEquals("Channel is closed", closed.getMessage());
      var messages = logged.stream().map(LogRecord::getMessage).toList();
      assertFalse(messages.contains("Reader loop error"), messages::toString);
      assertTrue(
          launch.directories().stream().noneMatch(Files::exists), launch.directories()::toString);
      Await.until(
          "the rejected sandbox's subprocess to end",
          () -> earlierChildren.containsAll(livePids()));
    }
  }

  /**
   * A launch as its RPC connection was accepted: its channel, and the socket and working
   * directories it made, read from the socket's address and from the new subprocess's working
   * directory. Read from the launch itself, they are unaffected by other sandboxes on the host.
   */
  private record Launch(RpcChannel channel, List<Path> directories) {

    static Launch of(SandboxRpc rpc, Set<Long> earlierChildren) {
      try {
        var socket = (UnixDomainSocketAddress) rpc.socket().getLocalAddress();
        var child =
            ProcessHandle.current()
                .children()
                .filter(handle -> !earlierChildren.contains(handle.pid()))
                .findFirst()
                .orElseThrow();
        var working = Files.readSymbolicLink(Path.of("/proc", Long.toString(child.pid()), "cwd"));
        return new Launch(rpc.channel(), List.of(socket.getPath().getParent(), working));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  private static Set<Long> livePids() {
    return ProcessHandle.current()
        .children()
        .filter(ProcessHandle::isAlive)
        .map(ProcessHandle::pid)
        .collect(Collectors.toSet());
  }
}
