/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.protocol.ProcessTransport;
import ai.singlr.repl.protocol.RpcChannel;
import ai.singlr.repl.protocol.RpcMessage;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class JvmSandboxTest {

  @Test
  void buildLaunchCommandIncludesMainClassAndClasspath() {
    var config = JvmSandboxConfig.defaults();
    var cmd = JvmSandbox.buildLaunchCommand("/fake/java", config);
    assertEquals("/fake/java", cmd.get(0));
    assertTrue(cmd.contains("-cp"), "must pass -cp so non-JPMS callers work");
    assertTrue(
        cmd.contains("ai.singlr.repl.sandbox.JvmSandboxBootstrap"),
        "main class must be on the command line");
    assertTrue(
        cmd.stream().anyMatch(a -> a.startsWith("-Xmx")),
        "must set a heap size from config.maxHeapMb");
  }

  @Test
  void buildLaunchCommandRespectsConfigMaxHeap() {
    var config = JvmSandboxConfig.newBuilder().withMaxHeapMb(1234).build();
    var cmd = JvmSandbox.buildLaunchCommand("/fake/java", config);
    assertTrue(cmd.contains("-Xmx1234m"), "config max heap should be applied; got: " + cmd);
  }

  @Test
  void buildLaunchCommandStripsSystemPropertiesFromParent() {
    // The parent JVM under Surefire typically carries several -D args (e.g. basedir, user.dir).
    // These must not propagate to the sandbox subprocess, since they may contain secrets and
    // since the sandbox runs user code with access to System.getProperties().
    var parentArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
    var config = JvmSandboxConfig.defaults();
    var cmd = JvmSandbox.buildLaunchCommand("/fake/java", config);
    var parentHasDArgs = parentArgs.stream().anyMatch(a -> a.startsWith("-D"));
    if (parentHasDArgs) {
      assertTrue(
          cmd.stream().noneMatch(a -> a.startsWith("-D")),
          "no -D args should propagate to subprocess; got: " + cmd);
    }
  }

  @Test
  void buildLaunchCommandFiltersParentHeapArgs() {
    // Parent's -Xmx/-Xms should not leak into the subprocess because the caller sets its own heap.
    // buildLaunchCommand pulls parent args via ManagementFactory; we can't easily inject custom
    // parent args from a test, but we can assert the ones this JVM was started with don't make the
    // subprocess command list have conflicting -Xmx entries.
    var config = JvmSandboxConfig.newBuilder().withMaxHeapMb(512).build();
    var cmd = JvmSandbox.buildLaunchCommand("/fake/java", config);
    var xmxCount = cmd.stream().filter(a -> a.startsWith("-Xmx")).count();
    assertEquals(1, xmxCount, "exactly one -Xmx expected; got: " + cmd);
  }

  @Test
  void shouldPropagateJvmArgFiltersHeapArgs() {
    assertFalse(JvmSandbox.shouldPropagateJvmArg("-Xmx512m"));
    assertFalse(JvmSandbox.shouldPropagateJvmArg("-Xms128m"));
  }

  @Test
  void shouldPropagateJvmArgFiltersAgentArgs() {
    assertFalse(JvmSandbox.shouldPropagateJvmArg("-javaagent:/path/to/agent.jar"));
    assertFalse(JvmSandbox.shouldPropagateJvmArg("-agentlib:jdwp=transport=dt_socket"));
    assertFalse(JvmSandbox.shouldPropagateJvmArg("-agentpath:/path/to/libagent.so"));
  }

  @Test
  void shouldPropagateJvmArgFiltersSystemProperties() {
    assertFalse(JvmSandbox.shouldPropagateJvmArg("-Dauth.token=sekrit"));
    assertFalse(JvmSandbox.shouldPropagateJvmArg("-Djavax.net.ssl.trustStorePassword=changeit"));
    assertFalse(JvmSandbox.shouldPropagateJvmArg("-Dfile.encoding=UTF-8"));
  }

  @Test
  void shouldPropagateJvmArgPropagatesOtherArgs() {
    assertTrue(JvmSandbox.shouldPropagateJvmArg("--module-path"));
    assertTrue(JvmSandbox.shouldPropagateJvmArg("--enable-preview"));
    assertTrue(JvmSandbox.shouldPropagateJvmArg("--add-opens=java.base/java.lang=ALL-UNNAMED"));
    assertTrue(JvmSandbox.shouldPropagateJvmArg("-XX:+UseZGC"));
  }

  @Test
  void parentUsesModulePathDetectsAllForms() {
    assertTrue(JvmSandbox.parentUsesModulePath(List.of("--module-path", "/libs")));
    assertTrue(JvmSandbox.parentUsesModulePath(List.of("--module-path=/libs")));
    assertTrue(JvmSandbox.parentUsesModulePath(List.of("-p", "/libs")));
    assertTrue(JvmSandbox.parentUsesModulePath(List.of("-p=/libs")));
  }

  @Test
  void parentUsesModulePathReturnsFalseWhenAbsent() {
    assertFalse(JvmSandbox.parentUsesModulePath(List.of("-cp", "/libs", "-Xmx1g")));
    assertFalse(JvmSandbox.parentUsesModulePath(List.of()));
  }

  @Test
  void factoryWithNullConfigThrows() {
    assertThrows(IllegalArgumentException.class, () -> JvmSandbox.factory(null));
  }

  @Test
  void factoryCreatesNonNull() {
    var factory = JvmSandbox.factory(JvmSandboxConfig.defaults());
    assertNotNull(factory);
  }

  @Test
  void defaultFactoryCreatesNonNull() {
    var factory = JvmSandbox.factory();
    assertNotNull(factory);
  }

  @Test
  void executeOnDeadSandboxReturnsFailure() throws Exception {
    var pb = new ProcessBuilder("true");
    var process = pb.start();
    process.waitFor();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    var result = sandbox.execute(ExecutionRequest.java("1+1"));

    assertFalse(result.succeeded());
    assertTrue(result.stderr().contains("not alive"));

    sandbox.close();
  }

  @Test
  void closeDestroysProcess() throws Exception {
    var pb = new ProcessBuilder("sleep", "5");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    assertTrue(sandbox.isAlive());

    sandbox.close();

    assertFalse(sandbox.isAlive());
    assertFalse(process.isAlive());
  }

  @Test
  void shutdownHookKillsLeakedProcess() throws Exception {
    var pb = new ProcessBuilder("sleep", "30");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    assertTrue(process.isAlive());
    sandbox.destroyOnJvmShutdown();
    assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    assertFalse(process.isAlive());

    sandbox.close();
  }

  @Test
  void shutdownHookIsNoopAfterClose() throws Exception {
    var pb = new ProcessBuilder("sleep", "30");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    sandbox.close();
    assertFalse(process.isAlive());
    sandbox.destroyOnJvmShutdown();
    assertFalse(process.isAlive());
  }

  @Test
  void doubleCloseIsSafe() throws Exception {
    var pb = new ProcessBuilder("sleep", "5");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    sandbox.close();
    sandbox.close();

    assertFalse(sandbox.isAlive());
  }

  @Test
  void accessors() throws Exception {
    var pb = new ProcessBuilder("sleep", "5");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    assertEquals(process, sandbox.process());
    assertEquals(transport, sandbox.transport());
    assertEquals(channel, sandbox.channel());

    sandbox.close();
  }

  @Test
  void executeWithRequestTimeout() throws Exception {
    var pb = new ProcessBuilder("sleep", "5");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofMillis(100));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    var result =
        sandbox.execute(
            ExecutionRequest.newBuilder()
                .withCode("1+1")
                .withTimeout(Duration.ofMillis(100))
                .build());

    assertEquals(1, result.exitCode());

    sandbox.close();
  }

  @Test
  void executeWithDefaultTimeout() throws Exception {
    // When request has no timeout, the sandbox config timeout is used
    var pb = new ProcessBuilder("sleep", "5");
    var process = pb.start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofMillis(100));
    var config = JvmSandboxConfig.newBuilder().withExecutionTimeout(Duration.ofMillis(100)).build();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    // Request with null timeout — should use config default
    var result = sandbox.execute(ExecutionRequest.java("1+1"));

    assertEquals(1, result.exitCode());

    sandbox.close();
  }

  @Test
  void executeSuccessWithMapResult() throws Exception {
    // Simulate a process that responds with a proper RPC response containing a Map result
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    // Virtual thread to simulate the sandbox responding
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                // Read the request from stdin (we need to consume it to unblock)
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  // Parse the request to get the id
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    // Write a response with RPC prefix
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(
                                req.id(),
                                Map.of("stdout", "hello world", "stderr", "", "exitCode", 0)));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // Test may close streams
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("println(\"hello world\")"));

    assertEquals(0, result.exitCode());
    assertTrue(result.stdout().contains("hello world"));

    sandbox.close();
  }

  @Test
  void executeSuccessWithNonMapResult() throws Exception {
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(req.id(), "just a string"));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // Test may close streams
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("x"));

    assertEquals(0, result.exitCode());
    assertEquals("just a string", result.stdout());

    sandbox.close();
  }

  @Test
  void executeSuccessWithCapturedStdoutAndMapResult() throws Exception {
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    // Write some non-RPC output first (captured stdout), then the RPC response
                    processStdout.write("print output\n".getBytes(StandardCharsets.UTF_8));
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(
                                req.id(),
                                Map.of("stdout", "rpc stdout", "stderr", "", "exitCode", 0)));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // Test may close streams
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("println()"));

    assertEquals(0, result.exitCode());
    // Combined: captured stdout + rpc stdout
    assertTrue(result.stdout().contains("print output"));
    assertTrue(result.stdout().contains("rpc stdout"));

    sandbox.close();
  }

  @Test
  void executeSuccessMapWithSubmitted() throws Exception {
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(
                                req.id(),
                                Map.of(
                                    "stdout",
                                    "",
                                    "stderr",
                                    "warning",
                                    "exitCode",
                                    1,
                                    "submitted",
                                    "answer")));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // expected on close
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("code"));

    assertEquals(1, result.exitCode());
    assertEquals("warning", result.stderr());
    assertEquals("answer", result.submitted());

    sandbox.close();
  }

  @Test
  void executeSuccessWithCapturedStdoutOnlyEmptyMapStdout() throws Exception {
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    processStdout.write("captured line\n".getBytes(StandardCharsets.UTF_8));
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(
                                req.id(), Map.of("stdout", "", "stderr", "", "exitCode", 0)));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // expected
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("x"));

    assertEquals(0, result.exitCode());
    assertEquals("captured line", result.stdout());

    sandbox.close();
  }

  @Test
  void executeSuccessMapWithNonStringFields() throws Exception {
    // Cover branches where map values are NOT String/Number (fallback to defaults)
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    // Return a map with non-standard types for stdout/stderr/exitCode
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(
                                req.id(),
                                Map.of("stdout", 123, "stderr", true, "exitCode", "not-a-number")));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // expected on close
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("x"));

    // Non-string stdout/stderr should default to ""
    assertEquals("", result.stdout());
    assertEquals("", result.stderr());
    // Non-number exitCode should default to 0
    assertEquals(0, result.exitCode());

    sandbox.close();
  }

  @Test
  void executeSuccessNonMapResultWithCapturedStdout() throws Exception {
    // Cover the branch: capturedStdout is not empty AND result is not a Map
    var pipedToTransport = new PipedInputStream();
    var processStdout = new PipedOutputStream(pipedToTransport);
    var pipedFromTransport = new PipedOutputStream();
    var processStdin = new PipedInputStream(pipedFromTransport);

    var transport = new ProcessTransport(pipedToTransport, pipedFromTransport);
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(5));
    var config = JvmSandboxConfig.defaults();
    var process = new ProcessBuilder("sleep", "5").start();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                var reader =
                    new java.io.BufferedReader(
                        new java.io.InputStreamReader(processStdin, StandardCharsets.UTF_8));
                var line = reader.readLine();
                if (line != null) {
                  var reqJson = ProcessTransport.deserializeMessage(line);
                  if (reqJson instanceof RpcMessage.Request req) {
                    // Write captured stdout first
                    processStdout.write("captured output\n".getBytes(StandardCharsets.UTF_8));
                    // Return a non-map result (a string)
                    var respJson =
                        ProcessTransport.serializeMessage(
                            new RpcMessage.Response(req.id(), "plain result"));
                    processStdout.write(
                        (ProcessTransport.RPC_PREFIX + respJson + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                    processStdout.flush();
                  }
                }
              } catch (IOException e) {
                // expected on close
              }
            });

    var result = sandbox.execute(ExecutionRequest.java("x"));

    // When captured stdout is not empty and result is not a Map, capturedStdout wins
    assertEquals("captured output", result.stdout());
    assertEquals(0, result.exitCode());

    sandbox.close();
  }

  @Test
  void createStaticMethodLaunchesSubprocess() {
    // The create() method tries to start a JVM subprocess with the bootstrap class
    // which doesn't exist yet, so it will fail - but we exercise the code path
    var config = JvmSandboxConfig.defaults();
    var registry = new HostFunctionRegistry();

    // The bootstrap class doesn't exist, so this will either:
    // 1. Start the process but it will fail immediately (no main class)
    // 2. The channel reader will detect the process died
    // Either way, we cover the create() code path
    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(config, registry);
      // If it gets here, the process started (but may die soon)
      assertNotNull(sandbox);
      assertTrue(registry.isFrozen());
    } finally {
      if (sandbox != null) {
        sandbox.close();
      }
    }
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void endToEndSubprocessCallsHostBridgeAndSubmits() {
    // Real end-to-end: launch a sandbox subprocess, evaluate JShell code that calls
    // HostBridge.submit, confirm the submitted value flows back. This is the regression test
    // for Kubera's F1/F2/F3 — if any of those bugs reappears, this test fails.
    var submittedHolder = new AtomicReference<>();
    var registry = new HostFunctionRegistry();
    registry.register(
        new ai.singlr.repl.host.HostFunction(
            "submit",
            "stub submit for the JvmSandbox end-to-end test",
            params -> {
              submittedHolder.set(params.get("output"));
              return null;
            }));
    var config =
        JvmSandboxConfig.newBuilder()
            .withCallTimeout(Duration.ofSeconds(45))
            .withExecutionTimeout(Duration.ofSeconds(30))
            .build();

    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(config, registry);
      assertTrue(sandbox.isAlive(), "subprocess should be running after create");

      var request =
          ExecutionRequest.newBuilder()
              .withCode("submit(\"hello-from-sandbox\");")
              .withTimeout(Duration.ofSeconds(30))
              .build();
      var result = sandbox.execute(request);

      assertEquals(0, result.exitCode(), "exitCode != 0; stderr was:\n" + result.stderr());
      assertEquals("hello-from-sandbox", submittedHolder.get());
    } finally {
      if (sandbox != null) {
        var proc = sandbox.process();
        sandbox.close();
        assertFalse(proc.isAlive(), "subprocess should be dead after sandbox.close()");
      }
    }
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void endToEndSubprocessInvokesSynthesizedCustomHostFunction() {
    // The full Skill-host-function path under one test: register a custom HostFunction with
    // declared parameters, launch a subprocess, have it execute Java that calls the synthesized
    // wrapper directly (marketQuote("AAPL")), and verify the handler was invoked with the
    // expected args. Closes Kubera's "skill host functions are not callable from JShell" gap.
    var capturedArgs = new AtomicReference<Map<String, Object>>();
    var registry = new HostFunctionRegistry();
    registry.register(
        new ai.singlr.repl.host.HostFunction(
            "marketQuote",
            "Get a stock quote",
            List.of(
                ai.singlr.repl.host.HostParameter.required(
                    "ticker", ai.singlr.core.tool.ParameterType.STRING, "Ticker symbol"),
                ai.singlr.repl.host.HostParameter.optional(
                    "limit", ai.singlr.core.tool.ParameterType.INTEGER, "Max bars")),
            params -> {
              capturedArgs.set(params);
              return Map.of("price", 234.56, "ticker", params.get("ticker"));
            }));
    var config =
        JvmSandboxConfig.newBuilder()
            .withCallTimeout(Duration.ofSeconds(45))
            .withExecutionTimeout(Duration.ofSeconds(30))
            .build();

    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(config, registry);
      assertTrue(sandbox.isAlive(), "subprocess should be running after create");

      // Sandbox code calls the synthesized typed wrapper. Note we deliberately do NOT pass an
      // import or fully-qualified name — the synthesizer installs marketQuote at top level.
      var request =
          ExecutionRequest.newBuilder()
              .withCode(
                  """
                  var q = marketQuote("AAPL", 5L);
                  println(q);
                  """)
              .withTimeout(Duration.ofSeconds(30))
              .build();
      var result = sandbox.execute(request);

      assertEquals(0, result.exitCode(), "exitCode != 0; stderr was:\n" + result.stderr());
      assertNotNull(capturedArgs.get(), "handler must have been invoked");
      assertEquals("AAPL", capturedArgs.get().get("ticker"));
      assertEquals(5L, ((Number) capturedArgs.get().get("limit")).longValue());
      // The handler's return value should appear in the println output.
      assertTrue(result.stdout().contains("price"));
      assertTrue(result.stdout().contains("234.56"));
    } finally {
      if (sandbox != null) {
        var proc = sandbox.process();
        sandbox.close();
        assertFalse(proc.isAlive(), "subprocess should be dead after sandbox.close()");
      }
    }
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void endToEndSubprocessReturnsBindingsSnapshot() {
    // Variables bound during execute_code should come back in ExecutionResult.bindings(),
    // filtered to exclude __-prefixed harness internals and capped per-value.
    var registry = new HostFunctionRegistry();
    var config =
        JvmSandboxConfig.newBuilder()
            .withCallTimeout(Duration.ofSeconds(45))
            .withExecutionTimeout(Duration.ofSeconds(30))
            .build();

    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(config, registry);
      var request =
          ExecutionRequest.newBuilder()
              .withCode(
                  """
                  var macro = "Fed paused, VIX=18.5";
                  var count = 42;
                  var __internal = "should be hidden";
                  """)
              .withTimeout(Duration.ofSeconds(30))
              .build();
      var result = sandbox.execute(request, ExecuteParams.DEFAULT);

      assertEquals(0, result.exitCode(), "stderr was:\n" + result.stderr());
      assertNotNull(result.bindings());
      assertTrue(result.bindings().containsKey("macro"), "user var present");
      assertTrue(result.bindings().containsKey("count"), "user var present");
      assertFalse(
          result.bindings().containsKey("__internal"), "harness-internal __-prefixed var hidden");
      // JShell varValue strings include quotes for String values.
      assertTrue(result.bindings().get("macro").contains("Fed paused"));
      assertTrue(result.bindings().get("count").contains("42"));
    } finally {
      if (sandbox != null) {
        sandbox.close();
      }
    }
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void endToEndSubprocessRespectsBindingValueCap() {
    var registry = new HostFunctionRegistry();
    var config =
        JvmSandboxConfig.newBuilder()
            .withCallTimeout(Duration.ofSeconds(45))
            .withExecutionTimeout(Duration.ofSeconds(30))
            .build();

    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(config, registry);
      var request =
          ExecutionRequest.newBuilder()
              .withCode(
                  """
                  var huge = "x".repeat(5000);
                  var small = 7;
                  """)
              .withTimeout(Duration.ofSeconds(30))
              .build();
      var executeParams = new ExecuteParams(true, /* perValue= */ 50, 16 * 1024);
      var result = sandbox.execute(request, executeParams);

      assertEquals(0, result.exitCode(), "stderr was:\n" + result.stderr());
      var hugeRepr = result.bindings().get("huge");
      assertNotNull(hugeRepr);
      // 50-char cap + truncation marker — the recorded repr is bounded.
      assertTrue(
          hugeRepr.length() < 200,
          "huge var must be capped well below its real length, got len=" + hugeRepr.length());
      assertTrue(hugeRepr.contains("(len=5002)"), "marker should show real length");
      // small var fits comfortably.
      assertTrue(result.bindings().get("small").contains("7"));
    } finally {
      if (sandbox != null) {
        sandbox.close();
      }
    }
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void endToEndSubprocessOmitsBindingsWhenDisabled() {
    var registry = new HostFunctionRegistry();
    var config =
        JvmSandboxConfig.newBuilder()
            .withCallTimeout(Duration.ofSeconds(45))
            .withExecutionTimeout(Duration.ofSeconds(30))
            .build();

    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(config, registry);
      var request =
          ExecutionRequest.newBuilder()
              .withCode("var x = 1;")
              .withTimeout(Duration.ofSeconds(30))
              .build();
      var result = sandbox.execute(request, ExecuteParams.DISABLED);

      assertEquals(0, result.exitCode());
      assertTrue(result.bindings().isEmpty(), "DISABLED params produce empty bindings map");
    } finally {
      if (sandbox != null) {
        sandbox.close();
      }
    }
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void endToEndSubprocessReturnsExecutedCodeOnResult() {
    // 1.1.5 ask #3: ExecutionResult carries the source code that ran. Capture is parent-side
    // (no protocol change) — JvmSandbox sets executedCode from ExecutionRequest.code() before
    // returning. This is the substrate for live "user watches the agent think" UX panels that
    // show code + bindings side-by-side.
    var registry = new HostFunctionRegistry();
    var config =
        JvmSandboxConfig.newBuilder()
            .withCallTimeout(Duration.ofSeconds(45))
            .withExecutionTimeout(Duration.ofSeconds(30))
            .build();

    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(config, registry);
      var snippet = "var x = 42; var y = \"hello\"; println(x + \" \" + y);";
      var request =
          ExecutionRequest.newBuilder()
              .withCode(snippet)
              .withTimeout(Duration.ofSeconds(30))
              .build();
      var result = sandbox.execute(request);

      assertEquals(0, result.exitCode(), "stderr was:\n" + result.stderr());
      assertEquals(
          snippet, result.executedCode(), "executedCode must be the exact source the sandbox ran");
      assertTrue(result.stdout().contains("42 hello"));
    } finally {
      if (sandbox != null) {
        sandbox.close();
      }
    }
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  void endToEndSubprocessHandlesZeroArgCustomFunction() {
    // Zero-param functions synthesize as bare-call wrappers like listSymbols(). Verify the
    // synthesis produces something a model can actually invoke from JShell.
    var registry = new HostFunctionRegistry();
    registry.register(
        new ai.singlr.repl.host.HostFunction(
            "listSymbols", "All known tickers", params -> List.of("AAPL", "GOOG", "MSFT")));
    var config =
        JvmSandboxConfig.newBuilder()
            .withCallTimeout(Duration.ofSeconds(45))
            .withExecutionTimeout(Duration.ofSeconds(30))
            .build();

    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(config, registry);
      var request =
          ExecutionRequest.newBuilder()
              .withCode(
                  """
                  // listSymbols() returns Object — wrappers always return Object since the
                  // synthesizer doesn't know the handler's return shape. The model casts to the
                  // documented type from the function description.
                  var symbols = (java.util.List<String>) listSymbols();
                  println(symbols.size());
                  println(symbols);
                  """)
              .withTimeout(Duration.ofSeconds(30))
              .build();
      var result = sandbox.execute(request);
      assertEquals(0, result.exitCode(), "exitCode != 0; stderr was:\n" + result.stderr());
      assertTrue(result.stdout().contains("3"));
      assertTrue(result.stdout().contains("AAPL"));
    } finally {
      if (sandbox != null) {
        sandbox.close();
      }
    }
  }
}
