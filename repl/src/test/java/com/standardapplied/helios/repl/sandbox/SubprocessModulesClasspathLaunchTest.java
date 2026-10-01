/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.protocol.ProcessTransport;
import ai.singlr.repl.protocol.RpcChannel;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Empirical proof that L3 ({@code --limit-modules}) works under classpath launch — the case
 * non-JPMS library users would hit. The Maven Surefire test runner that invokes us uses {@code
 * --module-path}, so the normal {@link JvmSandbox#create} path always exercises modulepath launch.
 * To verify the classpath path, this test manually constructs a subprocess launch command using
 * only {@code -cp} (no {@code --module-path}, no {@code --add-modules ai.singlr.repl}) and
 * verifies:
 *
 * <ol>
 *   <li>The subprocess starts (regression coverage for the pre-fix bug where {@code
 *       --limit-modules} named {@code ai.singlr.repl} on classpath and crashed with "Module not
 *       found")
 *   <li>{@code --limit-modules} actually strips JDK modules from the subprocess's boot layer
 *   <li>Snippets compile and run against the restricted JDK module graph
 * </ol>
 *
 * <p>If this test ever breaks, the classpath story is broken — investigate before merging.
 */
class SubprocessModulesClasspathLaunchTest {

  @Test
  void classpathLaunchWithMinimalModulesStripsJavaSql() throws Exception {
    var classpath = buildFlattenedClasspath();
    var modules = SubprocessModules.minimal();
    var limitArg = modules.limitModulesArg(false);
    assertTrue(
        !limitArg.contains(SubprocessModules.BOOTSTRAP_MODULE),
        "limitModulesArg(false) must omit BOOTSTRAP_MODULE — naming it on classpath crashes the JVM");

    var stdout = runClasspathBootstrap(classpath, limitArg, "var x = 21 * 2;");
    assertEquals(
        0,
        stdout.exitCode(),
        () ->
            "Classpath-launched bootstrap should run a basic snippet under --limit-modules; stderr=["
                + stdout.stderr()
                + "]");
  }

  @Test
  void classpathLaunchUnderMinimalReportsJavaSqlAbsentFromBootLayer() throws Exception {
    var classpath = buildFlattenedClasspath();
    var limitArg = SubprocessModules.minimal().limitModulesArg(false);
    var result =
        runClasspathBootstrap(
            classpath,
            limitArg,
            "boolean sqlPresent ="
                + " ModuleLayer.boot().findModule(\"java.sql\").isPresent();\n"
                + "System.out.println(sqlPresent);");
    assertTrue(
        result.stdout().contains("false"),
        () ->
            "java.sql must be absent from boot layer under classpath launch with --limit-modules; "
                + "stdout=["
                + result.stdout()
                + "] stderr=["
                + result.stderr()
                + "] exit="
                + result.exitCode());
  }

  @Test
  void classpathLaunchUnderMinimalAlsoStripsJavaNetHttp() throws Exception {
    // Under classpath launch, ai.singlr.core's `requires java.net.http` is ignored (the JAR is
    // an unnamed-module member when on classpath), so http IS strippable here — unlike modulepath
    // launch where the bootstrap's transitive requires keep http in the resolved graph.
    var classpath = buildFlattenedClasspath();
    var limitArg = SubprocessModules.minimal().limitModulesArg(false);
    var result =
        runClasspathBootstrap(
            classpath,
            limitArg,
            "boolean httpPresent ="
                + " ModuleLayer.boot().findModule(\"java.net.http\").isPresent();\n"
                + "System.out.println(httpPresent);");
    assertTrue(
        result.stdout().contains("false"),
        () ->
            "java.net.http should be strippable under classpath launch + minimal; "
                + "stdout=["
                + result.stdout()
                + "] stderr=["
                + result.stderr()
                + "]");
  }

  /**
   * Build a single classpath string containing the parent JVM's full module path (flattened) plus
   * its existing classpath. The subprocess can then load all of helios-repl + transitive deps via
   * classpath alone, with no {@code --module-path} or {@code --add-modules} flag.
   */
  private static String buildFlattenedClasspath() {
    var paths = new ArrayList<String>();
    var parentArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
    for (var arg : parentArgs) {
      if (arg.startsWith("--module-path=")) {
        paths.add(arg.substring("--module-path=".length()));
      } else if (arg.equals("--module-path") || arg.equals("-p")) {
        // value is the next arg; with ManagementFactory we get individual tokens, but the bundled
        // form "--module-path=..." is what Surefire actually passes today. If we ever see the
        // split form, just skip — the bundled form covers our cases.
      }
    }
    var existingCp = System.getProperty("java.class.path");
    if (existingCp != null && !existingCp.isBlank()) {
      paths.add(existingCp);
    }
    return String.join(System.getProperty("path.separator"), paths);
  }

  /** Result of running a single snippet through a classpath-launched bootstrap. */
  private record SnippetResult(int exitCode, String stdout, String stderr) {}

  /**
   * Launch the bootstrap via classpath-only command (no {@code --module-path}), wait for it to
   * connect to a Unix domain socket, send a single {@code execute} RPC, return the result. Mirrors
   * {@link JvmSandbox#create} but with a manually-constructed launch command so the classpath
   * branch is actually exercised — Surefire-inherited modulepath args would otherwise dominate.
   */
  private static SnippetResult runClasspathBootstrap(
      String classpath, String limitModulesArg, String snippet) throws Exception {
    var socketDir = Files.createTempDirectory("helios-cp-test-");
    try {
      try {
        Files.setPosixFilePermissions(socketDir, PosixFilePermissions.fromString("rwx------"));
      } catch (UnsupportedOperationException ignored) {
        // non-POSIX filesystem
      }
      var socketPath = socketDir.resolve("rpc.sock");
      try (var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
        listener.bind(UnixDomainSocketAddress.of(socketPath), 1);

        var cmd = new ArrayList<String>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("-Xmx256m");
        cmd.add("-cp");
        cmd.add(classpath);
        if (!limitModulesArg.isEmpty()) {
          cmd.add("--limit-modules");
          cmd.add(limitModulesArg);
        }
        cmd.add("ai.singlr.repl.sandbox.JvmSandboxBootstrap");
        cmd.add("--rpc-socket=" + socketPath);

        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        var env = pb.environment();
        env.clear();
        env.put("PATH", System.getenv().getOrDefault("PATH", ""));
        env.put("JAVA_HOME", System.getProperty("java.home"));
        var process = pb.start();
        process.getOutputStream().close();

        try {
          var acceptedSocket = JvmSandbox.acceptWithTimeout(listener, Duration.ofSeconds(15));
          try {
            Files.deleteIfExists(socketPath);
            var transport =
                new ProcessTransport(
                    Channels.newInputStream(acceptedSocket),
                    Channels.newOutputStream(acceptedSocket));
            var channel =
                new RpcChannel(transport, new HostFunctionRegistry(), Duration.ofSeconds(30));
            var params = new java.util.LinkedHashMap<String, Object>();
            params.put("code", snippet);
            params.put("language", "java");
            params.put("timeoutMs", 15_000L);
            params.put("captureBindings", false);
            params.put("maxBindingValueChars", 0);
            params.put("maxBindingSnapshotChars", 0);
            var result = channel.call("execute", params);
            channel.close();

            if (result instanceof Map<?, ?> m) {
              var stdout = m.get("stdout") instanceof String s ? s : "";
              var stderr = m.get("stderr") instanceof String s ? s : "";
              var exitCode = m.get("exitCode") instanceof Number n ? n.intValue() : -1;
              return new SnippetResult(exitCode, stdout, stderr);
            }
            return new SnippetResult(-1, String.valueOf(result), "");
          } finally {
            acceptedSocket.close();
          }
        } finally {
          process.destroyForcibly();
          process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        }
      }
    } finally {
      try (var entries = Files.list(socketDir)) {
        entries.collect(Collectors.toList()).forEach(p -> deleteQuietly(p));
      } catch (IOException ignored) {
        // best-effort cleanup
      }
      Files.deleteIfExists(socketDir);
    }
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException ignored) {
      // best-effort
    }
  }
}
