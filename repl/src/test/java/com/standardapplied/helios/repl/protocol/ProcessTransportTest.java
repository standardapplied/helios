/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcessTransportTest {

  @Test
  void nullInputThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessTransport(null, new ByteArrayOutputStream()));
  }

  @Test
  void nullOutputThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessTransport(new ByteArrayInputStream(new byte[0]), null));
  }

  @Test
  void sendAndDeserializeRequest() throws Exception {
    var out = new ByteArrayOutputStream();
    var transport = new ProcessTransport(new ByteArrayInputStream(new byte[0]), out);
    var msg = new RpcMessage.Request("1", "execute", Map.of("code", "x+1"));

    transport.send(msg);

    var json = out.toString(StandardCharsets.UTF_8).trim();
    assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
    assertTrue(json.contains("\"id\":\"1\""));
    assertTrue(json.contains("\"method\":\"execute\""));
  }

  @Test
  void sendResponseWithNullResult() throws Exception {
    var out = new ByteArrayOutputStream();
    var transport = new ProcessTransport(new ByteArrayInputStream(new byte[0]), out);

    transport.send(new RpcMessage.Response("42", null));

    var json = out.toString(StandardCharsets.UTF_8).trim();
    assertTrue(json.contains("\"id\":\"42\""));
    assertTrue(json.contains("\"result\":null"));
  }

  @Test
  void sendErrorResponse() throws Exception {
    var out = new ByteArrayOutputStream();
    var transport = new ProcessTransport(new ByteArrayInputStream(new byte[0]), out);

    var error = new RpcError(-32601, "Method not found", "extra");
    transport.send(new RpcMessage.ErrorResponse("5", error));

    var json = out.toString(StandardCharsets.UTF_8).trim();
    assertTrue(json.contains("\"error\""));
    assertTrue(json.contains("-32601"));
    assertTrue(json.contains("\"data\":\"extra\""));
  }

  @Test
  void sendErrorResponseWithoutData() throws Exception {
    var out = new ByteArrayOutputStream();
    var transport = new ProcessTransport(new ByteArrayInputStream(new byte[0]), out);

    var error = RpcError.of(-32603, "Internal error");
    transport.send(new RpcMessage.ErrorResponse("6", error));

    var json = out.toString(StandardCharsets.UTF_8).trim();
    assertFalse(json.contains("\"data\""));
  }

  @Test
  void sendNotification() throws Exception {
    var out = new ByteArrayOutputStream();
    var transport = new ProcessTransport(new ByteArrayInputStream(new byte[0]), out);

    transport.send(new RpcMessage.Notification("progress", Map.of("pct", 50)));

    var json = out.toString(StandardCharsets.UTF_8).trim();
    assertTrue(json.contains("\"method\":\"progress\""));
    assertFalse(json.contains("\"id\""));
  }

  @Test
  void sendRequestNullParams() throws Exception {
    var out = new ByteArrayOutputStream();
    var transport = new ProcessTransport(new ByteArrayInputStream(new byte[0]), out);

    transport.send(new RpcMessage.Request("1", "ping", null));

    var json = out.toString(StandardCharsets.UTF_8).trim();
    assertTrue(json.contains("\"params\":{}"));
  }

  @Test
  void sendNotificationNullParams() throws Exception {
    var out = new ByteArrayOutputStream();
    var transport = new ProcessTransport(new ByteArrayInputStream(new byte[0]), out);

    transport.send(new RpcMessage.Notification("tick", null));

    var json = out.toString(StandardCharsets.UTF_8).trim();
    assertTrue(json.contains("\"params\":{}"));
  }

  @Test
  void receiveRpcPrefixedLine() throws Exception {
    var rpcJson =
        """
        {"jsonrpc":"2.0","id":"1","method":"execute","params":{"code":"1+1"}}""";
    var input = (ProcessTransport.RPC_PREFIX + rpcJson + "\n").getBytes(StandardCharsets.UTF_8);
    var transport =
        new ProcessTransport(new ByteArrayInputStream(input), new ByteArrayOutputStream());

    var msg = transport.receive();

    assertInstanceOf(RpcMessage.Request.class, msg);
    var req = (RpcMessage.Request) msg;
    assertEquals("1", req.id());
    assertEquals("execute", req.method());
  }

  @Test
  void receiveResponseMessage() throws Exception {
    var rpcJson =
        """
        {"jsonrpc":"2.0","id":"1","result":{"stdout":"hello"}}""";
    var input = (ProcessTransport.RPC_PREFIX + rpcJson + "\n").getBytes(StandardCharsets.UTF_8);
    var transport =
        new ProcessTransport(new ByteArrayInputStream(input), new ByteArrayOutputStream());

    var msg = transport.receive();

    assertInstanceOf(RpcMessage.Response.class, msg);
    assertEquals("1", ((RpcMessage.Response) msg).id());
  }

  @Test
  void receiveErrorResponse() throws Exception {
    var rpcJson =
        """
        {"jsonrpc":"2.0","id":"1","error":{"code":-32601,"message":"Not found"}}""";
    var input = (ProcessTransport.RPC_PREFIX + rpcJson + "\n").getBytes(StandardCharsets.UTF_8);
    var transport =
        new ProcessTransport(new ByteArrayInputStream(input), new ByteArrayOutputStream());

    var msg = transport.receive();

    assertInstanceOf(RpcMessage.ErrorResponse.class, msg);
    var err = (RpcMessage.ErrorResponse) msg;
    assertEquals(-32601, err.error().code());
  }

  @Test
  void receiveNotification() throws Exception {
    var rpcJson =
        """
        {"jsonrpc":"2.0","method":"progress","params":{"pct":50}}""";
    var input = (ProcessTransport.RPC_PREFIX + rpcJson + "\n").getBytes(StandardCharsets.UTF_8);
    var transport =
        new ProcessTransport(new ByteArrayInputStream(input), new ByteArrayOutputStream());

    var msg = transport.receive();

    assertInstanceOf(RpcMessage.Notification.class, msg);
    assertEquals("progress", ((RpcMessage.Notification) msg).method());
  }

  @Test
  void nonRpcLinesGoToStdoutBuffer() throws Exception {
    var lines =
        "hello world\n"
            + ProcessTransport.RPC_PREFIX
            + "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":\"ok\"}\n";
    var transport =
        new ProcessTransport(
            new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)),
            new ByteArrayOutputStream());

    var msg = transport.receive();

    assertInstanceOf(RpcMessage.Response.class, msg);
    assertEquals("hello world", transport.drainStdout());
  }

  @Test
  void multipleNonRpcLinesConcatenated() throws Exception {
    var lines =
        "line1\nline2\n"
            + ProcessTransport.RPC_PREFIX
            + "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":\"ok\"}\n";
    var transport =
        new ProcessTransport(
            new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)),
            new ByteArrayOutputStream());

    transport.receive();

    assertEquals("line1\nline2", transport.drainStdout());
  }

  @Test
  void drainStdoutClearsBuffer() throws Exception {
    var lines =
        "output\n"
            + ProcessTransport.RPC_PREFIX
            + "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":\"ok\"}\n";
    var transport =
        new ProcessTransport(
            new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8)),
            new ByteArrayOutputStream());

    transport.receive();
    assertEquals("output", transport.drainStdout());
    assertEquals("", transport.drainStdout());
  }

  @Test
  void receiveReturnsNullOnEof() throws Exception {
    var transport =
        new ProcessTransport(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());

    assertNull(transport.receive());
    assertFalse(transport.isOpen());
  }

  @Test
  void closeMarksClosed() throws Exception {
    var transport =
        new ProcessTransport(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());

    assertTrue(transport.isOpen());
    transport.close();
    assertFalse(transport.isOpen());
  }

  @Test
  void sendOnClosedTransportThrows() throws Exception {
    var transport =
        new ProcessTransport(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
    transport.close();

    assertThrows(IOException.class, () -> transport.send(new RpcMessage.Request("1", "x", null)));
  }

  @Test
  void doubleCloseIsSafe() throws Exception {
    var transport =
        new ProcessTransport(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
    transport.close();
    transport.close();
    assertFalse(transport.isOpen());
  }

  @Test
  void rpcPrefixConstant() {
    assertEquals("\0RPC:", ProcessTransport.RPC_PREFIX);
  }

  // --- Deserialization edge cases ---

  @Test
  void deserializeUnrecognizedMessageThrows() {
    assertThrows(
        IOException.class, () -> ProcessTransport.deserializeMessage("{\"unknown\":true}"));
  }

  @Test
  void deserializeMalformedJsonThrowsIOException() {
    var ex =
        assertThrows(
            IOException.class, () -> ProcessTransport.deserializeMessage("not json at all"));
    assertTrue(ex.getMessage().contains("Malformed"));
  }

  @Test
  void deserializeRequestWithNonStringMethodThrowsIOException() {
    var ex =
        assertThrows(
            IOException.class,
            () -> ProcessTransport.deserializeMessage("{\"id\":\"1\",\"method\":42}"));
    assertTrue(ex.getMessage().contains("'method'"));
  }

  @Test
  void deserializeNotificationWithNonStringMethodThrowsIOException() {
    var ex =
        assertThrows(
            IOException.class, () -> ProcessTransport.deserializeMessage("{\"method\":42}"));
    assertTrue(ex.getMessage().contains("'method'"));
  }

  @Test
  void deserializeErrorWithNonObjectErrorFieldThrowsIOException() {
    var ex =
        assertThrows(
            IOException.class,
            () ->
                ProcessTransport.deserializeMessage("{\"id\":\"1\",\"error\":\"not an object\"}"));
    assertTrue(ex.getMessage().contains("'error'"));
  }

  @Test
  void deserializeErrorWithNonStringMessageCoerces() throws Exception {
    var json = "{\"id\":\"1\",\"error\":{\"code\":-1,\"message\":42}}";
    var msg = ProcessTransport.deserializeMessage(json);
    assertInstanceOf(RpcMessage.ErrorResponse.class, msg);
    assertEquals("42", ((RpcMessage.ErrorResponse) msg).error().message());
  }

  @Test
  void deserializeErrorResponseWithNullId() throws Exception {
    var json =
        """
        {"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}""";
    var msg = ProcessTransport.deserializeMessage(json);

    assertInstanceOf(RpcMessage.ErrorResponse.class, msg);
    var err = (RpcMessage.ErrorResponse) msg;
    assertNull(err.id());
  }

  @Test
  void deserializeResponseWithIdOnly() throws Exception {
    var json =
        """
        {"jsonrpc":"2.0","id":"99"}""";
    var msg = ProcessTransport.deserializeMessage(json);

    assertInstanceOf(RpcMessage.Response.class, msg);
    assertEquals("99", ((RpcMessage.Response) msg).id());
    assertNull(((RpcMessage.Response) msg).result());
  }

  // --- Serialization round-trip ---

  @Test
  void serializeDeserializeRequestRoundTrip() throws Exception {
    var original = new RpcMessage.Request("7", "eval", Map.of("lang", "java"));
    var json = ProcessTransport.serializeMessage(original);
    var deserialized = ProcessTransport.deserializeMessage(json);

    assertInstanceOf(RpcMessage.Request.class, deserialized);
    var req = (RpcMessage.Request) deserialized;
    assertEquals("7", req.id());
    assertEquals("eval", req.method());
  }

  @Test
  void serializeDeserializeResponseRoundTrip() throws Exception {
    var original = new RpcMessage.Response("3", Map.of("value", 42));
    var json = ProcessTransport.serializeMessage(original);
    var deserialized = ProcessTransport.deserializeMessage(json);

    assertInstanceOf(RpcMessage.Response.class, deserialized);
    assertEquals("3", ((RpcMessage.Response) deserialized).id());
  }

  @Test
  void serializeDeserializeNotificationRoundTrip() throws Exception {
    var original = new RpcMessage.Notification("tick", Map.of("n", 1));
    var json = ProcessTransport.serializeMessage(original);
    var deserialized = ProcessTransport.deserializeMessage(json);

    assertInstanceOf(RpcMessage.Notification.class, deserialized);
    assertEquals("tick", ((RpcMessage.Notification) deserialized).method());
  }

  /**
   * LightGrid bug-report defense: when the read side throws an IOException (closed pipe, broken
   * stream, etc.), the transport must mark itself closed before rethrowing. Otherwise a handler
   * racing into {@code send(...)} sees {@code isOpen()==true} and logs a benign close-race as a
   * real WARNING. The {@code RpcChannel.safeSend} downgrade relies on this invariant.
   */
  @Test
  void deserializeNotificationWithoutId() throws Exception {
    var json = "{\"jsonrpc\":\"2.0\",\"method\":\"tick\",\"params\":{\"n\":1}}";
    var msg = ProcessTransport.deserializeMessage(json);
    assertInstanceOf(RpcMessage.Notification.class, msg);
    assertEquals("tick", ((RpcMessage.Notification) msg).method());
  }

  @Test
  void deserializeErrorWithNonNumericCodeCoercesToZero() throws Exception {
    var json =
        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":\"not-a-number\",\"message\":\"boom\"}}";
    var msg = ProcessTransport.deserializeMessage(json);
    assertInstanceOf(RpcMessage.ErrorResponse.class, msg);
    assertEquals(0, ((RpcMessage.ErrorResponse) msg).error().code());
  }

  @Test
  void deserializeResponseShapedWithIdButNoResultOrMethod() throws Exception {
    var json = "{\"jsonrpc\":\"2.0\",\"id\":\"7\"}";
    var msg = ProcessTransport.deserializeMessage(json);
    assertInstanceOf(RpcMessage.Response.class, msg);
    assertEquals("7", ((RpcMessage.Response) msg).id());
  }

  @Test
  void deserializeUnrecognizedShapeThrows() {
    // Empty-ish JSON with no method, id, result, or error falls through every branch to the
    // trailing IOException. Covers the negative arms of the response-shape check that none of
    // the message-type tests exercise.
    assertThrows(
        IOException.class, () -> ProcessTransport.deserializeMessage("{\"jsonrpc\":\"2.0\"}"));
  }

  /**
   * Covers the {@code return null} at the end of {@code receive()} — reached only when the
   * transport is closed by another thread <em>between</em> read iterations. We engineer the timing
   * deterministically by holding the internal {@code stdoutBuffer} monitor while the receive loop
   * blocks at the append step, calling {@code close()} during the wait, then releasing.
   */
  @Test
  void receiveReturnsNullWhenClosedBetweenIterations() throws Exception {
    var input =
        new java.io.ByteArrayInputStream(
            "first-line\nsecond-line\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var transport = new ProcessTransport(input, new ByteArrayOutputStream());

    var bufferField = ProcessTransport.class.getDeclaredField("stdoutBuffer");
    bufferField.setAccessible(true);
    var buffer = bufferField.get(transport);

    var monitorHeld = new java.util.concurrent.CountDownLatch(1);
    var releaseMonitor = new java.util.concurrent.CountDownLatch(1);
    var holder =
        Thread.ofVirtual()
            .start(
                () -> {
                  synchronized (buffer) {
                    monitorHeld.countDown();
                    try {
                      releaseMonitor.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                  }
                });
    assertTrue(monitorHeld.await(2, java.util.concurrent.TimeUnit.SECONDS));

    var result = new java.util.concurrent.atomic.AtomicReference<RpcMessage>();
    var receiver =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    result.set(transport.receive());
                  } catch (IOException ignored) {
                    // not expected — receive should return null cleanly
                  }
                });

    // Give the receiver time to read the first line and block at the synchronized append.
    Thread.sleep(100);

    // Flip the transport closed while the receiver is blocked on the monitor; release the
    // monitor so the receiver finishes its append, loops back, and exits at the trailing
    // `return null;`.
    transport.close();
    releaseMonitor.countDown();

    receiver.join(2000);
    holder.join(2000);

    assertNull(result.get(), "receive must return null after external close between iterations");
    assertFalse(transport.isOpen());
  }

  @Test
  void receiveIoExceptionMarksTransportClosed() {
    var failing =
        new java.io.InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("synthetic pipe failure");
          }
        };
    var sink = new ByteArrayOutputStream();
    var transport = new ProcessTransport(failing, sink);

    assertTrue(transport.isOpen(), "fresh transport must start open");

    assertThrows(IOException.class, transport::receive);

    assertFalse(transport.isOpen(), "IOException from read side must mark the transport closed");
  }
}
