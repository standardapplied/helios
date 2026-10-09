/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.core.tool.ParameterType;
import com.standardapplied.helios.repl.host.HostFunction;
import com.standardapplied.helios.repl.host.HostFunctionRegistry;
import com.standardapplied.helios.repl.host.HostParameter;
import com.standardapplied.helios.repl.protocol.ProcessTransport;
import com.standardapplied.helios.repl.protocol.RpcChannel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * No test depends on how long a subprocess takes. A sandbox launched for real gets {@link
 * #END_TO_END}: the hang guard bounds its startup and each call, and a snippet's own timeout and a
 * stopped snippet's grace are {@link #BEYOND_HANG_GUARD} unless their expiry is what the test
 * exercises. A child that stands in for a subprocess is {@code sleep 600}, which cannot end on its
 * own within the hang guard, so its death proves the kill.
 */
class JvmSandboxTest {

  private static final Duration BEYOND_HANG_GUARD = Await.HANG_GUARD.multipliedBy(5);

  private static final String FORGED_FRAME_ID = "\"id\":\"forged\"";

  private static final JvmSandboxConfig END_TO_END = endToEnd(BEYOND_HANG_GUARD);

  @AfterEach
  void leaveNoProcessBehind() {
    ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
  }

  @Test
  void buildLaunchCommandIncludesMainClassAndClasspath() {
    var config = JvmSandboxConfig.defaults();
    var cmd = SandboxLauncher.buildLaunchCommand("/fake/java", config);
    assertEquals("/fake/java", cmd.get(0));
    assertTrue(cmd.contains("-cp"), "must pass -cp so non-JPMS callers work");
    assertTrue(
        cmd.contains("com.standardapplied.helios.repl.sandbox.JvmSandboxBootstrap"),
        "main class must be on the command line");
    assertTrue(
        cmd.stream().anyMatch(a -> a.startsWith("-Xmx")),
        "must set a heap size from config.maxHeapMb");
  }

  /**
   * Regression for the cron.jar / relative-classpath bug: when the host JVM is launched as {@code
   * java -jar target/cron.jar}, {@code System.getProperty("java.class.path")} returns the bare
   * relative path {@code "target/cron.jar"}. The sandbox subprocess then sets its working directory
   * to a private {@code /tmp/helios-sandbox-cwd-*} (so the model can't see host files), which means
   * {@code target/cron.jar} no longer resolves. The subprocess dies with {@code
   * ClassNotFoundException: com.standardapplied.helios.repl.sandbox.JvmSandboxBootstrap}, the
   * host's accept loop times out 30 s later, and we surface a generic IOException to the caller.
   *
   * <p>The fix: every relative entry in {@code java.class.path} must be resolved against the host
   * JVM's current working directory before being passed to the subprocess as {@code -cp}.
   */
  @Test
  void buildLaunchCommandResolvesRelativeClasspathEntriesToAbsolute() {
    var originalCp = System.getProperty("java.class.path");
    var sep = System.getProperty("path.separator");
    var hostCwd = Path.of("").toAbsolutePath();
    try {
      System.setProperty(
          "java.class.path", "target/cron.jar" + sep + "libs/repository-1.0.0-SNAPSHOT.jar");
      var config = JvmSandboxConfig.defaults();
      var cmd = SandboxLauncher.buildLaunchCommand("/fake/java", config);

      var cpIdx = cmd.indexOf("-cp");
      assertTrue(cpIdx >= 0, "command must contain a -cp flag");
      var cpArg = cmd.get(cpIdx + 1);

      for (var entry : cpArg.split(java.util.regex.Pattern.quote(sep))) {
        assertTrue(
            Path.of(entry).isAbsolute(),
            () ->
                "classpath entry must be absolute — subprocess cwd differs from host JVM cwd, "
                    + "so relative entries fail to resolve. Got: '"
                    + entry
                    + "' in cp='"
                    + cpArg
                    + "'");
      }
      assertTrue(
          cpArg.contains(hostCwd.resolve("target/cron.jar").toString()),
          () -> "relative 'target/cron.jar' must resolve against host cwd; got: " + cpArg);
      assertTrue(
          cpArg.contains(hostCwd.resolve("libs/repository-1.0.0-SNAPSHOT.jar").toString()),
          () -> "every relative entry must resolve; got: " + cpArg);
    } finally {
      System.setProperty("java.class.path", originalCp);
    }
  }

  /**
   * Boundary: already-absolute entries must pass through unchanged, including when they're mixed
   * with relative ones. Catches a partial fix that resolves everything (even absolute paths) or one
   * that breaks when the classpath has zero relative entries.
   */
  @Test
  void buildLaunchCommandLeavesAbsoluteClasspathEntriesUnchanged() {
    var originalCp = System.getProperty("java.class.path");
    var sep = System.getProperty("path.separator");
    try {
      var absoluteEntry = "/opt/lib/foo.jar";
      var relativeEntry = "build/bar.jar";
      System.setProperty("java.class.path", absoluteEntry + sep + relativeEntry);
      var cmd = SandboxLauncher.buildLaunchCommand("/fake/java", JvmSandboxConfig.defaults());
      var cpArg = cmd.get(cmd.indexOf("-cp") + 1);
      assertTrue(
          cpArg.contains(absoluteEntry),
          () -> "already-absolute entry must pass through verbatim; got: " + cpArg);
      var hostCwd = Path.of("").toAbsolutePath();
      assertTrue(
          cpArg.contains(hostCwd.resolve(relativeEntry).toString()),
          () -> "relative entry must be resolved against host cwd; got: " + cpArg);
    } finally {
      System.setProperty("java.class.path", originalCp);
    }
  }

  @Test
  void buildLaunchCommandRespectsConfigMaxHeap() {
    var config = JvmSandboxConfig.newBuilder().withMaxHeapMb(1234).build();
    var cmd = SandboxLauncher.buildLaunchCommand("/fake/java", config);
    assertTrue(cmd.contains("-Xmx1234m"), "config max heap should be applied; got: " + cmd);
  }

  @Test
  void buildLaunchCommandStripsSystemPropertiesFromParent() {
    // The parent JVM under Surefire typically carries several -D args (e.g. basedir, user.dir).
    // These must not propagate to the sandbox subprocess, since they may contain secrets and
    // since the sandbox runs user code with access to System.getProperties().
    var parentArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
    var config = JvmSandboxConfig.defaults();
    var cmd = SandboxLauncher.buildLaunchCommand("/fake/java", config);
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
    var cmd = SandboxLauncher.buildLaunchCommand("/fake/java", config);
    var xmxCount = cmd.stream().filter(a -> a.startsWith("-Xmx")).count();
    assertEquals(1, xmxCount, "exactly one -Xmx expected; got: " + cmd);
  }

  @Test
  void hostNativeAccessIsNotGrantedToSandbox() {
    assertFalse(SandboxLauncher.shouldPropagateJvmArg("--enable-native-access=ALL-UNNAMED"));
    assertFalse(
        SandboxLauncher.shouldPropagateJvmArg(
            "--enable-native-access=com.standardapplied.helios.session"));
    assertTrue(SandboxLauncher.shouldPropagateJvmArg("--illegal-native-access=deny"));
    var command = SandboxLauncher.buildLaunchCommand("/fake/java", JvmSandboxConfig.defaults());
    assertTrue(command.stream().noneMatch(arg -> arg.startsWith("--enable-native-access")));
  }

  @Test
  void shouldPropagateJvmArgFiltersHeapArgs() {
    assertFalse(SandboxLauncher.shouldPropagateJvmArg("-Xmx512m"));
    assertFalse(SandboxLauncher.shouldPropagateJvmArg("-Xms128m"));
  }

  @Test
  void shouldPropagateJvmArgFiltersAgentArgs() {
    assertFalse(SandboxLauncher.shouldPropagateJvmArg("-javaagent:/path/to/agent.jar"));
    assertFalse(SandboxLauncher.shouldPropagateJvmArg("-agentlib:jdwp=transport=dt_socket"));
    assertFalse(SandboxLauncher.shouldPropagateJvmArg("-agentpath:/path/to/libagent.so"));
  }

  @Test
  void shouldPropagateJvmArgFiltersSystemProperties() {
    assertFalse(SandboxLauncher.shouldPropagateJvmArg("-Dauth.token=sekrit"));
    assertFalse(
        SandboxLauncher.shouldPropagateJvmArg("-Djavax.net.ssl.trustStorePassword=changeit"));
    assertFalse(SandboxLauncher.shouldPropagateJvmArg("-Dfile.encoding=UTF-8"));
  }

  @Test
  void shouldPropagateJvmArgPropagatesOtherArgs() {
    assertTrue(SandboxLauncher.shouldPropagateJvmArg("--module-path"));
    assertTrue(SandboxLauncher.shouldPropagateJvmArg("--enable-preview"));
    assertTrue(
        SandboxLauncher.shouldPropagateJvmArg("--add-opens=java.base/java.lang=ALL-UNNAMED"));
    assertTrue(SandboxLauncher.shouldPropagateJvmArg("-XX:+UseZGC"));
  }

  @Test
  void parentUsesModulePathDetectsAllForms() {
    assertTrue(SandboxLauncher.parentUsesModulePath(List.of("--module-path", "/libs")));
    assertTrue(SandboxLauncher.parentUsesModulePath(List.of("--module-path=/libs")));
    assertTrue(SandboxLauncher.parentUsesModulePath(List.of("-p", "/libs")));
    assertTrue(SandboxLauncher.parentUsesModulePath(List.of("-p=/libs")));
  }

  @Test
  void parentUsesModulePathReturnsFalseWhenAbsent() {
    assertFalse(SandboxLauncher.parentUsesModulePath(List.of("-cp", "/libs", "-Xmx1g")));
    assertFalse(SandboxLauncher.parentUsesModulePath(List.of()));
  }

  @Test
  void factoryWithNullConfigThrows() {
    var thrown = assertThrows(IllegalArgumentException.class, () -> JvmSandbox.factory(null));
    assertEquals("Config must not be null", thrown.getMessage());
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
    var process = new ProcessBuilder("true").start();
    Await.termination("the process that exits at once", process);
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    var result = sandbox.execute(ExecutionRequest.java("1+1"));

    assertFalse(result.succeeded());
    assertTrue(
        result.stderr().contains("not alive: it exited with code 0"),
        "stderr was: " + result.stderr());

    sandbox.close();
  }

  @Test
  void executeOnClosedSandboxReturnsFailure() {
    var sandbox = JvmSandbox.create(END_TO_END, new HostFunctionRegistry());
    sandbox.close();

    var result = sandbox.execute(ExecutionRequest.java("1+1"));

    assertTrue(
        result.stderr().contains("not alive: the sandbox is closed"),
        "stderr was: " + result.stderr());
  }

  @Test
  void closeDestroysProcess() throws Exception {
    var process = new ProcessBuilder("sleep", "600").start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    assertTrue(sandbox.isAlive());

    sandbox.close();

    assertFalse(sandbox.isAlive());
    Await.termination("the process close() destroys", process);
  }

  @Test
  void shutdownHookKillsLeakedProcess() throws Exception {
    var process = new ProcessBuilder("sleep", "600").start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    assertTrue(process.isAlive());
    sandbox.destroyOnJvmShutdown();
    Await.termination("the leaked process the shutdown hook kills", process);

    sandbox.close();
  }

  @Test
  void closeKillsSubprocessDescendantsNotJustTheParent() throws Exception {
    // Theme E regression test: snippets can call Runtime.exec(...) and spawn descendants. Without
    // process.descendants().forEach(::destroyForcibly) the parent dies on sandbox.close() but its
    // grandchildren survive — an orphaned process leak. This test launches a real sandbox, runs
    // a snippet that forks a long-running child, reports the child PID to a host function, closes
    // the sandbox, and asserts the descendant PID is no longer alive.
    var descendantPidHolder = new AtomicReference<Long>();
    var registry = new HostFunctionRegistry();
    registry.register(
        new HostFunction(
            "reportPid",
            "test capture for descendant PID",
            List.of(HostParameter.required("pid", ParameterType.STRING, "the descendant's PID")),
            params -> {
              descendantPidHolder.set(Long.parseLong(params.get("pid").toString()));
              return null;
            }));
    try (var sandbox = JvmSandbox.create(END_TO_END, registry)) {
      var result =
          sandbox.execute(
              ExecutionRequest.java(
                  "var grandchild = new ProcessBuilder(\"sleep\", \"600\").start();\n"
                      + "reportPid(String.valueOf(grandchild.pid()));"));
      assertEquals(0, result.exitCode(), "snippet failed; stderr was:\n" + result.stderr());
      var descendant = ProcessHandle.of(descendantPidHolder.get()).orElseThrow();
      try {
        assertTrue(descendant.isAlive(), "the descendant must be alive before the sandbox closes");

        sandbox.close();

        Await.value("the descendant to die with the sandbox", descendant.onExit());
      } finally {
        descendant.destroyForcibly();
      }
    }
  }

  @Test
  void destroyOnJvmShutdownKillsDescendantsAsWellAsParent() throws Exception {
    // Same hardening, different entry point: destroyOnJvmShutdown is fired by the JVM shutdown
    // hook to reap a sandbox the application forgot to close(). It must walk descendants too.
    // The existing shutdownHookKillsLeakedProcess test covers parent-only termination via this
    // path — here we extend it to a parent-with-child shape.
    var parentPb = new ProcessBuilder("sh", "-c", "sleep 600 & echo $! ; wait");
    parentPb.redirectErrorStream(true);
    var parent = parentPb.start();
    var firstLine =
        new BufferedReader(new InputStreamReader(parent.getInputStream(), StandardCharsets.UTF_8))
            .readLine();
    assertNotNull(firstLine, "shell did not echo child PID");
    var childPid = Long.parseLong(firstLine.trim());
    var child = ProcessHandle.of(childPid).orElseThrow();
    try {
      assertTrue(child.isAlive(), "the descendant must be alive before the shutdown hook runs");
      var transport = new ProcessTransport(parent.getInputStream(), parent.getOutputStream());
      var channel = new RpcChannel(transport, new HostFunctionRegistry(), BEYOND_HANG_GUARD);
      var sandbox = new JvmSandbox(parent, transport, channel, JvmSandboxConfig.defaults());

      sandbox.destroyOnJvmShutdown();

      Await.termination("the parent the shutdown hook kills", parent);
      Await.value("the descendant to die with its parent", child.onExit());
      sandbox.close();
    } finally {
      child.destroyForcibly();
    }
  }

  @Test
  void shutdownHookIsNoopAfterClose() throws Exception {
    var process = new ProcessBuilder("sleep", "600").start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    sandbox.close();
    Await.termination("the process close() destroys", process);
    sandbox.destroyOnJvmShutdown();
    assertFalse(process.isAlive());
  }

  @Test
  void doubleCloseIsSafe() throws Exception {
    var process = new ProcessBuilder("sleep", "600").start();
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
    var process = new ProcessBuilder("sleep", "600").start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofSeconds(1));
    var config = JvmSandboxConfig.defaults();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    assertEquals(process, sandbox.process());
    assertEquals(transport, sandbox.rpc().transport());
    assertEquals(channel, sandbox.rpc().channel());

    sandbox.close();
  }

  @Test
  void executeWithRequestTimeout() throws Exception {
    var process = new ProcessBuilder("sleep", "600").start();
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
    assertTrue(result.stderr().contains("timed out"), result.stderr());

    sandbox.close();
  }

  @Test
  void executeWithDefaultTimeout() throws Exception {
    // When request has no timeout, the sandbox config timeout is used
    var process = new ProcessBuilder("sleep", "600").start();
    var transport = new ProcessTransport(process.getInputStream(), process.getOutputStream());
    var registry = new HostFunctionRegistry();
    var channel = new RpcChannel(transport, registry, Duration.ofMillis(100));
    var config = JvmSandboxConfig.newBuilder().withExecutionTimeout(Duration.ofMillis(100)).build();
    var sandbox = new JvmSandbox(process, transport, channel, config);

    var result = sandbox.execute(ExecutionRequest.java("1+1"));

    assertEquals(1, result.exitCode());
    assertTrue(result.stderr().contains("timed out"), result.stderr());

    sandbox.close();
  }

  @Test
  void executeSuccessWithMapResult() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(Map.of("stdout", "hello world", "stderr", "", "exitCode", 0));

      var result = fake.sandbox().execute(ExecutionRequest.java("println(\"hello world\")"));

      assertEquals(0, result.exitCode());
      assertTrue(result.stdout().contains("hello world"));
    }
  }

  @Test
  void executeSuccessWithNonMapResult() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext("just a string");

      var result = fake.sandbox().execute(ExecutionRequest.java("x"));

      assertEquals(0, result.exitCode());
      assertEquals("just a string", result.stdout());
    }
  }

  @Test
  void executeSuccessWithCapturedStdoutAndMapResult() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(Map.of("stdout", "rpc stdout", "stderr", "", "exitCode", 0), "print output");

      var result = fake.sandbox().execute(ExecutionRequest.java("println()"));

      assertEquals(0, result.exitCode());
      assertEquals("print output\nrpc stdout", result.stdout());
    }
  }

  @Test
  void executeFailureMapCarriesStderrAndExitCode() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(Map.of("stdout", "", "stderr", "warning", "exitCode", 1));

      var result = fake.sandbox().execute(ExecutionRequest.java("code"));

      assertEquals(1, result.exitCode());
      assertEquals("warning", result.stderr());
    }
  }

  @Test
  void executeSuccessWithCapturedStdoutOnlyEmptyMapStdout() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(Map.of("stdout", "", "stderr", "", "exitCode", 0), "captured line");

      var result = fake.sandbox().execute(ExecutionRequest.java("x"));

      assertEquals(0, result.exitCode());
      assertEquals("captured line", result.stdout());
    }
  }

  /** Map values of the wrong type fall back to an empty string and exit code 0. */
  @Test
  void executeSuccessMapWithNonStringFields() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext(Map.of("stdout", 123, "stderr", true, "exitCode", "not-a-number"));

      var result = fake.sandbox().execute(ExecutionRequest.java("x"));

      assertEquals("", result.stdout());
      assertEquals("", result.stderr());
      assertEquals(0, result.exitCode());
    }
  }

  /** With captured output and a result that is not a map, the captured output wins. */
  @Test
  void executeSuccessNonMapResultWithCapturedStdout() throws Exception {
    try (var fake = new FakeSandboxProcess()) {
      fake.answerNext("plain result", "captured output");

      var result = fake.sandbox().execute(ExecutionRequest.java("x"));

      assertEquals("captured output", result.stdout());
      assertEquals(0, result.exitCode());
    }
  }

  @Test
  void createStaticMethodLaunchesSubprocess() {
    var registry = new HostFunctionRegistry();

    try (var sandbox = JvmSandbox.create(END_TO_END, registry)) {
      assertNotNull(sandbox);
      assertTrue(registry.isFrozen());
    }
  }

  @Test
  void endToEndSubprocessCallsACustomHostFunction() {
    // Real end-to-end: launch a sandbox subprocess, evaluate JShell code that calls a synthesized
    // host-function wrapper, confirm the argument flows back. This is the regression test
    // for Kubera's F1/F2/F3 — if any of those bugs reappears, this test fails.
    var reportedHolder = new AtomicReference<>();
    var registry = new HostFunctionRegistry();
    registry.register(
        new HostFunction(
            "report",
            "stub capture for the JvmSandbox end-to-end test",
            List.of(HostParameter.required("value", ParameterType.STRING, "the reported value")),
            params -> {
              reportedHolder.set(params.get("value"));
              return null;
            }));
    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(END_TO_END, registry);
      assertTrue(sandbox.isAlive(), "subprocess should be running after create");

      var request =
          ExecutionRequest.newBuilder().withCode("report(\"hello-from-sandbox\");").build();
      var result = sandbox.execute(request);

      assertEquals(0, result.exitCode(), "exitCode != 0; stderr was:\n" + result.stderr());
      assertEquals("hello-from-sandbox", reportedHolder.get());
    } finally {
      if (sandbox != null) {
        var proc = sandbox.process();
        sandbox.close();
        Await.termination("the subprocess to die after sandbox.close()", proc);
      }
    }
  }

  @Test
  void endToEndSubprocessInvokesSynthesizedCustomHostFunction() {
    // The full Skill-host-function path under one test: register a custom HostFunction with
    // declared parameters, launch a subprocess, have it execute Java that calls the synthesized
    // wrapper directly (marketQuote("AAPL")), and verify the handler was invoked with the
    // expected args. Closes Kubera's "skill host functions are not callable from JShell" gap.
    var capturedArgs = new AtomicReference<Map<String, Object>>();
    var registry = new HostFunctionRegistry();
    registry.register(
        new HostFunction(
            "marketQuote",
            "Get a stock quote",
            List.of(
                com.standardapplied.helios.repl.host.HostParameter.required(
                    "ticker",
                    com.standardapplied.helios.core.tool.ParameterType.STRING,
                    "Ticker symbol"),
                com.standardapplied.helios.repl.host.HostParameter.optional(
                    "limit",
                    com.standardapplied.helios.core.tool.ParameterType.INTEGER,
                    "Max bars")),
            params -> {
              capturedArgs.set(params);
              return Map.of("price", 234.56, "ticker", params.get("ticker"));
            }));
    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(END_TO_END, registry);
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
        Await.termination("the subprocess to die after sandbox.close()", proc);
      }
    }
  }

  @Test
  void endToEndSubprocessReturnsBindingsSnapshot() {
    // Variables bound during execute_code should come back in ExecutionResult.bindings(),
    // filtered to exclude __-prefixed harness internals and capped per-value.
    var registry = new HostFunctionRegistry();
    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(END_TO_END, registry);
      var request =
          ExecutionRequest.newBuilder()
              .withCode(
                  """
                  var macro = "Fed paused, VIX=18.5";
                  var count = 42;
                  var __internal = "should be hidden";
                  """)
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
  void endToEndSubprocessRespectsBindingValueCap() {
    var registry = new HostFunctionRegistry();
    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(END_TO_END, registry);
      var request =
          ExecutionRequest.newBuilder()
              .withCode(
                  """
                  var huge = "x".repeat(5000);
                  var small = 7;
                  """)
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
  void endToEndSubprocessOmitsBindingsWhenDisabled() {
    var registry = new HostFunctionRegistry();
    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(END_TO_END, registry);
      var request = ExecutionRequest.newBuilder().withCode("var x = 1;").build();
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
  void endToEndSubprocessReturnsExecutedCodeOnResult() {
    // 1.1.5 ask #3: ExecutionResult carries the source code that ran. Capture is parent-side
    // (no protocol change) — JvmSandbox sets executedCode from ExecutionRequest.code() before
    // returning. This is the substrate for live "user watches the agent think" UX panels that
    // show code + bindings side-by-side.
    var registry = new HostFunctionRegistry();
    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(END_TO_END, registry);
      var snippet = "var x = 42; var y = \"hello\"; println(x + \" \" + y);";
      var request = ExecutionRequest.newBuilder().withCode(snippet).build();
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
  void endToEndSubprocessHandlesZeroArgCustomFunction() {
    // Zero-param functions synthesize as bare-call wrappers like listSymbols(). Verify the
    // synthesis produces something a model can actually invoke from JShell.
    var registry = new HostFunctionRegistry();
    registry.register(
        new HostFunction(
            "listSymbols", "All known tickers", params -> List.of("AAPL", "GOOG", "MSFT")));
    JvmSandbox sandbox = null;
    try {
      sandbox = JvmSandbox.create(END_TO_END, registry);
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

  /**
   * A CPU-bound snippet that never checks its interrupt flag cannot end on its own. A result at all
   * proves only that the call returned; the loop's counter reading the same in two follow-up calls
   * proves the timeout ended the snippet, and the follow-up calls succeeding prove the stopped
   * snippet did not leave the sandbox unusable. The counter is declared by an execute of its own,
   * so it exists whether the stop ends the loop before or after the loop starts.
   */
  @Test
  void uninterruptibleSnippetTimesOutWithoutWedgingTheSandbox() {
    try (var sandbox = JvmSandbox.create(END_TO_END, new HostFunctionRegistry())) {
      var declared = sandbox.execute(ExecutionRequest.java("long count = 0;"));
      assertEquals(0, declared.exitCode(), "stderr was:\n" + declared.stderr());
      var tightLoop =
          ExecutionRequest.newBuilder()
              .withCode("while (true) { count++; }")
              .withTimeout(Duration.ofMillis(800))
              .build();

      var result = sandbox.execute(tightLoop);

      assertEquals(1, result.exitCode(), "uninterruptible loop should exit 1");
      assertTrue(
          result.stderr().contains("Execution timed out"),
          "expected timeout marker; stderr was:\n" + result.stderr());
      var firstRead = sandbox.execute(ExecutionRequest.java("count"));
      var secondRead = sandbox.execute(ExecutionRequest.java("count"));
      assertEquals(0, firstRead.exitCode(), "stderr was:\n" + firstRead.stderr());
      assertEquals(firstRead.stdout(), secondRead.stdout());
    }
  }

  /**
   * A snippet blocked entering a monitor that a thread outside its thread group holds cannot be
   * stopped, so the sandbox answers the execute and then exits with code 3. The holder is a virtual
   * thread, never a member of the snippet's group, blocked on a latch nothing releases. The
   * shutdown hook blocks on the same monitor running JDK code alone, which JShell's stop check
   * never reaches, so the sandbox exits only if it runs no hook. The monitor is a {@code Vector}
   * for that reason, and the bindings snapshot is off because the vector's {@code toString} needs
   * it too. The timeout outlasts compiling the statement by far: a stop that landed before the
   * snippet started would end it at its first stop check instead.
   */
  @Test
  void unstoppableSnippetExitsTheSandboxAfterAnswering() {
    try (var sandbox =
        JvmSandbox.create(endToEnd(Duration.ofMillis(300)), new HostFunctionRegistry())) {
      var holdMonitor =
          sandbox.execute(
              ExecutionRequest.java(
                  """
                  var monitor = new java.util.Vector<Object>();
                  var entered = new java.util.concurrent.CountDownLatch(1);
                  Thread.startVirtualThread(() -> {
                    synchronized (monitor) {
                      entered.countDown();
                      try {
                        new java.util.concurrent.CountDownLatch(1).await();
                      } catch (InterruptedException e) {
                      }
                    }
                  });
                  entered.await();
                  Runtime.getRuntime().addShutdownHook(new Thread(monitor::clear));
                  """),
              ExecuteParams.DISABLED);
      assertEquals(0, holdMonitor.exitCode(), "stderr was:\n" + holdMonitor.stderr());

      var result =
          sandbox.execute(
              ExecutionRequest.newBuilder()
                  .withCode("synchronized (monitor) { }")
                  .withTimeout(Await.HANG_GUARD.dividedBy(6))
                  .build(),
              ExecuteParams.DISABLED);

      assertEquals(1, result.exitCode());
      assertTrue(result.stderr().contains("Execution timed out"), result.stderr());
      assertTrue(result.stderr().contains("could not be stopped"), result.stderr());
      Await.termination("the sandbox exiting", sandbox.process());
      assertEquals(3, sandbox.process().exitValue());
      var afterExit = sandbox.execute(ExecutionRequest.java("1 + 1"));
      assertTrue(
          afterExit
              .stderr()
              .contains(
                  "it exited with code 3; the sandbox exited because a timed-out snippet could not"
                      + " be stopped"),
          afterExit.stderr());
      assertFalse(sandbox.isAlive());
    }
  }

  @Test
  void rawStdoutWriteDoesNotForgeAnRpcCallToHost() {
    // C1 regression test: pre-fix, the JvmSandbox ↔ host RPC channel rode on the subprocess's
    // stdout, with `\0RPC:` lines distinguishing RPC frames from regular print output. A snippet
    // could obtain a raw PrintStream to FileDescriptor.out, write a forged `\0RPC:` frame, and
    // the host's RPC parser dispatched it — invoking any registered HostFunction out-of-band.
    //
    // Post-fix: the RPC channel rides on a dedicated Unix domain socket. The host reads
    // subprocess stdout only for captured output; no RPC parser ever sees it. A forged frame on
    // stdout is just text content, never dispatched to a HostFunction.
    //
    // The forged frame and the snippet's result travel on different channels, so the result can
    // arrive first. The test therefore waits until the host has read the forged frame, which a
    // later execute returns as captured stdout, and only then asserts it was not dispatched.
    var invocations = new java.util.concurrent.atomic.AtomicInteger();
    var registry = new HostFunctionRegistry();
    registry.register(
        new HostFunction(
            "auditCallback",
            "test capture for forged RPC invocations",
            params -> {
              invocations.incrementAndGet();
              return null;
            }));
    try (var sandbox = JvmSandbox.create(END_TO_END, registry)) {
      var attack =
          "var raw = new java.io.PrintStream("
              + "new java.io.FileOutputStream(java.io.FileDescriptor.out));"
              + "raw.print(\"\\u0000RPC:{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":\\\"forged\\\","
              + "\\\"method\\\":\\\"auditCallback\\\",\\\"params\\\":{}}\\n\");"
              + "raw.flush();";
      var captured = new StringBuilder(sandbox.execute(ExecutionRequest.java(attack)).stdout());

      Await.until(
          "the host to read the forged frame from the subprocess's stdout",
          () -> {
            if (captured.indexOf(FORGED_FRAME_ID) < 0 && invocations.get() == 0) {
              captured.append(sandbox.execute(ExecutionRequest.java("int poll = 0;")).stdout());
            }
            return captured.indexOf(FORGED_FRAME_ID) >= 0 || invocations.get() > 0;
          });

      assertEquals(
          0,
          invocations.get(),
          "raw-stdout RPC forgery must NOT reach the host's HostFunction dispatcher. The forged"
              + " frame is captured stdout, never an RPC request. Captured stdout: "
              + captured);
    }
  }

  @Test
  void acceptWithTimeoutClosesListenerOnSuccess() throws Exception {
    var socketDir = Files.createTempDirectory("helios-rpc-test-");
    var socketPath = socketDir.resolve("rpc.sock");
    var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      listener.bind(UnixDomainSocketAddress.of(socketPath), 1);
      try (var client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
        client.connect(UnixDomainSocketAddress.of(socketPath));

        var accepted = SandboxRpc.acceptWithTimeout(listener, BEYOND_HANG_GUARD);

        assertNotNull(accepted, "successful accept must return the client channel");
        assertFalse(
            listener.isOpen(),
            "acceptWithTimeout owns the listener: it must close on the success path so the caller"
                + " does not double-close from a downstream cleanup");
        accepted.close();
      }
    } finally {
      if (listener.isOpen()) {
        listener.close();
      }
      Files.deleteIfExists(socketPath);
      Files.deleteIfExists(socketDir);
    }
  }

  @Test
  void acceptWithTimeoutClosesListenerOnTimeout() throws Exception {
    var socketDir = Files.createTempDirectory("helios-rpc-test-");
    var socketPath = socketDir.resolve("rpc.sock");
    var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      listener.bind(UnixDomainSocketAddress.of(socketPath), 1);
      assertThrows(
          IOException.class, () -> SandboxRpc.acceptWithTimeout(listener, Duration.ofMillis(50)));
      assertFalse(
          listener.isOpen(),
          "acceptWithTimeout must close the listener on timeout so the blocked accept thread"
              + " unblocks via AsynchronousCloseException");
    } finally {
      if (listener.isOpen()) {
        listener.close();
      }
      Files.deleteIfExists(socketPath);
      Files.deleteIfExists(socketDir);
    }
  }

  /**
   * The subprocess is given a heap too small for a JVM to start, so it exits without ever
   * connecting and only the startup timeout can end the wait for it.
   */
  @Test
  void subprocessStartupTimeoutHonoursConfigAndSurfacesDurationInError() {
    var startupTimeout = Duration.ofMillis(50);
    var config =
        JvmSandboxConfig.newBuilder()
            .withMaxHeapMb(1)
            .withSubprocessStartupTimeout(startupTimeout)
            .build();
    var registry = new HostFunctionRegistry();
    var thrown =
        assertThrows(
            com.standardapplied.helios.repl.ReplException.class,
            () -> JvmSandbox.create(config, registry));
    assertEquals("Failed to start JVM sandbox subprocess", thrown.getMessage());
    assertNotNull(thrown.getCause(), "expected wrapped cause");
    assertEquals(
        "Subprocess did not connect to the RPC socket within PT0.05S; the launch probably failed"
            + " — check stderr for the cause",
        thrown.getCause().getMessage());
    var causeMessage = thrown.getCause().getMessage();
    assertNotNull(causeMessage, "cause must carry a message");
    assertTrue(
        causeMessage.contains(startupTimeout.toString()),
        "timeout error must include the configured duration ("
            + startupTimeout
            + "); got: "
            + causeMessage);
  }

  @Test
  void reflectionForgesAnRpcCallToHost() {
    // EXPLOIT regression test for the limit C1 does NOT close:
    //
    // C1 moved the RPC channel off the subprocess's stdout onto a dedicated Unix domain socket,
    // so a snippet writing to FileDescriptor.out can no longer forge an RPC frame the host parser
    // sees (covered by rawStdoutWriteDoesNotForgeAnRpcCallToHost). But the underlying capability
    // is still in-JVM and reachable through reflection against HostBridgeState's private
    // fields: a snippet that can call setAccessible(true) on the bridge state's static `instance`
    // and private `realOut` fields can grab the RPC socket's PrintStream and write a forged
    // Request directly to the host.
    //
    // setAccessible(true) on a private field in a named module requires the module to be `open`
    // or specifically opened via `--add-opens`. com.standardapplied.helios.repl does NOT open the
    // sandbox package,
    // so under a clean JPMS launch the snippet cannot reach the field. BUT: classpath-launched
    // deployments put the bootstrap in an unnamed module which is fully open; and a parent JVM
    // that uses
    // `--add-opens=com.standardapplied.helios.repl/com.standardapplied.helios.repl.sandbox=...` (as
    // Surefire does, and
    // as common testing/instrumentation setups do) leaks that into the subprocess via
    // SandboxLauncher.shouldPropagateJvmArg, opening the package even under modulepath.
    //
    // This test documents that limit: it runs the reflection attack against the production launch
    // path and asserts the forged auditCallback IS invoked. The fix path is documentation
    // (precise C1 javadoc) plus a one-time WARNING at bootstrap startup when the module is
    // unnamed — NOT closing the reflection gap, which is unreachable from inside a single-JVM
    // sandbox without OS-level isolation (Incus is the load-bearing boundary). If a future
    // change ever does close this gap, flip this test to assert the callback is never invoked.
    var forgedCallArrived = new CountDownLatch(1);
    var registry = new HostFunctionRegistry();
    registry.register(
        new HostFunction(
            "auditCallback",
            "test capture for forged RPC invocations",
            params -> {
              forgedCallArrived.countDown();
              return null;
            }));
    try (var sandbox = JvmSandbox.create(END_TO_END, registry)) {
      // The attack: Class.forName the bridge state (its location is on the JShell classpath via
      // addHostBridgeToJShellClasspath, since HostBridge sits in the same jar), grab the static
      // `instance` field reflectively, then pull `realOut` (which IS the RPC socket PrintStream
      // post-C1) and write a fully-formed JSON-RPC Request frame.
      var attack =
          "var c = Class.forName(\"com.standardapplied.helios.repl.sandbox.HostBridgeState\");"
              + "var instField = c.getDeclaredField(\"instance\");"
              + "instField.setAccessible(true);"
              + "var b = instField.get(null);"
              + "var realOutField = c.getDeclaredField(\"realOut\");"
              + "realOutField.setAccessible(true);"
              + "var out = (java.io.PrintStream) realOutField.get(b);"
              + "synchronized (out) {"
              + "  out.print(\"\\u0000RPC:{\\\"jsonrpc\\\":\\\"2.0\\\","
              + "\\\"id\\\":\\\"forged-refl-1\\\","
              + "\\\"method\\\":\\\"auditCallback\\\",\\\"params\\\":{}}\\n\");"
              + "  out.flush();"
              + "}";

      var result = sandbox.execute(ExecutionRequest.java(attack));

      assertEquals(0, result.exitCode(), "the attack snippet failed; stderr:\n" + result.stderr());
      Await.latch(
          "the reflection-forged RPC call to reach the host's HostFunction dispatcher",
          forgedCallArrived);
    }
  }

  @Test
  void collectBindingsCatchHandlesThrowableNotJustException() throws Exception {
    // Theme E defensive-hardening assertion: collectBindings catches Throwable (not Exception),
    // so a binding's toString throwing StackOverflowError or OOM yields a "<error: …>" stub
    // instead of escaping into the virtual thread's uncaught handler and abandoning the snapshot.
    //
    // We assert this at the source level rather than via integration: JShell's varValue catches
    // most Error subtypes internally and returns null (varies across JDKs), so a behavioural test
    // that reliably triggers the Throwable branch is brittle. The catch type is the
    // security-critical invariant; this test prevents an unwitting future revert to
    // `catch (Exception e)`.
    var sourcePath =
        Path.of("src/main/java/com/standardapplied/helios/repl/sandbox/SnippetEvaluator.java");
    var source = Files.readString(sourcePath, StandardCharsets.UTF_8);
    var collectBindingsStart = source.indexOf("Map<String, String> collectBindings(");
    assertTrue(collectBindingsStart > 0, "collectBindings method declaration not found");
    var collectBindingsEnd = source.indexOf("\n  }", collectBindingsStart);
    var body = source.substring(collectBindingsStart, collectBindingsEnd);
    assertTrue(
        body.contains("catch (Throwable"),
        "collectBindings must catch Throwable so StackOverflowError / OOM in a binding's"
            + " toString cannot abort the snapshot; current body:\n"
            + body);
    assertFalse(
        body.contains("catch (Exception e)"),
        "collectBindings should not narrow to Exception — that's the regression we're guarding"
            + " against; current body:\n"
            + body);
  }

  private static JvmSandboxConfig endToEnd(Duration stopGrace) {
    return JvmSandboxConfig.newBuilder()
        .withSubprocessStartupTimeout(Await.HANG_GUARD)
        .withCallTimeout(Await.HANG_GUARD)
        .withExecutionTimeout(BEYOND_HANG_GUARD)
        .withStopGrace(stopGrace)
        .build();
  }
}
