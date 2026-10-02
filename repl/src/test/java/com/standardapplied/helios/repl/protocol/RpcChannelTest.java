/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.host.HostFunctionHandler;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * No test depends on how long the channel takes. A call timeout is {@link #BEYOND_HANG_GUARD}
 * unless the timeout is what the test exercises. A test that asserts something was not sent or not
 * logged first waits for the event after which it can no longer happen: the answer to a request the
 * reader could only dispatch afterwards, the last record a handler logs, or the end of the reader
 * thread.
 */
class RpcChannelTest {

  private static final Duration BEYOND_HANG_GUARD = Await.HANG_GUARD.multipliedBy(5);

  private static final String PING = "ping";
  private static final Map<String, Object> PONG = Map.of("pong", true);

  private static final String SEND_SKIPPED = "Skipping response send on closed channel";
  private static final String SEND_RACE_IGNORED = "send-after-close race ignored";
  private static final String SEND_FAILED = "Failed to send response";

  @Test
  void nullTransportThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RpcChannel(null, new HostFunctionRegistry(), BEYOND_HANG_GUARD));
  }

  @Test
  void nullRegistryThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RpcChannel(new FakeTransport(), null, BEYOND_HANG_GUARD));
  }

  @Test
  void nullTimeoutThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RpcChannel(new FakeTransport(), new HostFunctionRegistry(), null));
  }

  @Test
  void callSendsRequestAndGetsResponse() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);
    transport.onSend(
        msg -> {
          if (msg instanceof RpcMessage.Request req) {
            transport.enqueueIncoming(new RpcMessage.Response(req.id(), Map.of("value", "ok")));
          }
        });

    var result = channel.call("test", Map.of("x", 1));

    assertEquals(Map.of("value", "ok"), result);
    channel.close();
  }

  @Test
  void callOnClosedChannelThrows() {
    var channel =
        new RpcChannel(new FakeTransport(), new HostFunctionRegistry(), BEYOND_HANG_GUARD);
    channel.close();

    assertThrows(RpcChannel.RpcException.class, () -> channel.call("test", null));
  }

  @Test
  void notifyOnClosedChannelThrows() {
    var channel =
        new RpcChannel(new FakeTransport(), new HostFunctionRegistry(), BEYOND_HANG_GUARD);
    channel.close();

    assertThrows(RpcChannel.RpcException.class, () -> channel.notify("test", null));
  }

  @Test
  void callTimesOut() {
    var channel =
        new RpcChannel(new FakeTransport(), new HostFunctionRegistry(), Duration.ofMillis(100));

    var ex = assertThrows(RpcChannel.RpcException.class, () -> channel.call("slow", null));

    assertTrue(ex.getMessage().contains("timed out"), ex.getMessage());
    channel.close();
  }

  @Test
  void incomingRequestDispatchesToRegistry() {
    var transport = new FakeTransport();
    var capturedParams = new AtomicReference<Map<String, Object>>();
    var registry =
        registryWith(
            "doWork",
            params -> {
              capturedParams.set(params);
              return Map.of("done", true);
            });
    var channel = new RpcChannel(transport, registry, BEYOND_HANG_GUARD);

    transport.enqueueIncoming(new RpcMessage.Request("r1", "doWork", Map.of("task", "analyze")));

    assertEquals(new RpcMessage.Response("r1", Map.of("done", true)), transport.nextSent());
    assertEquals("analyze", capturedParams.get().get("task"));
    channel.close();
  }

  @Test
  void incomingRequestForUnknownMethodSendsError() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);

    transport.enqueueIncoming(new RpcMessage.Request("r2", "unknown", null));

    var error = assertInstanceOf(RpcMessage.ErrorResponse.class, transport.nextSent());
    assertEquals("r2", error.id());
    assertEquals(RpcError.METHOD_NOT_FOUND, error.error().code());
    channel.close();
  }

  @Test
  void incomingRequestHandlerExceptionSendsInternalError() {
    var transport = new FakeTransport();
    var registry =
        registryWith(
            "fail",
            params -> {
              throw new RuntimeException("handler boom");
            });
    var channel = new RpcChannel(transport, registry, BEYOND_HANG_GUARD);

    transport.enqueueIncoming(new RpcMessage.Request("r3", "fail", Map.of()));

    var error = assertInstanceOf(RpcMessage.ErrorResponse.class, transport.nextSent());
    assertEquals("r3", error.id());
    assertEquals(RpcError.INTERNAL_ERROR, error.error().code());
    channel.close();
  }

  @Test
  void errorResponseCompletesCallExceptionally() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);
    transport.onSend(
        msg -> {
          if (msg instanceof RpcMessage.Request req) {
            transport.enqueueIncoming(
                new RpcMessage.ErrorResponse(req.id(), RpcError.of(-32603, "boom")));
          }
        });

    var ex = assertThrows(RpcChannel.RpcException.class, () -> channel.call("fail", null));

    assertTrue(ex.getMessage().contains("boom"));
    channel.close();
  }

  @Test
  void isActiveReflectsState() {
    var channel =
        new RpcChannel(new FakeTransport(), new HostFunctionRegistry(), BEYOND_HANG_GUARD);

    assertTrue(channel.isActive());
    channel.close();
    assertFalse(channel.isActive());
  }

  @Test
  void doubleCloseIsSafe() {
    var channel =
        new RpcChannel(new FakeTransport(), new HostFunctionRegistry(), BEYOND_HANG_GUARD);
    channel.close();
    channel.close();
    assertFalse(channel.isActive());
  }

  @Test
  void notifySendsNotification() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);

    channel.notify("progress", Map.of("pct", 75));

    assertEquals(
        new RpcMessage.Notification("progress", Map.of("pct", 75)),
        transport.sentMessages().poll());
    channel.close();
  }

  /**
   * Only the reader loop's exit can fail this call: nothing answers it, its timeout is beyond the
   * hang guard, and the channel is not closed until the call has failed.
   */
  @Test
  void transportCloseTerminatesReaderLoop() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);
    var pending = callThatIsNeverAnswered(channel);
    transport.nextSent();

    transport.close();

    var failure = Await.failure("the call the ended reader loop abandons", pending);
    assertInstanceOf(RpcChannel.RpcException.class, failure);
    assertEquals("Channel closed", failure.getMessage());
    assertFalse(channel.isActive());
    channel.close();
  }

  @Test
  void rpcExceptionWithMessage() {
    var ex = new RpcChannel.RpcException("test error");
    assertEquals("test error", ex.getMessage());
  }

  @Test
  void rpcExceptionWithCause() {
    var cause = new IOException("io fail");
    var ex = new RpcChannel.RpcException("wrapped", cause);
    assertEquals("wrapped", ex.getMessage());
    assertEquals(cause, ex.getCause());
  }

  @Test
  void callWithSendIoExceptionThrows() {
    var channel =
        new RpcChannel(new FailingSendTransport(), new HostFunctionRegistry(), BEYOND_HANG_GUARD);

    var ex = assertThrows(RpcChannel.RpcException.class, () -> channel.call("test", null));

    assertTrue(ex.getMessage().contains("Failed to send"));
    channel.close();
  }

  @Test
  void notifyWithSendIoExceptionThrows() {
    var channel =
        new RpcChannel(new FailingSendTransport(), new HostFunctionRegistry(), BEYOND_HANG_GUARD);

    var ex = assertThrows(RpcChannel.RpcException.class, () -> channel.notify("test", null));

    assertTrue(ex.getMessage().contains("Failed to send"));
    channel.close();
  }

  @Test
  void closeWithPendingCallsCompletesExceptionally() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);
    var pending = callThatIsNeverAnswered(channel);
    transport.nextSent();

    channel.close();

    var failure = Await.failure("the call pending when the channel closed", pending);
    assertInstanceOf(RpcChannel.RpcException.class, failure);
    assertEquals("Channel closed", failure.getMessage());
  }

  @Test
  void closeWithIoExceptionOnTransportClose() {
    var channel =
        new RpcChannel(new FailingCloseTransport(), new HostFunctionRegistry(), BEYOND_HANG_GUARD);

    channel.close();

    assertFalse(channel.isActive());
  }

  @Test
  void incomingNotificationIsHandled() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, pingRegistry(), BEYOND_HANG_GUARD);

    transport.enqueueIncoming(new RpcMessage.Notification("tick", Map.of("n", 1)));

    assertNothingSentBeforeReaderCaughtUp(transport);
    channel.close();
  }

  @Test
  void responseForUnknownIdIsIgnored() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, pingRegistry(), BEYOND_HANG_GUARD);

    transport.enqueueIncoming(new RpcMessage.Response("unknown-id", "data"));

    assertNothingSentBeforeReaderCaughtUp(transport);
    assertTrue(channel.isActive());
    channel.close();
  }

  @Test
  void errorResponseWithNullIdIsIgnored() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, pingRegistry(), BEYOND_HANG_GUARD);

    transport.enqueueIncoming(
        new RpcMessage.ErrorResponse(null, RpcError.of(-32700, "Parse error")));

    assertNothingSentBeforeReaderCaughtUp(transport);
    assertTrue(channel.isActive());
    channel.close();
  }

  @Test
  void errorResponseForUnknownIdIsIgnored() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, pingRegistry(), BEYOND_HANG_GUARD);

    transport.enqueueIncoming(
        new RpcMessage.ErrorResponse("999", RpcError.of(-32603, "some error")));

    assertNothingSentBeforeReaderCaughtUp(transport);
    assertTrue(channel.isActive());
    channel.close();
  }

  @Test
  void incomingRequestWithNonMapParams() {
    var transport = new FakeTransport();
    var registry = registryWith("echo", params -> Map.of("received", params.size()));
    var channel = new RpcChannel(transport, registry, BEYOND_HANG_GUARD);

    transport.enqueueIncoming(new RpcMessage.Request("r5", "echo", "not-a-map"));

    assertEquals(new RpcMessage.Response("r5", Map.of("received", 0)), transport.nextSent());
    channel.close();
  }

  @Test
  void readerLoopIoExceptionLogsWarning() {
    try (var capture = new LogCapture()) {
      var channel =
          new RpcChannel(
              new FailingReceiveTransport(), new HostFunctionRegistry(), BEYOND_HANG_GUARD);

      capture.awaitMessage("Reader loop error");

      assertEquals(List.of("Reader loop error"), capture.warnings());
      assertFalse(channel.isActive());
      channel.close();
    }
  }

  /**
   * A handler still running when the channel closes ends by sending its response on a closed
   * channel. That is a benign race, dropped at FINE, and never the WARNING reserved for real send
   * failures.
   */
  @Test
  void handlerSendAfterCloseDoesNotLogWarning() {
    var transport = new FakeTransport();
    var inHandler = new CountDownLatch(1);
    var registry =
        registryWith(
            "slow",
            params -> {
              inHandler.countDown();
              awaitUninterruptibly(new CountDownLatch(1));
              return Map.of("done", true);
            });
    try (var capture = new LogCapture()) {
      var channel = new RpcChannel(transport, registry, BEYOND_HANG_GUARD);
      transport.enqueueIncoming(new RpcMessage.Request("r-race", "slow", Map.of()));
      Await.latch("the handler to start", inHandler);

      channel.close();

      capture.awaitMessage(SEND_SKIPPED);
      assertEquals(List.of(), capture.warnings());
      assertTrue(transport.sentMessages().isEmpty());
    }
  }

  @Test
  void closeInterruptsInFlightHandlers() {
    var transport = new FakeTransport();
    var entered = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    var registry =
        registryWith(
            "interruptible",
            params -> {
              entered.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return Map.of();
            });
    try (var capture = new LogCapture()) {
      var channel = new RpcChannel(transport, registry, BEYOND_HANG_GUARD);
      transport.enqueueIncoming(new RpcMessage.Request("r-int", "interruptible", Map.of()));
      Await.latch("the handler to start", entered);

      channel.close();

      Await.latch("close() to interrupt the in-flight handler", interrupted);
      capture.awaitMessage(SEND_SKIPPED);
    }
  }

  @Test
  void unknownMethodHandlerSurvivesClose() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);
    channel.close();

    channel.handleRequest(new RpcMessage.Request("r-unknown", "no-such-method", Map.of()));

    assertTrue(
        transport.sentMessages().isEmpty(),
        "handler must not send anything when the channel is already closed");
  }

  /**
   * A request the reader decoded just as close() ran is submitted to an executor that is already
   * shut down. The rejection is synchronous, so the handler can never start once it returns.
   */
  @Test
  void dispatchRequestAfterCloseLogsFineAndDropsHandler() {
    var invoked = new AtomicBoolean(false);
    var registry =
        registryWith(
            "shouldNotFire",
            params -> {
              invoked.set(true);
              return Map.of();
            });
    try (var capture = new LogCapture()) {
      var channel = new RpcChannel(new FakeTransport(), registry, BEYOND_HANG_GUARD);
      channel.close();

      channel.dispatchRequest(new RpcMessage.Request("r-rejected", "shouldNotFire", Map.of()));

      assertFalse(invoked.get(), "registry function must not fire after executor shutdown");
      assertEquals(List.of(), capture.warnings());
      assertEquals(
          1,
          capture.messages().stream().filter(m -> m.contains("Handler rejected")).count(),
          "must log 'Handler rejected' at FINE");
    }
  }

  @Test
  void isActiveReturnsFalseWhenTransportClosedButChannelOpen() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);
    transport.close();
    assertFalse(channel.isActive(), "isActive must return false when transport is closed");
    channel.close();
  }

  /**
   * The transport dies under a running handler while close() has not been called on the channel.
   * The handler's response is dropped by safeSend's pre-check.
   */
  @Test
  void safeSendPreCheckCatchesTransportClosedButChannelOpen() {
    var transport = new FakeTransport();
    var inHandler = new CountDownLatch(1);
    var releaseHandler = new CountDownLatch(1);
    var registry =
        registryWith(
            "wait",
            params -> {
              inHandler.countDown();
              awaitUninterruptibly(releaseHandler);
              return Map.of();
            });
    try (var capture = new LogCapture()) {
      var channel = new RpcChannel(transport, registry, BEYOND_HANG_GUARD);
      transport.enqueueIncoming(new RpcMessage.Request("r-tx", "wait", Map.of()));
      Await.latch("the handler to start", inHandler);

      transport.close();
      releaseHandler.countDown();

      capture.awaitMessage(SEND_SKIPPED);
      assertEquals(List.of(), capture.warnings());
      assertTrue(transport.sentMessages().isEmpty());
      assertFalse(transport.isOpen());
      channel.close();
    }
  }

  /**
   * The channel closes between safeSend's pre-check and the failing write: the transport closes the
   * channel from inside send, then throws.
   */
  @Test
  void safeSendPostThrowCheckCatchesChannelClosedBranch() {
    var channelRef = new AtomicReference<RpcChannel>();
    var transport =
        new FakeTransport() {
          @Override
          public void send(RpcMessage message) throws IOException {
            if (message instanceof RpcMessage.Response) {
              channelRef.get().close();
              throw new IOException("synthetic send failure while channel closes");
            }
            super.send(message);
          }
        };
    try (var capture = new LogCapture()) {
      channelRef.set(new RpcChannel(transport, pingRegistry(), BEYOND_HANG_GUARD));

      transport.enqueueIncoming(new RpcMessage.Request("r-ch-close", PING, Map.of()));

      capture.awaitMessage(SEND_RACE_IGNORED);
      assertEquals(List.of(), capture.warnings());
    }
  }

  /**
   * A read failure that arrives after close() is not a protocol failure and is not logged. The
   * transport's receive() blocks until close() and then throws, so the failure can only be seen
   * with the channel already closed; the count proves it was thrown.
   */
  @Test
  void readerLoopIoExceptionAfterCloseIsSilent() {
    var readerInReceive = new CompletableFuture<Thread>();
    var throwCount = new AtomicInteger();
    var transport =
        new SilentTransport() {
          @Override
          public RpcMessage receive() throws IOException {
            readerInReceive.complete(Thread.currentThread());
            super.receive();
            throwCount.incrementAndGet();
            throw new IOException("post-close read failure");
          }
        };
    try (var capture = new LogCapture()) {
      var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);
      var reader = Await.value("the reader to enter receive()", readerInReceive);

      channel.close();

      Await.termination("the reader loop to end", reader);
      assertEquals(1, throwCount.get(), "receive() must have thrown its IOException");
      assertEquals(List.of(), capture.warnings());
    }
  }

  @Test
  void handleRequestEarlyExitsWhenChannelAlreadyClosed() {
    var transport = new FakeTransport();
    var invoked = new AtomicBoolean(false);
    var registry =
        registryWith(
            "shouldNotFire",
            params -> {
              invoked.set(true);
              return Map.of();
            });
    var channel = new RpcChannel(transport, registry, BEYOND_HANG_GUARD);
    channel.close();

    channel.handleRequest(new RpcMessage.Request("r-early", "shouldNotFire", Map.of()));

    assertFalse(invoked.get(), "handler must not run when channel is already closed on entry");
    assertTrue(
        transport.sentMessages().isEmpty(),
        "no response or error response must be sent after close");
  }

  /**
   * The transport died (broken pipe, peer EOF) but close() has not been called on the channel: the
   * other arm of handleRequest's guard.
   */
  @Test
  void handleRequestEarlyExitsWhenTransportClosedButChannelOpen() {
    var transport = new FakeTransport();
    var invoked = new AtomicBoolean(false);
    var registry =
        registryWith(
            "nope",
            params -> {
              invoked.set(true);
              return Map.of();
            });
    var channel = new RpcChannel(transport, registry, BEYOND_HANG_GUARD);
    transport.close();

    channel.handleRequest(new RpcMessage.Request("r-tx-closed", "nope", Map.of()));

    assertFalse(invoked.get());
    assertTrue(transport.sentMessages().isEmpty());
    channel.close();
  }

  /**
   * A transport that throws unchecked must not kill the handler thread silently: the failure is
   * logged at WARNING so the upstream bug is visible.
   */
  @Test
  void safeSendSwallowsRuntimeExceptionFromBuggyTransport() {
    var transport =
        new FakeTransport() {
          @Override
          public void send(RpcMessage message) {
            throw new IllegalStateException("buggy transport");
          }
        };
    try (var capture = new LogCapture()) {
      var channel = new RpcChannel(transport, pingRegistry(), BEYOND_HANG_GUARD);

      transport.enqueueIncoming(new RpcMessage.Request("r-runtime", PING, Map.of()));

      capture.awaitMessage("Unexpected runtime error sending response");
      assertEquals(List.of("Unexpected runtime error sending response"), capture.warnings());
      channel.close();
    }
  }

  @Test
  void callInterruptedThrowsRpcExceptionAndRestoresInterruptFlag() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);
    var observedException = new AtomicReference<Throwable>();
    var observedInterrupt = new AtomicBoolean(false);
    var caller =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    channel.call("never-responds", null);
                  } catch (RpcChannel.RpcException e) {
                    observedException.set(e);
                  }
                  observedInterrupt.set(Thread.currentThread().isInterrupted());
                });
    transport.nextSent();

    caller.interrupt();

    Await.termination("the interrupted caller", caller);
    assertInstanceOf(RpcChannel.RpcException.class, observedException.get());
    assertTrue(observedException.get().getMessage().contains("Call interrupted"));
    assertTrue(observedInterrupt.get(), "interrupt flag must be restored on the calling thread");
    channel.close();
  }

  /**
   * The reader completes a pending call only with a result or an RpcException, so no message can
   * make a call fail with any other cause. This reaches the defensive branch that wraps one by
   * failing the pending future directly.
   */
  @Test
  void callExecutionExceptionWithNonRpcCauseWraps() {
    var transport = new FakeTransport();
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);
    transport.onSend(
        msg -> {
          if (msg instanceof RpcMessage.Request req) {
            pendingCalls(channel)
                .get(req.id())
                .completeExceptionally(new IllegalStateException("non-rpc cause"));
          }
        });

    var ex = assertThrows(RpcChannel.RpcException.class, () -> channel.call("test", null));

    assertEquals("Call failed", ex.getMessage());
    assertInstanceOf(IllegalStateException.class, ex.getCause());
    channel.close();
  }

  /**
   * The peer closes while a handler writes its response: the write fails and the transport is
   * closed by the time safeSend looks again, so the failure is the benign race, logged at FINE.
   */
  @Test
  void handlerSendIoExceptionDuringMidFlightCloseLogsFine() {
    var transport =
        new FakeTransport() {
          @Override
          public void send(RpcMessage message) throws IOException {
            close();
            throw new IOException("synthetic mid-send close");
          }
        };
    try (var capture = new LogCapture()) {
      var channel = new RpcChannel(transport, pingRegistry(), BEYOND_HANG_GUARD);

      transport.enqueueIncoming(new RpcMessage.Request("r-midclose", PING, Map.of()));

      capture.awaitMessage(SEND_RACE_IGNORED);
      assertEquals(List.of(), capture.warnings());
      channel.close();
    }
  }

  @Test
  void handlerSendIoExceptionWhileOpenStillWarns() {
    var transport =
        new FakeTransport() {
          @Override
          public void send(RpcMessage message) throws IOException {
            throw new IOException("synthetic failure (still open)");
          }
        };
    try (var capture = new LogCapture()) {
      var channel = new RpcChannel(transport, pingRegistry(), BEYOND_HANG_GUARD);

      transport.enqueueIncoming(new RpcMessage.Request("r-warn", PING, Map.of()));

      capture.awaitMessage(SEND_FAILED);
      assertEquals(List.of(SEND_FAILED), capture.warnings());
      channel.close();
    }
  }

  private static HostFunctionRegistry registryWith(String name, HostFunctionHandler handler) {
    var registry = new HostFunctionRegistry();
    registry.register(new HostFunction(name, "Test function " + name, handler));
    return registry;
  }

  private static HostFunctionRegistry pingRegistry() {
    return registryWith(PING, params -> PONG);
  }

  /**
   * The reader dispatches in order, so the answer to a request enqueued now is sent only after
   * everything enqueued before it was dispatched. That answer being the first message sent proves
   * the earlier messages produced none.
   */
  private static void assertNothingSentBeforeReaderCaughtUp(FakeTransport transport) {
    transport.enqueueIncoming(new RpcMessage.Request("caught-up", PING, Map.of()));
    assertEquals(new RpcMessage.Response("caught-up", PONG), transport.nextSent());
    assertTrue(transport.sentMessages().isEmpty());
  }

  private static CompletableFuture<Object> callThatIsNeverAnswered(RpcChannel channel) {
    return CompletableFuture.supplyAsync(
        () -> channel.call("never-responds", null), Thread.ofVirtual()::start);
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @SuppressWarnings("unchecked")
  private static ConcurrentHashMap<String, CompletableFuture<Object>> pendingCalls(
      RpcChannel channel) {
    try {
      var field = RpcChannel.class.getDeclaredField("pendingCalls");
      field.setAccessible(true);
      return (ConcurrentHashMap<String, CompletableFuture<Object>>) field.get(channel);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Collects what {@link RpcChannel} logs while open, at every level, and lets a test wait for a
   * record. A handler's or the reader's last act on a path is its log record, so the record is the
   * event after which that thread logs and sends nothing more.
   */
  private static final class LogCapture extends Handler implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(RpcChannel.class.getName());

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final BlockingQueue<LogRecord> unread = new LinkedBlockingQueue<>();
    private final Level priorLevel = LOGGER.getLevel();

    LogCapture() {
      LOGGER.addHandler(this);
      LOGGER.setLevel(Level.ALL);
    }

    @Override
    public void publish(LogRecord record) {
      records.add(record);
      unread.add(record);
    }

    void awaitMessage(String message) {
      var description = "a log record saying '" + message + "'";
      LogRecord record;
      do {
        record = Await.next(description, unread);
      } while (!message.equals(record.getMessage()));
    }

    List<String> messages() {
      return records.stream().map(LogRecord::getMessage).toList();
    }

    List<String> warnings() {
      return records.stream()
          .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
          .map(LogRecord::getMessage)
          .toList();
    }

    @Override
    public void flush() {}

    @Override
    public void close() {
      LOGGER.removeHandler(this);
      LOGGER.setLevel(priorLevel);
    }
  }

  /** Never delivers a message: {@code receive()} blocks until the transport is closed. */
  private static class SilentTransport implements RpcTransport {
    private final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public void send(RpcMessage message) throws IOException {}

    @Override
    public RpcMessage receive() throws IOException {
      awaitUninterruptibly(closed);
      return null;
    }

    @Override
    public boolean isOpen() {
      return closed.getCount() > 0;
    }

    @Override
    public void close() throws IOException {
      closed.countDown();
    }
  }

  private static final class FailingSendTransport extends SilentTransport {
    @Override
    public void send(RpcMessage message) throws IOException {
      throw new IOException("Send failed");
    }
  }

  private static final class FailingCloseTransport extends SilentTransport {
    @Override
    public void close() throws IOException {
      super.close();
      throw new IOException("Close failed");
    }
  }

  private static final class FailingReceiveTransport implements RpcTransport {
    private volatile boolean open = true;

    @Override
    public void send(RpcMessage message) {}

    @Override
    public RpcMessage receive() throws IOException {
      open = false;
      throw new IOException("Receive failed");
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
    }
  }

  /**
   * Delivers the messages a test enqueues and queues the ones the channel sends. {@code receive()}
   * blocks until a message is enqueued or the transport is closed.
   */
  private static class FakeTransport implements RpcTransport {
    private static final RpcMessage CLOSED = new RpcMessage.Notification("closed", Map.of());

    private final BlockingQueue<RpcMessage> incoming = new LinkedBlockingQueue<>();
    private final BlockingQueue<RpcMessage> sent = new LinkedBlockingQueue<>();
    private volatile boolean open = true;
    private volatile Consumer<RpcMessage> onSendCallback = message -> {};

    void enqueueIncoming(RpcMessage msg) {
      incoming.add(msg);
    }

    void onSend(Consumer<RpcMessage> callback) {
      this.onSendCallback = callback;
    }

    RpcMessage nextSent() {
      return Await.next("the next message the channel sends", sent);
    }

    BlockingQueue<RpcMessage> sentMessages() {
      return sent;
    }

    @Override
    public void send(RpcMessage message) throws IOException {
      if (!open) {
        throw new IOException("Closed");
      }
      sent.add(message);
      onSendCallback.accept(message);
    }

    @Override
    public RpcMessage receive() {
      try {
        var message = incoming.take();
        return open ? message : null;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    @Override
    public void close() {
      open = false;
      incoming.add(CLOSED);
    }
  }
}
