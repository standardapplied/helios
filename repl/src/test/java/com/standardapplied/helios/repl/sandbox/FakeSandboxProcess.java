/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.test.FeedableInputStream;
import com.standardapplied.helios.core.test.LineSink;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcChannel;
import com.standardapplied.helios.repl.protocol.RpcMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A {@link JvmSandbox} whose subprocess is played by the test. The sandbox's transport writes its
 * requests to a {@link LineSink} and reads from a {@link FeedableInputStream}; {@link #answerNext}
 * stands in for the subprocess, taking the next request line and feeding back what the subprocess
 * would print. The {@link Process} the sandbox watches is a real child that only sleeps, so the
 * sandbox reports alive until it is closed.
 */
final class FakeSandboxProcess implements AutoCloseable {

  private final LineSink toProcess = new LineSink();
  private final FeedableInputStream fromProcess = new FeedableInputStream();
  private final JvmSandbox sandbox;
  private CompletableFuture<String> answer = CompletableFuture.completedFuture(null);

  FakeSandboxProcess() throws IOException {
    var transport = new ProcessTransport(fromProcess, toProcess);
    var channel = new RpcChannel(transport, new HostFunctionRegistry(), Await.HANG_GUARD);
    sandbox =
        new JvmSandbox(
            new ProcessBuilder("sleep", "600").start(),
            transport,
            channel,
            JvmSandboxConfig.defaults());
  }

  JvmSandbox sandbox() {
    return sandbox;
  }

  /**
   * Answers the sandbox's next request with {@code result}, after printing each line of {@code
   * plainOutput} the way a snippet's own output reaches the stream the transport reads.
   */
  void answerNext(Object result, String... plainOutput) {
    answerNextWith(id -> new RpcMessage.Response(id, result), plainOutput);
  }

  /**
   * Answers the sandbox's next request with the message {@code reply} builds from the request's id,
   * after printing each line of {@code plainOutput}.
   */
  void answerNextWith(Function<String, RpcMessage> reply, String... plainOutput) {
    answer =
        CompletableFuture.supplyAsync(
            () -> {
              var line = toProcess.nextLine();
              var request = request(line);
              for (var output : plainOutput) {
                fromProcess.feed(output + "\n");
              }
              fromProcess.feed(
                  ProcessTransport.RPC_PREFIX + serialize(reply.apply(request.id())) + "\n");
              return line;
            },
            Thread.ofVirtual()::start);
  }

  /** The request line the last {@link #answerNextWith} answered. */
  String answeredRequest() {
    return Await.value("the fake subprocess's answer", answer);
  }

  @Override
  public void close() {
    sandbox.close();
    Await.value("the fake subprocess's answer", answer);
  }

  private static RpcMessage.Request request(String line) {
    try {
      return (RpcMessage.Request) ProcessTransport.deserializeMessage(line);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String serialize(RpcMessage message) {
    try {
      return ProcessTransport.serializeMessage(message);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
