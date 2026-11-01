/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.AsynchronousCloseException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A channel releases what it holds exactly once, whichever comes first: its transport ending or
 * {@link RpcChannel#close()}. The transport is a real {@link ProcessTransport} over streams that
 * count their closes; a disconnect is a feed stream that ends.
 */
class RpcChannelDisposalTest {

  private static final String BLOCKED_REQUEST =
      ProcessTransport.RPC_PREFIX
          + "{\"jsonrpc\":\"2.0\",\"id\":\"r1\",\"method\":\"blocked\",\"params\":{}}\n";

  private final CountedStreams streams = new CountedStreams();
  private final CountDownLatch handlerEntered = new CountDownLatch(1);
  private final CountDownLatch handlerInterrupted = new CountDownLatch(1);
  private final RpcChannel channel =
      new RpcChannel(streams.transport(), blockingRegistry(), Await.HANG_GUARD.multipliedBy(5));

  /** Leaves one handler blocked and one outbound call pending, and returns that call. */
  private CompletableFuture<Object> blockHandlerAndCall() {
    streams.fromPeer.feed(BLOCKED_REQUEST);
    Await.latch("the handler to start", handlerEntered);
    var pending =
        CompletableFuture.supplyAsync(
            () -> channel.call("never-answered", null), Thread.ofVirtual()::start);
    streams.toPeer.nextLine();
    return pending;
  }

  @Test
  void theTransportEndingReleasesEverythingAndALaterCloseChangesNothing() {
    var pending = blockHandlerAndCall();

    streams.fromPeer.end();

    Await.latch("the blocked handler to be interrupted", handlerInterrupted);
    assertChannelClosed(Await.failure("the pending call", pending));
    streams.awaitBothClosed();
    assertFalse(channel.isActive());
    channel.close();
    Await.termination("the reader", streams.reader());
    assertClosedOnce();
  }

  @Test
  void closeBeforeTheTransportEndsReleasesEverythingOnce() {
    var pending = blockHandlerAndCall();

    channel.close();
    streams.fromPeer.end();

    Await.latch("the blocked handler to be interrupted", handlerInterrupted);
    assertChannelClosed(Await.failure("the pending call", pending));
    Await.termination("the reader", streams.reader());
    assertClosedOnce();
  }

  @Test
  void aReadFailureReleasesEverythingOnce() {
    var pending = blockHandlerAndCall();

    streams.fromPeer.fail(new IOException("synthetic read failure"));

    assertChannelClosed(Await.failure("the pending call", pending));
    streams.awaitBothClosed();
    Await.termination("the reader", streams.reader());
    assertClosedOnce();
  }

  @Test
  void aFrameOverTheLimitEndsTheChannel() {
    var pending = blockHandlerAndCall();

    streams.fromPeer.feed(
        ProcessTransport.RPC_PREFIX + "x".repeat(ProcessTransport.MAX_FRAME_CHARS));

    assertChannelClosed(Await.failure("the pending call", pending));
    streams.awaitBothClosed();
    assertFalse(channel.isActive());
    assertClosedOnce();
  }

  /**
   * A socket's write blocks once the peer stops reading, and only closing the socket releases it.
   * The input stands for the socket: disposal has to close it without waiting for the write.
   */
  @Test
  void aDisconnectReleasesACallBlockedWritingToAPeerThatStoppedReading() {
    var writeBlocked = new CountDownLatch(1);
    var socketClosed = new CountDownLatch(1);
    var fromPeer = new FeedableInputStream();
    var input =
        new FilterInputStream(fromPeer) {
          @Override
          public void close() throws IOException {
            socketClosed.countDown();
            super.close();
          }
        };
    var output =
        new OutputStream() {
          @Override
          public void write(int value) throws IOException {
            writeBlocked.countDown();
            try {
              socketClosed.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            throw new AsynchronousCloseException();
          }
        };
    var blockedChannel =
        new RpcChannel(
            new ProcessTransport(input, output),
            new HostFunctionRegistry(),
            Await.HANG_GUARD.multipliedBy(5));
    var call =
        CompletableFuture.supplyAsync(
            () -> blockedChannel.call("never-written", null), Thread.ofVirtual()::start);
    Await.latch("the write to block", writeBlocked);

    fromPeer.end();

    var failure = Await.failure("the blocked call", call);
    assertInstanceOf(RpcChannel.RpcException.class, failure);
    assertFalse(blockedChannel.isActive());
  }

  /**
   * A sandbox can die before its channel's constructor returns: the reader thread is set by then.
   */
  @Test
  void aTransportThatHasAlreadyEndedDisposesTheChannelWithoutFailing() {
    var ended = new CountedStreams();
    ended.fromPeer.end();

    var channelOverEnded =
        new RpcChannel(ended.transport(), new HostFunctionRegistry(), Await.HANG_GUARD);

    ended.awaitBothClosed();
    assertFalse(channelOverEnded.isActive());
    assertEquals(1, ended.inputCloses.get());
    assertEquals(1, ended.outputCloses.get());
  }

  /** {@link ProcessTransport} forgives a second close; a channel must not rely on that. */
  @Test
  void theTransportIsClosedOnceThoughBothPathsRun() {
    var transportCloses = new AtomicInteger();
    var endedTransport =
        new RpcTransport() {
          @Override
          public void send(RpcMessage message) {}

          @Override
          public RpcMessage receive() {
            return null;
          }

          @Override
          public boolean isOpen() {
            return true;
          }

          @Override
          public void close() {
            transportCloses.incrementAndGet();
          }
        };
    var channelOverEnded =
        new RpcChannel(endedTransport, new HostFunctionRegistry(), Await.HANG_GUARD);

    Await.until("the read loop to close the channel", () -> transportCloses.get() > 0);
    channelOverEnded.close();

    assertEquals(1, transportCloses.get());
  }

  private void assertClosedOnce() {
    assertEquals(1, streams.inputCloses.get(), "closes of the input");
    assertEquals(1, streams.outputCloses.get(), "closes of the output");
  }

  private static void assertChannelClosed(Throwable failure) {
    assertInstanceOf(RpcChannel.RpcException.class, failure);
    assertEquals("Channel closed", failure.getMessage());
  }

  private HostFunctionRegistry blockingRegistry() {
    var registry = new HostFunctionRegistry();
    registry.register(
        new HostFunction(
            "blocked",
            "Blocks until interrupted.",
            params -> {
              handlerEntered.countDown();
              try {
                new CountDownLatch(1).await();
              } catch (InterruptedException e) {
                handlerInterrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return Map.of();
            }));
    return registry;
  }
}
