/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcError;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntConsumer;

/**
 * The sandbox bootstrap's read loop: reads each message the host sends over the RPC socket and
 * dispatches it. A request is served on a virtual thread of its own, so the loop goes on routing
 * the host's answers to the calls a running snippet makes; a line that is not JSON-RPC is answered
 * with a parse error, a request for a method the bootstrap does not serve with method-not-found.
 */
final class BootstrapRpc {

  private final BufferedReader stdinReader;
  private final HostBridgeState bridge;
  private final SnippetEvaluator evaluator;
  private final IntConsumer exit;

  /**
   * @param stdinReader the RPC socket's input
   * @param bridge the RPC socket's output and the host calls awaiting an answer
   * @param evaluator serves {@code execute} and {@code installPrelude}
   * @param exit terminates the sandbox JVM with the given status once a snippet proved unstoppable,
   *     without running shutdown hooks: a hook the snippet registered could block the exit and
   *     leave the sandbox serving requests after it said it was shutting down
   */
  BootstrapRpc(
      BufferedReader stdinReader,
      HostBridgeState bridge,
      SnippetEvaluator evaluator,
      IntConsumer exit) {
    this.stdinReader = stdinReader;
    this.bridge = bridge;
    this.evaluator = evaluator;
    this.exit = exit;
  }

  /** Read and dispatch messages until the host closes the socket. */
  void readLoop() {
    try {
      String line;
      while ((line = stdinReader.readLine()) != null) {
        RpcMessage message;
        try {
          message = ProcessTransport.deserializeMessage(line);
        } catch (Exception e) {
          try {
            bridge.sendRpc(
                new RpcMessage.ErrorResponse(
                    null, RpcError.of(RpcError.PARSE_ERROR, e.getMessage())));
          } catch (IOException sendErr) {
          }
          continue;
        }
        dispatch(message);
      }
    } catch (IOException e) {
    } finally {
      bridge.abandonPendingCalls();
    }
  }

  void dispatch(RpcMessage message) {
    switch (message) {
      case RpcMessage.Request req -> {
        switch (req.method()) {
          case "execute" ->
              Thread.ofVirtual().name("jshell-execute").start(() -> serveExecute(req));
          case "installPrelude" ->
              Thread.ofVirtual()
                  .name("jshell-install-prelude")
                  .start(() -> respond(req, evaluator::handleInstallPrelude));
          default -> {
            try {
              bridge.sendRpc(
                  new RpcMessage.ErrorResponse(req.id(), RpcError.methodNotFound(req.method())));
            } catch (IOException e) {
            }
          }
        }
      }
      case RpcMessage.Response resp -> bridge.answer(resp);
      case RpcMessage.ErrorResponse err -> bridge.answer(err);
      case RpcMessage.Notification _ -> {}
    }
  }

  /**
   * A snippet that outlived {@link jdk.jshell.JShell#stop()} still holds the captured system
   * streams and runs on, so no later execute can be trusted. Only the execute that found the
   * snippet unstoppable exits, and only after its own response has been sent, so the host learns
   * why the sandbox is going away before the process ends; any other request exiting could beat
   * that response.
   */
  void serveExecute(RpcMessage.Request req) {
    respond(req, evaluator::handleExecute);
    if (evaluator.isUnstoppableOn(Thread.currentThread())) {
      exit.accept(JvmSandboxBootstrap.UNSTOPPABLE_SNIPPET_EXIT_CODE);
    }
  }

  @SuppressWarnings("unchecked")
  private void respond(
      RpcMessage.Request req, Function<Map<String, Object>, Map<String, Object>> handler) {
    try {
      var params =
          req.params() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
      bridge.sendRpc(new RpcMessage.Response(req.id(), handler.apply(params)));
    } catch (Exception e) {
      try {
        bridge.sendRpc(
            new RpcMessage.ErrorResponse(req.id(), RpcError.internalError(e.getMessage())));
      } catch (IOException sendErr) {
      }
    }
  }
}
