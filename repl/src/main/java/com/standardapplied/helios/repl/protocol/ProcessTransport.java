/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * NDJSON transport over stdin/stdout streams. JSON-RPC lines from the subprocess are prefixed with
 * a magic marker ({@code \0RPC:}) to distinguish them from regular print output.
 */
public final class ProcessTransport implements RpcTransport {

  /** Magic prefix on JSON-RPC lines from the sandbox process. */
  public static final String RPC_PREFIX = "\0RPC:";

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final BufferedReader reader;
  private final BufferedWriter writer;
  private final AtomicBoolean open = new AtomicBoolean(true);
  private final StringBuilder stdoutBuffer = new StringBuilder();

  /**
   * Create a transport over the given streams.
   *
   * @param input the stream to read messages from (sandbox stdout)
   * @param output the stream to write messages to (sandbox stdin)
   */
  public ProcessTransport(InputStream input, OutputStream output) {
    if (input == null) {
      throw new IllegalArgumentException("Input stream must not be null");
    }
    if (output == null) {
      throw new IllegalArgumentException("Output stream must not be null");
    }
    this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
  }

  @Override
  public void send(RpcMessage message) throws IOException {
    if (!open.get()) {
      throw new IOException("Transport is closed");
    }
    var json = serializeMessage(message);
    synchronized (writer) {
      writer.write(json);
      writer.newLine();
      writer.flush();
    }
  }

  @Override
  public RpcMessage receive() throws IOException {
    while (open.get()) {
      String line;
      try {
        line = reader.readLine();
      } catch (IOException e) {
        // A read-side IOException (closed pipe, broken stream) means the transport is no longer
        // usable. Mark it closed before rethrowing so any handler that races into a `send(...)`
        // after the underlying pipe died sees `isOpen()==false` and treats the failure as a
        // benign close-race rather than logging it as a real protocol error.
        open.set(false);
        throw e;
      }
      if (line == null) {
        open.set(false);
        return null;
      }
      if (line.startsWith(RPC_PREFIX)) {
        var json = line.substring(RPC_PREFIX.length());
        return deserializeMessage(json);
      }
      synchronized (stdoutBuffer) {
        if (!stdoutBuffer.isEmpty()) {
          stdoutBuffer.append('\n');
        }
        stdoutBuffer.append(line);
      }
    }
    return null;
  }

  /**
   * Drain and return any non-RPC stdout lines accumulated since the last drain.
   *
   * @return captured stdout text, or empty string if none
   */
  public String drainStdout() {
    synchronized (stdoutBuffer) {
      var result = stdoutBuffer.toString();
      stdoutBuffer.setLength(0);
      return result;
    }
  }

  @Override
  public boolean isOpen() {
    return open.get();
  }

  @Override
  public void close() throws IOException {
    if (open.compareAndSet(true, false)) {
      reader.close();
      writer.close();
    }
  }

  public static RpcMessage deserializeMessage(String json) throws IOException {
    Map<?, ?> map;
    try {
      map = MAPPER.readValue(json, Map.class);
    } catch (Exception e) {
      throw new IOException("Malformed JSON-RPC message: " + e.getMessage(), e);
    }

    if (map.containsKey("method") && map.containsKey("id")) {
      return new RpcMessage.Request(
          String.valueOf(map.get("id")), requireString(map, "method"), map.get("params"));
    }
    if (map.containsKey("method") && !map.containsKey("id")) {
      return new RpcMessage.Notification(requireString(map, "method"), map.get("params"));
    }
    if (map.containsKey("error")) {
      if (!(map.get("error") instanceof Map<?, ?> errorMap)) {
        throw new IOException("JSON-RPC 'error' field must be an object: " + json);
      }
      var code = errorMap.get("code") instanceof Number n ? n.intValue() : 0;
      var message =
          errorMap.get("message") instanceof String s ? s : String.valueOf(errorMap.get("message"));
      var data = errorMap.get("data");
      var id = map.get("id") != null ? String.valueOf(map.get("id")) : null;
      return new RpcMessage.ErrorResponse(id, new RpcError(code, message, data));
    }
    if (map.containsKey("result") || (map.containsKey("id") && !map.containsKey("method"))) {
      return new RpcMessage.Response(String.valueOf(map.get("id")), map.get("result"));
    }
    throw new IOException("Unrecognized JSON-RPC message: " + json);
  }

  private static String requireString(Map<?, ?> map, String key) throws IOException {
    if (map.get(key) instanceof String s) {
      return s;
    }
    throw new IOException("JSON-RPC '" + key + "' field must be a string: " + map.get(key));
  }

  public static String serializeMessage(RpcMessage message) throws IOException {
    return switch (message) {
      case RpcMessage.Request req ->
          MAPPER.writeValueAsString(
              Map.of(
                  "jsonrpc",
                  RpcMessage.VERSION,
                  "id",
                  req.id(),
                  "method",
                  req.method(),
                  "params",
                  req.params() != null ? req.params() : Map.of()));
      case RpcMessage.Response resp -> {
        var map = new LinkedHashMap<String, Object>();
        map.put("jsonrpc", RpcMessage.VERSION);
        map.put("id", resp.id());
        map.put("result", resp.result());
        yield MAPPER.writeValueAsString(map);
      }
      case RpcMessage.ErrorResponse err -> {
        var errorMap = new LinkedHashMap<String, Object>();
        errorMap.put("code", err.error().code());
        errorMap.put("message", err.error().message());
        if (err.error().data() != null) {
          errorMap.put("data", err.error().data());
        }
        var map = new LinkedHashMap<String, Object>();
        map.put("jsonrpc", RpcMessage.VERSION);
        map.put("id", err.id());
        map.put("error", errorMap);
        yield MAPPER.writeValueAsString(map);
      }
      case RpcMessage.Notification notif ->
          MAPPER.writeValueAsString(
              Map.of(
                  "jsonrpc",
                  RpcMessage.VERSION,
                  "method",
                  notif.method(),
                  "params",
                  notif.params() != null ? notif.params() : Map.of()));
    };
  }
}
