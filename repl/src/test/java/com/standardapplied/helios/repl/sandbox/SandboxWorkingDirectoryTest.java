/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.repl.host.HostFunctionRegistry;
import ai.singlr.repl.sandbox.policy.SandboxPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests covering the working-directory containment story introduced alongside {@link
 * SandboxPolicy#denyFileSystemAccess()}. Together the two features answer "does the JShell
 * subprocess see the host JVM's working directory?" with "no — by default it sees a private
 * per-session scratch dir that the JvmSandbox deletes on close, and you can pin it to a
 * caller-owned path when you want a longer-lived workspace".
 *
 * <p>Each test launches a fresh subprocess (~1-2 s each), so this class is intentionally narrow:
 * one default-path test, one explicit-path test, one cleanup test, and the bytecode-level deny
 * lives in the in-process unit suite ({@code PolicyBytecodeVerifierTest}).
 */
class SandboxWorkingDirectoryTest {

  @Test
  void defaultConfigCreatesEphemeralWorkingDirAndSubprocessSeesIt() throws Exception {
    // No withWorkingDirectory() — the sandbox creates a helios-sandbox-cwd-* scratch dir,
    // sets it on ProcessBuilder.directory(), and the subprocess sees it as its System.getProperty
    // ("user.dir") and as the resolution root for relative paths.
    var config = JvmSandboxConfig.newBuilder().withExecutionTimeout(Duration.ofSeconds(20)).build();
    var registry = new HostFunctionRegistry();
    try (var sandbox = JvmSandbox.create(config, registry)) {
      var ephemeralCwd = sandbox.ephemeralWorkingDirForTests();
      assertNotNull(
          ephemeralCwd, "default config must create a per-session ephemeral working directory");
      assertTrue(Files.isDirectory(ephemeralCwd), "ephemeral cwd must exist on the filesystem");
      assertTrue(
          ephemeralCwd.getFileName().toString().startsWith("helios-sandbox-cwd-"),
          "ephemeral cwd must use the helios-sandbox-cwd- prefix: " + ephemeralCwd);

      // Probe from inside the subprocess — its user.dir matches the ephemeral cwd, not the host
      // JVM's cwd. This is the central invariant: JShell snippets don't inherit the parent's
      // working directory.
      var result =
          sandbox.execute(
              ExecutionRequest.java("System.out.println(System.getProperty(\"user.dir\"));"));
      assertEquals(0, result.exitCode(), () -> "snippet should run: stderr=" + result.stderr());
      // Compare via Path::toRealPath on both sides — macOS symlinks (/var -> /private/var)
      // otherwise produce a string mismatch where the canonical paths are identical.
      var hostSeen = ephemeralCwd.toRealPath();
      var snippetSeen = Path.of(result.stdout().trim()).toRealPath();
      assertEquals(hostSeen, snippetSeen, "subprocess cwd must match the ephemeral path");
      assertNotEquals(
          Path.of(System.getProperty("user.dir")).toRealPath(),
          snippetSeen,
          "subprocess cwd must NOT inherit the host JVM's cwd");
    }
  }

  @Test
  void defaultEphemeralCwdIsDeletedOnClose() throws Exception {
    var config = JvmSandboxConfig.newBuilder().withExecutionTimeout(Duration.ofSeconds(20)).build();
    var registry = new HostFunctionRegistry();
    Path ephemeralCwd;
    try (var sandbox = JvmSandbox.create(config, registry)) {
      ephemeralCwd = sandbox.ephemeralWorkingDirForTests();
      assertTrue(Files.isDirectory(ephemeralCwd), "ephemeral cwd must exist while sandbox is open");
    }
    assertFalse(
        Files.exists(ephemeralCwd, java.nio.file.LinkOption.NOFOLLOW_LINKS),
        "ephemeral cwd must be deleted on sandbox close");
  }

  @Test
  void defaultEphemeralCwdDeletionIsRecursiveAcrossSnippetWrites(@TempDir Path tempDir)
      throws Exception {
    // Snippets write files into the ephemeral cwd; close() must walk and delete them all so the
    // tmpdir doesn't fill up across many sessions. Permissive policy so the snippet can write.
    var config = JvmSandboxConfig.newBuilder().withExecutionTimeout(Duration.ofSeconds(20)).build();
    var registry = new HostFunctionRegistry();
    Path ephemeralCwd;
    try (var sandbox = JvmSandbox.create(config, registry)) {
      ephemeralCwd = sandbox.ephemeralWorkingDirForTests();
      // Snippet creates a nested file structure: subdir/leaf.txt
      var snippet =
          "var p = java.nio.file.Path.of(\"subdir\", \"leaf.txt\");"
              + "java.nio.file.Files.createDirectories(p.getParent());"
              + "java.nio.file.Files.writeString(p, \"hello\");"
              + "System.out.println(p.toAbsolutePath());";
      var result = sandbox.execute(ExecutionRequest.java(snippet));
      assertEquals(0, result.exitCode(), () -> "snippet write failed: " + result.stderr());
      var subdir = ephemeralCwd.resolve("subdir");
      assertTrue(Files.isDirectory(subdir), "snippet's subdir must be present pre-close");
      assertTrue(Files.isRegularFile(subdir.resolve("leaf.txt")), "snippet's file must be present");
    }
    assertFalse(
        Files.exists(ephemeralCwd, java.nio.file.LinkOption.NOFOLLOW_LINKS),
        "ephemeral cwd (with snippet-written nested files) must be deleted recursively on close");
    // tempDir is JUnit-managed and unused here — fixture stays so the test signature documents
    // that the cleanup operates on tmpdir territory (no leak into other dirs).
    assertTrue(Files.isDirectory(tempDir));
  }

  @Test
  void callerOwnedWorkingDirectoryIsUsedButNotDeletedOnClose(@TempDir Path callerOwnedDir)
      throws IOException {
    // When the caller pins a path via withWorkingDirectory, the sandbox uses it verbatim and
    // leaves it alone on close — caller-owned lifecycle. Verifies (a) the subprocess sees the
    // caller's path as its cwd and (b) the directory survives sandbox close.
    Files.createDirectories(callerOwnedDir);
    var config =
        JvmSandboxConfig.newBuilder()
            .withWorkingDirectory(callerOwnedDir)
            .withExecutionTimeout(Duration.ofSeconds(20))
            .build();
    var registry = new HostFunctionRegistry();
    try (var sandbox = JvmSandbox.create(config, registry)) {
      assertNull(
          sandbox.ephemeralWorkingDirForTests(),
          "explicit working directory must NOT create an ephemeral one");
      var result =
          sandbox.execute(
              ExecutionRequest.java("System.out.println(System.getProperty(\"user.dir\"));"));
      assertEquals(0, result.exitCode(), () -> "snippet should run: stderr=" + result.stderr());
      var snippetSeen = Path.of(result.stdout().trim()).toRealPath();
      assertEquals(callerOwnedDir.toRealPath(), snippetSeen, "subprocess must run in caller dir");
    }
    assertTrue(
        Files.isDirectory(callerOwnedDir),
        "caller-owned directory must survive sandbox close — caller manages lifecycle");
  }

  @Test
  void filesystemDenyPolicyBlocksAbsolutePathReadOfEtcPasswd() {
    // The canonical "can JShell escape to /etc/passwd?" question. With denyFileSystemAccess set,
    // even though the cwd is ephemeral and absolute paths bypass cwd resolution entirely, the
    // bytecode verifier denies the IO open at load time. The snippet's stderr carries the
    // policy traceback so the model self-corrects.
    var policy = SandboxPolicy.newBuilder().withDenyFileSystemAccess(true).build();
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
                  "var bs = java.nio.file.Files.readAllBytes("
                      + "java.nio.file.Path.of(\"/etc/passwd\"));"));
      assertTrue(
          result.stderr().contains("denyFileSystemAccess")
              || result.stderr().contains("Sandbox policy denied"),
          () ->
              "Expected denyFileSystemAccess policy message; stderr=["
                  + result.stderr()
                  + "] stdout=["
                  + result.stdout()
                  + "]");
    }
  }

  @Test
  void filesystemDenyPolicyBlocksFileExistsMetadataProbe() {
    // The historical leak path: new File("/etc/passwd").exists() would return true under prior
    // noEgress() because File itself wasn't denied. denyFileSystemAccess closes it.
    var policy = SandboxPolicy.newBuilder().withDenyFileSystemAccess(true).build();
    var config =
        JvmSandboxConfig.newBuilder()
            .withSandboxPolicy(policy)
            .withExecutionTimeout(Duration.ofSeconds(20))
            .build();
    var registry = new HostFunctionRegistry();
    try (var sandbox = JvmSandbox.create(config, registry)) {
      var result =
          sandbox.execute(
              ExecutionRequest.java("boolean b = new java.io.File(\"/etc/passwd\").exists();"));
      assertTrue(
          result.stderr().contains("denyFileSystemAccess")
              || result.stderr().contains("Sandbox policy denied"),
          () ->
              "Expected denyFileSystemAccess on File.exists metadata leak; stderr=["
                  + result.stderr()
                  + "]");
    }
  }
}
