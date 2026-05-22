/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import jdk.jshell.JShell;
import jdk.jshell.Snippet;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@link SandboxPolicy#noEgress()} curated preset end-to-end against real JShell:
 * snippets that touch egress paths (process, network, reflection, file IO, native, dynamic class
 * def) are rejected; compute snippets (math, collections, streams, time, println) are accepted.
 */
class NoEgressPresetTest {

  @Test
  void noEgressIsNotPermissive() {
    var p = SandboxPolicy.noEgress();
    assertFalse(p.isPermissive());
  }

  @Test
  void noEgressIncludesExpectedAllowedPackages() {
    var p = SandboxPolicy.noEgress();
    assertTrue(p.allowedPackages().contains("java.lang"));
    assertTrue(p.allowedPackages().contains("java.util"));
    assertTrue(p.allowedPackages().contains("java.math"));
    assertTrue(p.allowedPackages().contains("java.time"));
    assertTrue(p.allowedPackages().contains("java.io"));
  }

  @Test
  void noEgressDeniesProcessBuilderAndRuntime() {
    var p = SandboxPolicy.noEgress();
    assertTrue(p.deniedClasses().contains("java.lang.ProcessBuilder"));
    assertTrue(p.deniedClasses().contains("java.lang.Runtime"));
  }

  @Test
  void noEgressDeniesFileIoViaCategoricalFlag() {
    // 2.4.0 honesty pass: noEgress() used to enumerate FileInputStream / FileOutputStream /
    // FileReader / FileWriter / RandomAccessFile in deniedClasses, but the prior leak path
    // (java.io.File metadata methods like list / exists / canRead) survived because the File
    // class itself wasn't enumerated. Rolling them into denyFileSystemAccess closes that gap and
    // simplifies the deniedClasses set — file IO now travels through one flag.
    var p = SandboxPolicy.noEgress();
    assertTrue(
        p.denyFileSystemAccess(),
        "noEgress() must enable denyFileSystemAccess to honestly close the filesystem surface");
    assertFalse(
        p.deniedClasses().contains("java.io.FileInputStream"),
        "FileInputStream is now covered by denyFileSystemAccess; the explicit deny is removed");
    assertFalse(
        p.deniedClasses().contains("java.io.FileReader"),
        "FileReader is now covered by denyFileSystemAccess; the explicit deny is removed");
    assertFalse(
        p.deniedClasses().contains("java.io.RandomAccessFile"),
        "RandomAccessFile is now covered by denyFileSystemAccess; the explicit deny is removed");
  }

  @Test
  void noEgressEnablesAllCategoricalDenies() {
    var p = SandboxPolicy.noEgress();
    assertTrue(p.denyReflection());
    assertTrue(p.denyNativeAccess());
    assertTrue(p.denyDynamicClassDefinition());
    assertTrue(p.denyFileSystemAccess());
  }

  @Test
  void noEgressRejectsProcessBuilderSnippet() {
    assertSnippetDenied("var p = new ProcessBuilder(\"ls\");", "ProcessBuilder");
  }

  @Test
  void noEgressRejectsRuntimeSnippet() {
    assertSnippetDenied("var r = Runtime.getRuntime();", "Runtime");
  }

  @Test
  void noEgressRejectsClassForName() {
    assertSnippetDenied("var c = Class.forName(\"java.lang.String\");", "denyReflection");
  }

  @Test
  void noEgressRejectsHttpClient() {
    assertSnippetDenied(
        "var c = java.net.http.HttpClient.newHttpClient();", "allowedPackages-default-deny");
  }

  @Test
  void noEgressRejectsFileReader() {
    assertSnippetDenied("var r = new java.io.FileReader(\"/etc/passwd\");", "denyFileSystemAccess");
  }

  @Test
  void noEgressRejectsFileInputStream() {
    assertSnippetDenied(
        "var s = new java.io.FileInputStream(\"/etc/passwd\");", "denyFileSystemAccess");
  }

