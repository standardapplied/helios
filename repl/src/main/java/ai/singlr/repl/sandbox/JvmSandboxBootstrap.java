/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import ai.singlr.repl.protocol.ProcessTransport;
import ai.singlr.repl.protocol.RpcError;
import ai.singlr.repl.protocol.RpcMessage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SourceCodeAnalysis;

/**
 * JShell subprocess entry point. Reads JSON-RPC execute requests on stdin, evaluates Java code via
 * JShell with {@link jdk.jshell.execution.LocalExecutionControl LocalExecutionControl}, and returns
 * structured results on stdout. Host function calls from sandbox code flow back through the same
 * stdin/stdout channel using reverse RPC.
 *
 * <p>Threading model:
 *
 * <ul>
 *   <li>Main thread runs {@link #readLoop()} — reads stdin, dispatches requests, routes responses
 *   <li>Virtual thread per execute — JShell eval with stdout/stderr capture
 *   <li>Sandbox code calling {@link HostBridge#predict} blocks on a {@link CompletableFuture} until
 *       the main thread routes the host response
 * </ul>
 *
 * <p>Only one execute may run at a time. {@code System.out}/{@code System.err} are redirected to
 * capture buffers during eval — concurrent executes would corrupt each other's streams. A {@link
 * Semaphore} enforces this invariant; the host side ({@link ai.singlr.repl.protocol.RpcChannel#call
 * RpcChannel.call}) also serializes naturally by blocking until each response arrives.
 */
public final class JvmSandboxBootstrap {

  private static final long CALL_TIMEOUT_MS = 300_000;

  private static volatile JvmSandboxBootstrap instance;

  private final JShell jshell;
  private final BufferedReader stdinReader;
  private final PrintStream realOut;
  private final ConcurrentHashMap<String, CompletableFuture<Object>> pendingCallbacks =
      new ConcurrentHashMap<>();
  private final AtomicLong idCounter = new AtomicLong(0);
  private final Semaphore executeLock = new Semaphore(1);
  private volatile Object submittedValue;

  JvmSandboxBootstrap(JShell jshell, BufferedReader stdinReader, PrintStream realOut) {
    this.jshell = jshell;
    this.stdinReader = stdinReader;
    this.realOut = realOut;
  }

  /** Subprocess entry point. */
  public static void main(String[] args) {
    var realOut = System.out;
    var jshell = JShell.builder().executionEngine("local").build();
    addHostBridgeToJShellClasspath(jshell);
    jshell.eval("import static ai.singlr.repl.sandbox.HostBridge.*;");
    jshell.eval("import ai.singlr.repl.sandbox.HostBridge;");
    SandboxPrelude.install(jshell);

    var stdinReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    var bootstrap = new JvmSandboxBootstrap(jshell, stdinReader, realOut);
    setInstance(bootstrap);

    bootstrap.readLoop();

    jshell.close();
    System.exit(0);
  }

  /**
   * Make {@link HostBridge} (and the rest of {@code ai.singlr.repl}) visible to JShell's
   * compilation context. The sandbox subprocess is launched with {@code --add-modules
   * ai.singlr.repl} so the classes are on the boot layer at runtime — but JShell's internal javac
   * runs its own compilation unit that only sees explicit classpath entries. Without this, sandbox
   * code calling {@code predict(...)}, {@code fetch(...)}, or any other bridge method fails to
   * compile with {@code "cannot find symbol"}.
   */
  static void addHostBridgeToJShellClasspath(JShell jshell) {
    try {
      var codeSource = HostBridge.class.getProtectionDomain().getCodeSource();
      if (codeSource == null) {
        return;
      }
      var url = codeSource.getLocation();
      if (url == null) {
        return;
      }
      var path = Paths.get(url.toURI()).toString();
      jshell.addToClasspath(path);
    } catch (Exception e) {
      System.err.println(
          "Warning: could not add HostBridge location to JShell classpath: " + e.getMessage());
    }
  }

  static JvmSandboxBootstrap instance() {
    return instance;
  }

  static void setInstance(JvmSandboxBootstrap inst) {
    instance = inst;
  }

