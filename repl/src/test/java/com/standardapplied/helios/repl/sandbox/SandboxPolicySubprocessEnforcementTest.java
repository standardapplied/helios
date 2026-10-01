/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.sandbox.policy.SandboxPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end enforcement test through the real {@link JvmSandbox} subprocess. Closes the gap left
 * by the in-process {@code SandboxPolicyEnforcementTest}: this one proves the policy travels
 * host→subprocess via argv, the bootstrap reconstructs it, the verifier wires up against the real
 * JShell instance in the subprocess, and the stderr signal makes the round trip back into {@link
 * ExecutionResult#stderr()}.
 *
 * <p>Each test launches a fresh JVM subprocess (~1-2 s each), so this class deliberately holds
 * three tests — one denial, one allow, one sentinel read/write through method references — covering
 * the wire end-to-end without ballooning CI cost.
 */
class SandboxPolicySubprocessEnforcementTest {

  @Test
  void denyingPolicyRejectsDeniedSnippetInRealSubprocess() {
    var policy = SandboxPolicy.noEgress();
    var config =
        JvmSandboxConfig.newBuilder()
            .withSandboxPolicy(policy)
            .withExecutionTimeout(Duration.ofSeconds(20))
            .build();
    var registry = new HostFunctionRegistry();
    try (var sandbox = JvmSandbox.create(config, registry)) {
      var result =
          sandbox.execute(ExecutionRequest.java("var c = Class.forName(\"java.lang.String\");"));
      assertTrue(
          result.stderr().contains("Sandbox policy denied")
              || result.stderr().contains("denyReflection"),
          () ->
              "Expected policy message in subprocess stderr, got: stderr=["
                  + result.stderr()
                  + "] stdout=["
                  + result.stdout()
                  + "]");
      var staticReference =
          sandbox.execute(
              ExecutionRequest.java(
                  "interface Loader { Class<?> load(String name) throws ClassNotFoundException; }\n"
                      + "Loader loader = Class::forName;"));
      assertTrue(
          staticReference.stderr().contains("Sandbox policy denied java.lang.Class#forName"),
          staticReference::stderr);
      var instanceReference =
          sandbox.execute(
              ExecutionRequest.java(
                  "java.util.function.Function<java.io.File, java.io.File[]> lister ="
                      + " java.io.File::listFiles;"));
      assertTrue(
          instanceReference.stderr().contains("Sandbox policy denied java.io.File#listFiles"),
          instanceReference::stderr);
      var boundReference =
          sandbox.execute(
              ExecutionRequest.java(
                  "java.util.function.Supplier<Boolean> exists = new java.io.File(\".\")::exists;"));
      assertTrue(
          boundReference.stderr().contains("Sandbox policy denied java.io.File#exists"),
          boundReference::stderr);
      var nestedReference =
          sandbox.execute(
              ExecutionRequest.java(
                  "java.util.function.Supplier<java.util.function.Predicate<java.io.File>>"
                      + " nested = () -> java.io.File::exists;"));
      assertTrue(
          nestedReference.stderr().contains("Sandbox policy denied java.io.File#exists"),
          nestedReference::stderr);
    }
  }

  @Test
  void noEgressAllowsLanguageBootstrapsInRealSubprocess() {
    var policy = SandboxPolicy.noEgress();
    var config =
        JvmSandboxConfig.newBuilder()
            .withSandboxPolicy(policy)
            .withExecutionTimeout(Duration.ofSeconds(20))
            .build();
    var registry = new HostFunctionRegistry();
    try (var sandbox = JvmSandbox.create(config, registry)) {
      var result =
          sandbox.execute(
              ExecutionRequest.java(
                  """
                  record Point(int x, String label) {}
                  int[] values = {1, -2};
                  java.util.function.Supplier<int[]> copy = values::clone;
                  java.util.function.IntUnaryOperator abs = Math::abs;
                  int factor = 3;
                  java.util.function.IntUnaryOperator scale = value -> value * factor;
                  Point point = new Point(scale.applyAsInt(abs.applyAsInt(copy.get()[1])), "ok");
                  int classify(Object value) {
                    return switch (value) {
                      case java.time.DayOfWeek.MONDAY -> 1;
                      case String s -> s.length();
                      default -> 0;
                    };
                  }
                  System.out.println("point=" + point);
                  System.out.println(point.equals(new Point(6, "ok")));
                  System.out.println(point.hashCode() == new Point(6, "ok").hashCode());
                  System.out.println("day=" + classify(java.time.DayOfWeek.MONDAY));
                  """));
      assertEquals(
          0,
          result.exitCode(),
          () ->
              "Allowed snippet should have exit code 0; stderr=["
                  + result.stderr()
                  + "] stdout=["
                  + result.stdout()
                  + "]");
      assertTrue(
          !result.stderr().contains("Sandbox policy denied"),
          () -> "Allowed snippet unexpectedly produced a policy denial: " + result.stderr());
      assertEquals("point=Point[x=6, label=ok]\ntrue\ntrue\nday=1\n", result.stdout());
    }
  }

  @Test
  void noEgressBlocksMethodReferenceReadAndWriteOfSentinelInRealSubprocess(@TempDir Path dir)
      throws IOException {
    var sentinel = dir.resolve("sentinel.txt");
    var marker = "SENTINEL-" + UUID.randomUUID();
    Files.writeString(sentinel, marker);
    var config =
        JvmSandboxConfig.newBuilder()
            .withSandboxPolicy(SandboxPolicy.noEgress())
            .withExecutionTimeout(Duration.ofSeconds(20))
            .build();
    try (var sandbox = JvmSandbox.create(config, new HostFunctionRegistry())) {
      var read =
          sandbox.execute(
              ExecutionRequest.java(
                  "interface Opener { java.io.Reader open(String p) throws java.io.IOException; }\n"
                      + "Opener opener = java.io.FileReader::new;\n"
                      + "var reader = opener.open(\""
                      + sentinel
                      + "\");\n"
                      + "char[] buf = new char[256];\n"
                      + "System.out.println(new String(buf, 0, reader.read(buf)));"));
      assertTrue(
          read.stderr().contains("Sandbox policy denied java.io.FileReader#<init>"),
          () -> "Expected FileReader::new denial, got stderr=[" + read.stderr() + "]");
      assertTrue(
          !read.stdout().contains(marker),
          () -> "Sentinel content leaked through a method reference: " + read.stdout());

      var write =
          sandbox.execute(
              ExecutionRequest.java(
                  "interface Sink { java.io.Writer open(String p) throws java.io.IOException; }\n"
                      + "Sink sink = java.io.FileWriter::new;\n"
                      + "try (var out = sink.open(\""
                      + sentinel
                      + "\")) { out.write(\"overwritten\"); }"));
      assertTrue(
          write.stderr().contains("Sandbox policy denied java.io.FileWriter#<init>"),
          () -> "Expected FileWriter::new denial, got stderr=[" + write.stderr() + "]");
      assertEquals(marker, Files.readString(sentinel));
    }
  }
}
