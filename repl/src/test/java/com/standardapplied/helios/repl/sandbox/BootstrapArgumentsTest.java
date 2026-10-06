/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */
package com.standardapplied.helios.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.standardapplied.helios.core.test.Await;
import com.standardapplied.helios.repl.sandbox.policy.SandboxPolicySerialization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reduced-isolation warning for each launch regime, and the bootstrap's exit, with its exact
 * message, on an argument it cannot run without or cannot trust. An exit ends the JVM, so those
 * cases launch the bootstrap on the classpath, where it also prints the unnamed-module warning.
 */
class BootstrapArgumentsTest {

  private static final String UNNAMED_MODULE_WARNING =
      warning("running in the unnamed module (classpath launch)");

  @TempDir Path tempDir;

  @Test
  void classpathLaunchIsWarnedAbout() {
    assertEquals(
        UNNAMED_MODULE_WARNING, warningFor(ClassLoader.getSystemClassLoader().getUnnamedModule()));
  }

  @Test
  void packageOpenedToUnnamedModulesIsWarnedAbout() {
    assertEquals(
        warning(
            "running in module com.standardapplied.helios.repl but package"
                + " com.standardapplied.helios.repl.sandbox is opened to unnamed modules"
                + " (typically via --add-opens inherited from the parent JVM)"),
        warningFor(JvmSandboxBootstrap.class.getModule()));
  }

  @Test
  void namedModuleThatKeepsThePackageClosedIsNotWarnedAbout() {
    assertEquals("", warningFor(Object.class.getModule()));
  }

  @Test
  void rpcSocketIsTakenFromItsArgument() {
    assertEquals(
        Path.of("/run/helios/rpc.sock"),
        BootstrapArguments.parseRpcSocketArg(
            new String[] {"--stop-grace=PT1S", "--rpc-socket=/run/helios/rpc.sock"}));
  }

  @Test
  void missingRpcSocketEndsTheBootstrap() throws IOException {
    assertEquals(
        new Exit(
            2,
            UNNAMED_MODULE_WARNING
                + "JvmSandboxBootstrap: missing required --rpc-socket=<path> argument. The sandbox"
                + " host (JvmSandbox) is responsible for binding the socket and passing the path;"
                + " if you are seeing this manually, you are running the bootstrap outside its"
                + " intended harness.\n"),
        launch());
  }

  @Test
  void malformedSandboxPolicyEndsTheBootstrap() throws IOException {
    var decodeFailure =
        assertThrows(IllegalArgumentException.class, () -> SandboxPolicySerialization.decode("%"));

    assertEquals(
        new Exit(
            2,
            UNNAMED_MODULE_WARNING
                + "JvmSandboxBootstrap: malformed --sandbox-policy argument: "
                + decodeFailure.getMessage()
                + "\n"),
        launch("--rpc-socket=" + tempDir.resolve("rpc.sock"), "--sandbox-policy=%"));
  }

  @Test
  void stopGraceThatIsNotPositiveEndsTheBootstrap() throws IOException {
    assertEquals(
        new Exit(
            2, UNNAMED_MODULE_WARNING + "JvmSandboxBootstrap: --stop-grace=PT0S is not positive\n"),
        launch("--rpc-socket=" + tempDir.resolve("rpc.sock"), "--stop-grace=PT0S"));
  }

  @Test
  void socketNobodyListensOnEndsTheBootstrap() throws IOException {
    var socket = tempDir.resolve("absent.sock");

    assertEquals(
        new Exit(
            2,
            UNNAMED_MODULE_WARNING
                + "JvmSandboxBootstrap: failed to connect to RPC socket "
                + socket
                + ": java.net.SocketException: No such file or directory\n"),
        launch("--rpc-socket=" + socket));
  }

  private record Exit(int status, String stderr) {}

  private static String warning(String reason) {
    return "WARNING: com.standardapplied.helios.repl JvmSandboxBootstrap is "
        + reason
        + ". A JShell snippet can use setAccessible(true) on private bootstrap fields to obtain the"
        + " RPC socket PrintStream and forge calls into the host. C1 closes the stdout-RPC forgery"
        + " path only; closing the reflection forgery path requires both JPMS isolation"
        + " (modulepath launch, no --add-opens to com.standardapplied.helios.repl.sandbox) AND an"
        + " externally-arranged OS-level isolation boundary around the host process for untrusted"
        + " workloads. See the JvmSandboxBootstrap#main javadoc for the full isolation regime.\n";
  }

  private static String warningFor(Module module) {
    var buffer = new ByteArrayOutputStream();
    BootstrapArguments.warnIfReducedIsolation(
        module, new PrintStream(buffer, true, StandardCharsets.UTF_8));
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private Exit launch(String... args) throws IOException {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-cp");
    command.add(SubprocessModulesClasspathLaunchTest.buildFlattenedClasspath());
    command.add(JvmSandboxBootstrap.class.getName());
    command.addAll(List.of(args));
    var stderr = tempDir.resolve("stderr.txt");
    var builder =
        new ProcessBuilder(command).redirectOutput(Redirect.DISCARD).redirectError(stderr.toFile());
    builder.environment().clear();
    var process = builder.start();
    process.getOutputStream().close();
    Await.termination("the bootstrap exiting", process);
    return new Exit(process.exitValue(), Files.readString(stderr, StandardCharsets.UTF_8));
  }
}
