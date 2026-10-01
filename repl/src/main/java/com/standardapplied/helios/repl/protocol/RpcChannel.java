/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.protocol;

import ai.singlr.repl.host.HostFunctionRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bidirectional JSON-RPC 2.0 dispatcher. Reads messages from a transport on a virtual thread,
 * dispatches incoming requests to a {@link HostFunctionRegistry}, and correlates outbound
 * request/response pairs via {@link CompletableFuture}.
 */
public final class RpcChannel implements AutoCloseable {

  private static final Logger LOG = Logger.getLogger(RpcChannel.class.getName());

  private final RpcTransport transport;
  private final HostFunctionRegistry registry;
  private final Duration callTimeout;
  private final AtomicLong idCounter = new AtomicLong(0);
  private final ConcurrentHashMap<String, CompletableFuture<Object>> pendingCalls =
      new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Thread readerThread;

  /**
   * Per-channel executor that dispatches incoming requests to virtual-thread handlers. Owning the
   * executor — rather than spawning unmanaged {@code Thread.ofVirtual().start()} handlers — gives
   * three properties hand-rolled tracking does not:
   *
   * <ol>
   *   <li><b>No spawn/register race.</b> {@code submit()} registers the task with the executor
   *       before returning, so {@link ExecutorService#shutdownNow()} sees every in-flight task —
   *       even ones whose virtual thread hasn't begun executing yet.
   *   <li><b>Built-in en-masse interrupt.</b> {@code shutdownNow()} interrupts every running task
   *       in one call.
   *   <li><b>Submission rejection after shutdown.</b> A late dispatch from the reader (e.g., a
   *       message decoded just as {@link #close()} fires) gets a {@link RejectedExecutionException}
   *       we can downgrade to {@code FINE} instead of leaking a handler.
   * </ol>
   */
  private final ExecutorService handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Create a channel and start the reader loop.
   *
   * @param transport the underlying transport
   * @param registry the host function registry for incoming requests
   * @param callTimeout timeout for outbound calls waiting for a response
   */
  public RpcChannel(RpcTransport transport, HostFunctionRegistry registry, Duration callTimeout) {
    if (transport == null) {
      throw new IllegalArgumentException("Transport must not be null");
    }
    if (registry == null) {
      throw new IllegalArgumentException("Registry must not be null");
    }
    if (callTimeout == null) {
      throw new IllegalArgumentException("Call timeout must not be null");
    }
    this.transport = transport;
    this.registry = registry;
    this.callTimeout = callTimeout;
    this.readerThread = Thread.ofVirtual().name("rpc-reader").start(this::readLoop);
  }

  /**
   * Send a request and wait for the response.
   *
   * @param method the method to call
   * @param params the parameters
   * @return the result from the remote side
   * @throws RpcException if the call fails, times out, or is interrupted
   */
  public Object call(String method, Object params) {
    if (closed.get()) {
      throw new RpcException("Channel is closed");
    }
    var id = String.valueOf(idCounter.incrementAndGet());
    var future = new CompletableFuture<Object>();
    pendingCalls.put(id, future);
    try {
      transport.send(new RpcMessage.Request(id, method, params));
      return future.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (IOException e) {
      pendingCalls.remove(id);
      throw new RpcException("Failed to send request", e);
    } catch (TimeoutException e) {
      pendingCalls.remove(id);
      throw new RpcException("Call timed out after " + callTimeout, e);
    } catch (InterruptedException e) {
      pendingCalls.remove(id);
      Thread.currentThread().interrupt();
      throw new RpcException("Call interrupted", e);
    } catch (ExecutionException e) {
      pendingCalls.remove(id);
      if (e.getCause() instanceof RpcException rpc) {
        throw rpc;
      }
      throw new RpcException("Call failed", e.getCause());
    }
  }

  /**
   * Send a one-way notification.
   *
   * @param method the notification method
   * @param params the parameters
   */
  public void notify(String method, Object params) {
    if (closed.get()) {
      throw new RpcException("Channel is closed");
    }
    try {
      transport.send(new RpcMessage.Notification(method, params));
    } catch (IOException e) {
      throw new RpcException("Failed to send notification", e);
    }
  }

  /** Whether the channel is still active. */
  public boolean isActive() {
    return !closed.get() && transport.isOpen();
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      readerThread.interrupt();
      // shutdownNow() interrupts every in-flight handler and rejects new submissions. We do not
      // await termination — handlers may still be unwinding their finally blocks (those go
      // through safeSend, which sees closed=true and silently drops).
      handlerExecutor.shutdownNow();
      pendingCalls.forEach(
          (id, future) -> future.completeExceptionally(new RpcException("Channel closed")));
      pendingCalls.clear();
      try {
        transport.close();
      } catch (IOException e) {
        LOG.log(Level.FINE, "Error closing transport", e);
      }
    }
  }