  void readLoop() {
    try {
      String line;
      while ((line = stdinReader.readLine()) != null) {
        RpcMessage message;
        try {
          message = ProcessTransport.deserializeMessage(line);
        } catch (Exception e) {
          try {
            sendRpc(
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
      pendingCallbacks.forEach(
          (id, future) ->
              future.completeExceptionally(new RuntimeException("Sandbox stdin closed")));
      pendingCallbacks.clear();
    }
  }

  Map<String, Object> handleExecute(Map<String, Object> params) {
    if (!executeLock.tryAcquire()) {
      var error = new LinkedHashMap<String, Object>();
      error.put("stdout", "");
      error.put("stderr", "Concurrent execution rejected — only one execute may run at a time");
      error.put("exitCode", 1);
      error.put("submitted", null);
      return error;
    }
    try {
      return doExecute(params);
    } finally {
      executeLock.release();
    }
  }

  /**
   * Evaluate a registry-derived JShell snippet at boot time. Called by {@code JvmSandbox} via the
   * {@code installPrelude} RPC after the subprocess starts but before the first user execute. Any
   * REJECTED snippet event is collected into the response so the parent can surface the error
   * without having to dig through stderr.
   */
  Map<String, Object> handleInstallPrelude(Map<String, Object> params) {
    var snippet = params.get("snippet") instanceof String s ? s : "";
    if (snippet.isBlank()) {
      return Map.of("success", true);
    }
    var errors = new ArrayList<String>();
    var analysis = jshell.sourceCodeAnalysis();
    var remaining = snippet;
    while (!remaining.isBlank()) {
      var info = analysis.analyzeCompletion(remaining);
      if (!info.completeness().isComplete()) {
        errors.add("Incomplete snippet at: " + info.source());
        break;
      }
      var events = jshell.eval(info.source());
      for (var event : events) {
        if (event.status() == Snippet.Status.REJECTED) {
          jshell.diagnostics(event.snippet()).forEach(d -> errors.add(d.getMessage(null)));
        }
        if (event.exception() != null) {
          errors.add(event.exception().toString());
        }
      }
      remaining = info.remaining();
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("success", errors.isEmpty());
    if (!errors.isEmpty()) {
      result.put("errors", List.copyOf(errors));
    }
    return result;
  }

  void sendRpc(RpcMessage message) throws IOException {
    var json = ProcessTransport.serializeMessage(message);
    synchronized (realOut) {
      realOut.print(ProcessTransport.RPC_PREFIX + json + "\n");
      realOut.flush();
    }
  }

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

  void setSubmittedValue(Object value) {
    this.submittedValue = value;
  }

  Object submittedValue() {
    return submittedValue;
  }

  @SuppressWarnings("unchecked")
  void dispatch(RpcMessage message) {
    switch (message) {
      case RpcMessage.Request req -> {
        switch (req.method()) {
          case "execute" ->
              Thread.ofVirtual()
                  .name("jshell-execute")
                  .start(
                      () -> {
                        try {
                          var params =
                              req.params() instanceof Map<?, ?> m
                                  ? (Map<String, Object>) m
                                  : Map.<String, Object>of();
                          var result = handleExecute(params);
                          sendRpc(new RpcMessage.Response(req.id(), result));
                        } catch (Exception e) {
                          try {
                            sendRpc(
                                new RpcMessage.ErrorResponse(
                                    req.id(), RpcError.internalError(e.getMessage())));
                          } catch (IOException sendErr) {
                          }
                        }
                      });
          case "installPrelude" ->
              Thread.ofVirtual()
                  .name("jshell-install-prelude")
                  .start(
                      () -> {
                        try {
                          var params =
                              req.params() instanceof Map<?, ?> m
                                  ? (Map<String, Object>) m
                                  : Map.<String, Object>of();
                          var result = handleInstallPrelude(params);
                          sendRpc(new RpcMessage.Response(req.id(), result));
                        } catch (Exception e) {
                          try {
                            sendRpc(
                                new RpcMessage.ErrorResponse(
                                    req.id(), RpcError.internalError(e.getMessage())));
                          } catch (IOException sendErr) {
                          }
                        }
                      });
          default -> {
            try {
              sendRpc(
                  new RpcMessage.ErrorResponse(req.id(), RpcError.methodNotFound(req.method())));
            } catch (IOException e) {
            }
          }
        }
      }
      case RpcMessage.Response resp -> {
        var future = pendingCallbacks.remove(resp.id());
        if (future != null) {
          future.complete(resp.result());
        }
      }
      case RpcMessage.ErrorResponse err -> {
        var future = err.id() != null ? pendingCallbacks.remove(err.id()) : null;
        if (future != null) {
          future.completeExceptionally(
              new RuntimeException(
                  "Host error [" + err.error().code() + "]: " + err.error().message()));
        }
      }
      case RpcMessage.Notification _ -> {}
    }
  }

  private Map<String, Object> doExecute(Map<String, Object> params) {
    var code = params.get("code") instanceof String s ? s : "";
    var timeoutMs = params.get("timeoutMs") instanceof Number n ? n.longValue() : 30000L;
    var maxBindingValueChars =
        params.get("maxBindingValueChars") instanceof Number bn ? bn.intValue() : 200;
    var maxBindingSnapshotChars =
        params.get("maxBindingSnapshotChars") instanceof Number tn ? tn.intValue() : 16 * 1024;
    var captureBindings = params.get("captureBindings") instanceof Boolean cb ? cb : Boolean.TRUE;

    submittedValue = null;

    var stdoutCapture = new ByteArrayOutputStream();
    var stderrCapture = new ByteArrayOutputStream();
    var captureOut = new PrintStream(stdoutCapture, true, StandardCharsets.UTF_8);
    var captureErr = new PrintStream(stderrCapture, true, StandardCharsets.UTF_8);

    var originalOut = System.out;
    var originalErr = System.err;
    var exitCode = new AtomicInteger(0);

    System.setOut(captureOut);
    System.setErr(captureErr);
    try {
      var evalThread =
          Thread.ofVirtual()
              .name("jshell-eval")
              .start(
                  () -> {
                    try {
                      if (!evalCode(code, captureOut, captureErr)) {
                        exitCode.set(1);
                      }
                    } catch (Exception e) {
                      e.printStackTrace(captureErr);
                      exitCode.set(1);
                    }
                  });

      try {
        evalThread.join(Duration.ofMillis(timeoutMs));
        if (evalThread.isAlive()) {
          // First try cooperative interrupt — fast for snippets that block on IO or sleep.
          evalThread.interrupt();
          evalThread.join(Duration.ofMillis(1000));
          if (evalThread.isAlive()) {
            // The snippet ignored the interrupt (tight CPU loop, native call, etc.). Without
            // jshell.stop() the thread keeps running, holding references to captureOut/captureErr
            // and slowly accumulating. JShell's stop() asks the engine to interrupt the in-flight
            // snippet at engine level, which the JShell evaluator honours promptly.
            try {
              jshell.stop();
            } catch (RuntimeException jshellStopErr) {
              // jshell.stop() is documented best-effort; failure here just means we leak this
              // one thread. Log and proceed so the outer dispatch path doesn't wedge.
              jshellStopErr.printStackTrace(captureErr);
            }
            evalThread.join(Duration.ofMillis(1000));
          }
          captureErr.println("Execution timed out");
          exitCode.set(1);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        exitCode.set(1);
      }
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }

    captureOut.flush();
    captureErr.flush();

    var result = new LinkedHashMap<String, Object>();
    result.put("stdout", stdoutCapture.toString(StandardCharsets.UTF_8));
    result.put("stderr", stderrCapture.toString(StandardCharsets.UTF_8));
    result.put("exitCode", exitCode.get());
    result.put("submitted", submittedValue);
    if (Boolean.TRUE.equals(captureBindings)) {
      result.put("bindings", collectBindings(maxBindingValueChars, maxBindingSnapshotChars));
    }
    return result;
  }

  /**
   * Snapshot every user-declared {@code var} in JShell, filtered to exclude harness-internal {@code
   * __}-prefixed names, with each value's {@code toString} repr capped per-value and the total
   * snapshot capped to a budget. The repr is whatever JShell's {@code varValue} returns (which is
   * itself the runtime {@code toString}); a custom {@code toString} that throws gets its message
   * folded into the value as {@code "<error: ...>"} rather than aborting the snapshot.
   */
  Map<String, String> collectBindings(int maxValueChars, int maxSnapshotChars) {
    var snapshot = new LinkedHashMap<String, String>();
    var totalChars = 0;
    var snippets = jshell.variables().toList();
    for (var snippet : snippets) {
      var name = snippet.name();
      if (name.startsWith("__")) {
        continue;
      }
      String repr;
      try {
        repr = jshell.varValue(snippet);
      } catch (Throwable e) {
        // Catch Throwable here (not just Exception): a malicious toString() can throw
        // StackOverflowError, OutOfMemoryError, or AssertionError. Aborting the snapshot would
        // kill the response with no bindings map, and propagate out of doExecute into the virtual
        // thread's uncaught handler. The "<error: …>" stub is a recoverable substitute regardless
        // of the failure mode.
        repr = "<error: " + e.getClass().getSimpleName() + ": " + e.getMessage() + ">";
      }
      if (repr == null) {
        repr = "null";
      }
      if (maxValueChars > 0 && repr.length() > maxValueChars) {
        repr = repr.substring(0, maxValueChars) + "... (len=" + repr.length() + ")";
      }
      if (maxSnapshotChars > 0 && totalChars + name.length() + repr.length() > maxSnapshotChars) {
        snapshot.put(
            "__truncated__",
            "(snapshot exceeded " + maxSnapshotChars + " chars; remaining vars dropped)");
        break;
      }
      totalChars += name.length() + repr.length();
      snapshot.put(name, repr);
    }
    return snapshot;
  }

  private boolean evalCode(String code, PrintStream out, PrintStream err) {
    var analysis = jshell.sourceCodeAnalysis();
    var remaining = code;
    var success = true;

    while (!remaining.isEmpty()) {
      var info = analysis.analyzeCompletion(remaining);
      if (info.completeness() == SourceCodeAnalysis.Completeness.EMPTY) {
        break;
      }
      var events = jshell.eval(info.source());

      for (var event : events) {
        if (event.status() == Snippet.Status.REJECTED) {
          jshell.diagnostics(event.snippet()).forEach(d -> err.println(d.getMessage(null)));
          success = false;
        }
        if (event.exception() != null) {
          event.exception().printStackTrace(err);
          success = false;
        }
        if (event.value() != null
            && (event.snippet().subKind() == Snippet.SubKind.TEMP_VAR_EXPRESSION_SUBKIND
                || event.snippet().kind() == Snippet.Kind.EXPRESSION)) {
          out.println(event.value());
        }
      }

      remaining = info.remaining();
    }

    return success;
  }
}
