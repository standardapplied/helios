/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The sandbox's end of the RPC socket as {@link HostBridge} reaches it from inside a snippet: the
 * one installed instance, the frames written to the host, the calls to the host still awaiting an
 * answer, and the value the snippet submitted during the current execute.
 */
final class HostBridgeState {

  private static final long CALL_TIMEOUT_MS = 300_000;

  private static volatile HostBridgeState instance;

  private final PrintStream realOut;
  private final ConcurrentHashMap<String, CompletableFuture<Object>> pendingCallbacks =
      new ConcurrentHashMap<>();
  private final AtomicLong idCounter = new AtomicLong(0);
  private volatile Object submittedValue;

  /**
   * @param realOut the stream over the RPC socket
   */
  HostBridgeState(PrintStream realOut) {
    this.realOut = realOut;
  }

  /** The instance {@link HostBridge} calls into, or {@code null} outside a sandbox. */
  static HostBridgeState instance() {
    return instance;
  }

  static void setInstance(HostBridgeState state) {
    instance = state;
  }

  /**
   * Send a JSON-RPC message to the host over the dedicated RPC channel. The {@link
   * ProcessTransport#RPC_PREFIX} magic prefix is required, not decorative: the host-side {@link
   * ProcessTransport#receive} parser distinguishes RPC frames from incidental subprocess writes by
   * the prefix, and would drop unprefixed lines into its stdout buffer (which the host no longer
   * drains as a side channel since C1). The prefix is not strictly necessary on this dedicated
   * socket — both peers know every byte is RPC — but the parser's contract still requires it. Do
   * not remove it without changing {@code ProcessTransport.receive} in lockstep.
   */
  void sendRpc(RpcMessage message) throws IOException {
    var json = ProcessTransport.serializeMessage(message);
    synchronized (realOut) {
      realOut.print(ProcessTransport.RPC_PREFIX + json + "\n");
      realOut.flush();
    }
  }

  /** Call {@code method} on the host and wait for its answer. */
  Object callHost(String method, Map<String, Object> params) {
    var id = "sub-" + idCounter.incrementAndGet();
    var future = new CompletableFuture<Object>();
    pendingCallbacks.put(id, future);
    try {
      sendRpc(new RpcMessage.Request(id, method, params));
      return future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (IOException e) {
      throw new RuntimeException("Failed to send host call", e);
    } catch (TimeoutException e) {
      throw new RuntimeException("Host call timed out after " + CALL_TIMEOUT_MS + "ms", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Host call interrupted", e);
    } catch (ExecutionException e) {
      throw new RuntimeException("Host call failed: " + e.getCause().getMessage(), e.getCause());
    } finally {
      pendingCallbacks.remove(id);
    }
  }

  /** Complete the host call {@code response} answers; an answer to no pending call is ignored. */
  void answer(RpcMessage.Response response) {
    var future = pendingCallbacks.remove(response.id());
    if (future != null) {
      future.complete(response.result());
    }
  }

  /** Fail the host call {@code error} answers; an error for no pending call is ignored. */
  void answer(RpcMessage.ErrorResponse error) {
    var future = error.id() != null ? pendingCallbacks.remove(error.id()) : null;
    if (future != null) {
      future.completeExceptionally(
          new RuntimeException(
              "Host error [" + error.error().code() + "]: " + error.error().message()));
    }
  }

  /** Fail every host call still waiting: the host will not answer any more. */
  void abandonPendingCalls() {
    pendingCallbacks.forEach(
        (id, future) -> future.completeExceptionally(new RuntimeException("Sandbox stdin closed")));
    pendingCallbacks.clear();
  }

  void setSubmittedValue(Object value) {
    this.submittedValue = value;
  }

  Object submittedValue() {
    return submittedValue;
  }
}
