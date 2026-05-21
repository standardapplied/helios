/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import jdk.jshell.SnippetEvent;
import org.junit.jupiter.api.Test;

/**
 * End-to-end enforcement tests. Drives a real {@link JShell} backed by {@link
 * GuardedExecutionControlProvider} with a non-permissive {@link SandboxPolicy}, then evaluates
 * snippets that should and should not be rejected.
 *
 * <p>Denial signal. JShell silently swallows the {@link
 * jdk.jshell.spi.ExecutionControl.ClassInstallException} thrown when {@link
 * GuardedExecutionControl} rejects load — the snippet's {@link SnippetEvent} comes back with status
 * {@code VALID}, no value, and no exception attached. The actual denial reaches the deployer
 * through two channels:
 *
 * <ul>
 *   <li>{@code System.err} carries the policy message (the seam writes it before rewrapping into
 *       {@code ClassInstallException}) — captured by the bootstrap and forwarded to the model
 *   <li>{@code event.value()} stays {@code null}, signalling that execution never happened
 * </ul>
 *
 * <p>Each rule test asserts BOTH signals so a future change that breaks either path fails fast.
 * Allow-tests assert the inverse — that legitimate snippets still execute and produce values.
 */
class SandboxPolicyEnforcementTest {

  @Test
  void denyClassesRejectsProcessBuilderSnippet() {
    assertDenied(
        SandboxPolicy.newBuilder().withDeniedClasses("java.lang.ProcessBuilder").build(),
        "var p = new ProcessBuilder(\"ls\");",
        "ProcessBuilder");
  }

  @Test
  void denyClassesRejectsRuntimeSnippet() {
    assertDenied(
        SandboxPolicy.newBuilder().withDeniedClasses("java.lang.Runtime").build(),
        "var r = Runtime.getRuntime();",
        "Runtime");
  }

  @Test
  void denyPackagesRejectsHttpClientSnippet() {
    assertDenied(
        SandboxPolicy.newBuilder().withDeniedPackages("java.net.http").build(),
        "var c = java.net.http.HttpClient.newHttpClient();",
        "deniedPackages:java.net.http");
  }

  @Test
  void denyReflectionRejectsClassForName() {
    assertDenied(
        SandboxPolicy.newBuilder().withDenyReflection(true).build(),
        "var c = Class.forName(\"java.lang.String\");",
        "denyReflection");
  }

  @Test
  void denyReflectionRejectsMethodHandlesLookup() {
    assertDenied(
        SandboxPolicy.newBuilder().withDenyReflection(true).build(),
        "var l = java.lang.invoke.MethodHandles.lookup();",
        "denyReflection");
  }

  @Test
  void denyNativeAccessRejectsSystemLoadLibrary() {
    assertDenied(
        SandboxPolicy.newBuilder().withDenyNativeAccess(true).build(),
        "System.loadLibrary(\"foo\");",
        "denyNativeAccess");
  }

  @Test
  void denyReflectionAllowsArithmetic() {
    assertAllowed(SandboxPolicy.newBuilder().withDenyReflection(true).build(), "int sum = 2 + 3;");
  }

  @Test
  void denyReflectionAllowsLambdas() {
    assertAllowed(
        SandboxPolicy.newBuilder().withDenyReflection(true).build(),
        "int s = java.util.Arrays.stream(new int[] {1, 2, 3}).sum();");
  }

  @Test
  void denyReflectionAllowsStringConcatenation() {
    assertAllowed(
        SandboxPolicy.newBuilder().withDenyReflection(true).build(),
        "String s = \"a\" + 1 + \"b\";");
  }

  @Test
  void denyingPolicyAllowsBasicCollections() {
    var policy =
        SandboxPolicy.newBuilder()
            .withDeniedClasses("java.lang.ProcessBuilder", "java.lang.Runtime")
            .withDenyReflection(true)
            .withDenyNativeAccess(true)
            .build();
    assertAllowed(
        policy, "java.util.List<Integer> xs = java.util.List.of(1, 2, 3); int n = xs.size();");
  }

  @Test
  void denyClassesRejectsLdcClassConstant() {
    assertDenied(
        SandboxPolicy.newBuilder().withDeniedClasses("java.lang.ProcessBuilder").build(),
        "Class<?> c = ProcessBuilder.class;",
        "ProcessBuilder");
  }

  private static void assertDenied(SandboxPolicy policy, String snippet, String expectedMessage) {
    var capture = new ByteArrayOutputStream();
    var lastEvent = runSnippet(policy, snippet, capture);
    var stderr = capture.toString(StandardCharsets.UTF_8);
    assertTrue(
        stderr.contains(expectedMessage) || stderr.contains("Sandbox policy denied"),
        () ->
            "Expected '"
                + expectedMessage
                + "' or 'Sandbox policy denied' on stderr for snippet ["
                + snippet
                + "], got: ["
                + stderr
                + "]");
    if (lastEvent != null && lastEvent.snippet() != null) {
      assertNull(
          lastEvent.value(),
          () -> "Snippet should not have produced a value when denied: " + snippet);
    }
  }

  private static void assertAllowed(SandboxPolicy policy, String snippet) {
    var capture = new ByteArrayOutputStream();
    var lastEvent = runSnippet(policy, snippet, capture);
    var stderr = capture.toString(StandardCharsets.UTF_8);
    assertTrue(
        !stderr.contains("Sandbox policy denied"),
        () ->
            "Sandbox policy unexpectedly denied an allowed snippet ["
                + snippet
                + "], stderr: ["
                + stderr
                + "]");
    if (lastEvent == null) {
      return;
    }
    assertNotNull(
        lastEvent.snippet(), () -> "Expected snippet event for allowed snippet: " + snippet);
    assertTrue(
        lastEvent.status() != Snippet.Status.REJECTED,
        () -> "Allowed snippet was rejected: " + snippet);
  }

  private static SnippetEvent runSnippet(
      SandboxPolicy policy, String snippet, ByteArrayOutputStream stderrCapture) {
    var originalErr = System.err;
    System.setErr(new PrintStream(stderrCapture, true, StandardCharsets.UTF_8));
    try {
      try (var shell =
          JShell.builder()
              .executionEngine(new GuardedExecutionControlProvider(policy), Map.of())
              .build()) {
        var events = shell.eval(snippet);
        return events.isEmpty() ? null : events.get(events.size() - 1);
      }
    } finally {
      System.setErr(originalErr);
    }
  }
}