  @Test
  void noEgressRejectsFilesNio() {
    // Hits the deny on java.nio.file.* first — denyFileSystemAccess fires before the allow-list
    // default-deny because the categorical checks run earlier in checkOwnerMember.
    assertSnippetDenied("var p = java.nio.file.Paths.get(\"/tmp\");", "denyFileSystemAccess");
  }

  @Test
  void noEgressClosesTheFileMetadataLeakPathThatSurvivedPriorReleases() {
    // The honesty fix: prior noEgress() let snippets do new File("/etc/passwd").exists() and
    // new File("/").listFiles() because java.io.File itself was never in deniedClasses and the
    // package-allow on java.io kept it callable. denyFileSystemAccess now closes both routes.
    assertSnippetDenied(
        "boolean b = new java.io.File(\"/etc/passwd\").exists();", "denyFileSystemAccess");
    assertSnippetDenied("var f = new java.io.File(\"/\").listFiles();", "denyFileSystemAccess");
    assertSnippetDenied(
        "long n = new java.io.File(\"/etc/passwd\").length();", "denyFileSystemAccess");
  }

  @Test
  void noEgressRejectsFilesReadAllBytes() {
    assertSnippetDenied(
        "var bs = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(\"/etc/passwd\"));",
        "denyFileSystemAccess");
  }

  @Test
  void noEgressRejectsFileChannelOpen() {
    assertSnippetDenied(
        "var ch = java.nio.channels.FileChannel.open(java.nio.file.Path.of(\"/etc/passwd\"));",
        "denyFileSystemAccess");
  }

  @Test
  void noEgressAllowsBasicArithmetic() {
    assertSnippetAllowed("int sum = 21 * 2;");
  }

  @Test
  void noEgressAllowsCollectionsAndStreams() {
    assertSnippetAllowed("int s = java.util.Arrays.stream(new int[] {1, 2, 3}).sum();");
  }

  @Test
  void noEgressAllowsTime() {
    assertSnippetAllowed(
        "java.time.LocalDate d = java.time.LocalDate.now();" + " String iso = d.toString();");
  }

  @Test
  void noEgressAllowsMath() {
    assertSnippetAllowed("double r = Math.sqrt(2.0);");
  }

  private static void assertSnippetDenied(String snippet, String expectedInMessage) {
    var capture = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(capture, true, StandardCharsets.UTF_8));
    try (var shell =
        JShell.builder()
            .executionEngine(
                new GuardedExecutionControlProvider(SandboxPolicy.noEgress()), Map.of())
            .build()) {
      shell.eval(snippet);
    } finally {
      System.setErr(originalErr);
    }
    var stderr = capture.toString(StandardCharsets.UTF_8);
    assertTrue(
        stderr.contains("Sandbox policy denied") || stderr.contains(expectedInMessage),
        () ->
            "Expected '"
                + expectedInMessage
                + "' or 'Sandbox policy denied' on stderr for snippet ["
                + snippet
                + "], got: ["
                + stderr
                + "]");
  }

  private static void assertSnippetAllowed(String snippet) {
    var capture = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(capture, true, StandardCharsets.UTF_8));
    var events =
        new java.util.concurrent.atomic.AtomicReference<java.util.List<jdk.jshell.SnippetEvent>>();
    try (var shell =
        JShell.builder()
            .executionEngine(
                new GuardedExecutionControlProvider(SandboxPolicy.noEgress()), Map.of())
            .build()) {
      events.set(shell.eval(snippet));
    } finally {
      System.setErr(originalErr);
    }
    var stderr = capture.toString(StandardCharsets.UTF_8);
    assertFalse(
        stderr.contains("Sandbox policy denied"),
        () ->
            "Sandbox policy unexpectedly denied an allowed snippet ["
                + snippet
                + "], stderr: ["
                + stderr
                + "]");
    assertNotNull(events.get());
    for (var event : events.get()) {
      if (event.status() == Snippet.Status.REJECTED) {
        throw new AssertionError(
            "Snippet was REJECTED under noEgress when it should have been allowed: "
                + snippet
                + " — stderr: ["
                + stderr
                + "]");
      }
    }
  }
}