  private void readLoop() {
    try {
      while (!closed.get() && transport.isOpen()) {
        var message = transport.receive();
        if (message == null) {
          break;
        }
        dispatch(message);
      }
    } catch (IOException e) {
      if (!closed.get()) {
        LOG.log(Level.WARNING, "Reader loop error", e);
      }
    } finally {
      closed.set(true);
      pendingCalls.forEach(
          (id, future) -> future.completeExceptionally(new RpcException("Channel closed")));
      pendingCalls.clear();
    }
  }

  @SuppressWarnings("unchecked")
  private void dispatch(RpcMessage message) {
    switch (message) {
      case RpcMessage.Request req -> dispatchRequest(req);
      case RpcMessage.Response resp -> {
        var future = pendingCalls.remove(resp.id());
        if (future != null) {
          future.complete(resp.result());
        }
      }
      case RpcMessage.ErrorResponse err -> {
        var future = err.id() != null ? pendingCalls.remove(err.id()) : null;
        if (future != null) {
          future.completeExceptionally(
              new RpcException(
                  "Remote error [" + err.error().code() + "]: " + err.error().message()));
        }
      }
      case RpcMessage.Notification notif ->
          LOG.log(Level.FINE, "Received notification: {0}", notif.method());
    }
  }

  /**
   * Submit {@code req} to the handler executor. Package-private so tests can deterministically
   * exercise the {@link RejectedExecutionException} branch (a request submitted after {@link
   * #close()} shut the executor down) without engineering a scheduling race.
   */
  void dispatchRequest(RpcMessage.Request req) {
    try {
      handlerExecutor.execute(
          () -> {
            // Preserve helpful diagnostic naming on the virtual thread for thread dumps.
            Thread.currentThread().setName("rpc-handler-" + req.method());
            handleRequest(req);
          });
    } catch (RejectedExecutionException e) {
      // close() ran between the reader's loop check and our submit. Benign — the handler is
      // exactly the kind of work close() was tearing down. Log at FINE so this stays visible
      // under diagnostic logging.
      LOG.log(Level.FINE, "Handler rejected (channel closed)", e);
    }
  }

  /**
   * Run a request through the registry and send the response. Package-private so the close-race
   * early-exit branch (line above the {@code try}) can be exercised deterministically by tests
   * without needing to engineer a virtual-thread scheduling race.
   */
  @SuppressWarnings("unchecked")
  void handleRequest(RpcMessage.Request req) {
    if (closed.get() || !transport.isOpen()) {
      return;
    }
    try {
      var function = registry.get(req.method());
      if (function == null) {
        safeSend(new RpcMessage.ErrorResponse(req.id(), RpcError.methodNotFound(req.method())));
        return;
      }
      var params =
          req.params() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
      var result = function.handler().handle(params);
      safeSend(new RpcMessage.Response(req.id(), result));
    } catch (Exception e) {
      safeSend(new RpcMessage.ErrorResponse(req.id(), RpcError.internalError(e.getMessage())));
    }
  }

  /**
   * Send a handler-originated response, downgrading the noise of close-race failures.
   *
   * <p>An in-flight handler virtual thread may attempt to write a response after {@link #close()}
   * has torn down the transport — a benign race, not a protocol failure. This method pre-checks
   * {@link #closed} and {@link RpcTransport#isOpen()} and, if either fails, drops the send at
   * {@code FINE} instead of crashing into a {@code WARNING}. The post-write {@link IOException} is
   * handled the same way: closed-state → {@code FINE}, otherwise → {@code WARNING} (true send
   * failures still surface). Channel-initiated calls go through {@link #call} / {@link #notify}
   * instead and keep their original failure semantics.
   *
   * <p>A {@link RuntimeException} from a misbehaving transport is also caught and logged at {@code
   * WARNING}: handler virtual threads die silently otherwise (no default uncaught-exception logging
   * in many configurations), so an upstream bug would become invisible.
   */
  private void safeSend(RpcMessage message) {
    if (closed.get() || !transport.isOpen()) {
      LOG.log(Level.FINE, () -> "Skipping response send on closed channel");
      return;
    }
    try {
      transport.send(message);
    } catch (IOException e) {
      if (closed.get() || !transport.isOpen()) {
        LOG.log(Level.FINE, "send-after-close race ignored", e);
      } else {
        LOG.log(Level.WARNING, "Failed to send response", e);
      }
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Unexpected runtime error sending response", e);
    }
  }

  /** Runtime exception for RPC channel errors. */
  public static final class RpcException extends RuntimeException {
    public RpcException(String message) {
      super(message);
    }

    public RpcException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
