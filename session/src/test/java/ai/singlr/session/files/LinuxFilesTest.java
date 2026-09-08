/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.singlr.session.memory.FileSystemMemoryBackend;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledOnOs(OS.LINUX)
final class LinuxFilesTest {
  @Test
  void pinnedFileSurvivesReplacementWithoutFollowingTheNewLeaf(@TempDir Path tmp) throws Exception {
    var root = Files.createDirectory(tmp.resolve("workspace"));
    var file = Files.writeString(root.resolve("file"), "inside");
    var outside = Files.writeString(tmp.resolve("outside"), "sentinel");
    var workspace = WorkspaceRoot.of(root);
    try (var handle = LinuxFiles.pin(file);
        var input = workspace.newInputStream(file);
        var output = workspace.newOutputStream(file, StandardOpenOption.WRITE)) {
      Files.move(file, root.resolve("held"));
      Files.createSymbolicLink(file, outside);
      assertTrue(handle.regularFile().isRegularFile());
      assertEquals(
          "inside", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      output.write('x');
      assertEquals("sentinel", Files.readString(outside));
    }
    assertEquals("xnside", Files.readString(root.resolve("held")));
  }

  @Test
  void pinnedDirectoryDoesNotEnumerateItsReplacement(@TempDir Path tmp) throws Exception {
    var root = Files.createDirectory(tmp.resolve("workspace"));
    var directory = Files.createDirectory(root.resolve("dir"));
    var outside = Files.createDirectory(tmp.resolve("outside"));
    Files.writeString(directory.resolve("inside"), "x");
    Files.writeString(outside.resolve("secret"), "x");
    var workspace = WorkspaceRoot.of(root);
    try (var entries = workspace.newDirectoryStream(directory)) {
      Files.move(directory, root.resolve("held"));
      Files.createSymbolicLink(directory, outside);
      var names = new ArrayList<String>();
      entries.forEach(path -> names.add(path.getFileName().toString()));
      assertEquals(java.util.List.of("inside"), names);
    }
    assertThrows(IOException.class, () -> workspace.newDirectoryStream(directory));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ancestorSubstitutionCannotReadWriteCreateDeleteOrListOutside(
      boolean swapRoot, @TempDir Path tmp) throws Exception {
    var root = Files.createDirectory(tmp.resolve("workspace"));
    var outside = Files.createDirectory(tmp.resolve("outside"));
    var parent = Files.createDirectory(root.resolve("parent"));
    var held = root.resolve("held");
    Files.writeString(parent.resolve("file"), "inside");
    Files.writeString(parent.resolve("delete"), "inside");
    Files.writeString(outside.resolve("file"), "sentinel");
    Files.writeString(outside.resolve("delete"), "sentinel");
    Files.writeString(outside.resolve("outside-only"), "sentinel");
    var workspace = WorkspaceRoot.of(swapRoot ? parent : root);
    var stop = new AtomicBoolean();
    var failure = new AtomicReference<Throwable>();
    var ready = new CountDownLatch(1);
    var attacker =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    ready.countDown();
                    while (!stop.get()) {
                      Files.move(parent, held, StandardCopyOption.ATOMIC_MOVE);
                      try {
                        Files.createSymbolicLink(parent, outside);
                        Thread.yield();
                      } finally {
                        Files.deleteIfExists(parent);
                        Files.move(held, parent, StandardCopyOption.ATOMIC_MOVE);
                      }
                    }
                  } catch (Throwable error) {
                    failure.set(error);
                  }
                });
    int rounds = 0;
    try {
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
      for (; rounds < 2_000 && System.nanoTime() < deadline; rounds++) {
        try (var input = workspace.newInputStream(parent.resolve("file"))) {
          assertEquals(
              "inside", new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException raced) {
        }
        try (var output =
            workspace.newOutputStream(parent.resolve("file"), StandardOpenOption.WRITE)) {
          output.write("inside".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException raced) {
        }
        if (swapRoot) {
          try {
            workspace.createDirectories(parent.resolve("created/nested"));
          } catch (IOException raced) {
          }
        }
        try {
          workspace.deleteFile(parent.resolve("delete"));
        } catch (IOException raced) {
        }
        workspace.walkFileTree(
            parent,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                assertFalse(file.getFileName().toString().equals("outside-only"));
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFileFailed(Path file, IOException error) {
                return FileVisitResult.CONTINUE;
              }
            });
      }
    } finally {
      stop.set(true);
      attacker.join(5_000);
      assertFalse(attacker.isAlive(), "attacker must terminate");
    }
    assertEquals(null, failure.get());
    assertTrue(rounds > 0);
    assertEquals("sentinel", Files.readString(outside.resolve("file")));
    assertEquals("sentinel", Files.readString(outside.resolve("delete")));
    assertFalse(Files.exists(outside.resolve("created")));
  }

  @Test
  void directoryCreationAndDeletionRefuseAncestorLinks(@TempDir Path tmp) throws Exception {
    var root = Files.createDirectory(tmp.resolve("workspace"));
    var outside = Files.createDirectory(tmp.resolve("outside"));
    Files.writeString(outside.resolve("file"), "sentinel");
    var workspace = WorkspaceRoot.of(root);
    Files.createSymbolicLink(root.resolve("escape"), outside);
    assertThrows(
        IOException.class, () -> workspace.createDirectories(root.resolve("escape/new/deep")));
    assertThrows(IOException.class, () -> workspace.deleteFile(root.resolve("escape/file")));
    assertFalse(Files.exists(outside.resolve("new")));
    assertEquals("sentinel", Files.readString(outside.resolve("file")));
    workspace.deleteFile(root.resolve("escape"));
    assertTrue(Files.isDirectory(outside));
    assertFalse(Files.exists(root.resolve("escape"), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void removedRootIsNeverRecreatedByDirectoryCreation(@TempDir Path tmp) throws Exception {
    var root = Files.createDirectory(tmp.resolve("workspace"));
    var workspace = WorkspaceRoot.of(root);
    Files.delete(root);
    assertThrows(NoSuchFileException.class, () -> workspace.createDirectories(root.resolve("a/b")));
    assertFalse(Files.exists(root));
  }

  @Test
  void metadataOnlyPinsRejectSpecialFilesWithoutBlocking(@TempDir Path tmp) throws Exception {
    var fifo = tmp.resolve("fifo");
    assumeTrue(SpecialFiles.mkfifo(fifo));
    runProbe(tmp, "fifo", true);
  }

  @Test
  void missingNativeAccessFailsClosedButTrustedWorkspaceStillConstructs(@TempDir Path tmp)
      throws Exception {
    runProbe(tmp, "denied", false);
  }

  @Test
  void unsupportedFilesystemFailsClosed(@TempDir Path tmp) throws Exception {
    try (var zip =
        FileSystems.newFileSystem(
            URI.create("jar:" + tmp.resolve("archive.zip").toUri()), Map.of("create", "true"))) {
      assertThrows(UnsupportedOperationException.class, () -> WorkspaceRoot.of(zip.getPath("/")));
    }
  }

  @Test
  void sizeLimitUsesPinnedTargetAndCannotBeBypassedAfterAnException(@TempDir Path tmp)
      throws Exception {
    var file = Files.writeString(tmp.resolve("file"), "abc");
    var workspace = WorkspaceRoot.of(tmp);
    assertThrows(IOException.class, () -> workspace.newInputStream(file, 2));
    assertThrows(IllegalArgumentException.class, () -> workspace.newInputStream(file, -1));
    try (var input = workspace.newInputStream(file, 3)) {
      Files.writeString(file, "abcdef");
      assertEquals(0, input.read(new byte[0]));
      assertEquals(1, input.skip(1));
      assertEquals('b', input.read());
      assertEquals('c', input.read());
      assertThrows(IOException.class, input::read);
      assertThrows(IOException.class, input::read);
    }
    Files.writeString(file, "");
    try (var input = workspace.newInputStream(file, 0)) {
      assertEquals(-1, input.read());
      assertEquals(-1, input.read(new byte[1]));
    }
  }

  @Test
  void outputOptionsFailBeforeMutationAndPreserveAppendSemantics(@TempDir Path tmp)
      throws Exception {
    var workspace = WorkspaceRoot.of(tmp);
    var file = Files.writeString(tmp.resolve("file"), "keep");
    assertThrows(
        IllegalArgumentException.class,
        () -> workspace.newOutputStream(file, StandardOpenOption.READ));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            workspace.newOutputStream(
                file, StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING));
    assertThrows(
        UnsupportedOperationException.class,
        () -> workspace.newOutputStream(file, StandardOpenOption.DELETE_ON_CLOSE));
    assertThrows(
        UnsupportedOperationException.class,
        () -> workspace.newOutputStream(file, new OpenOption() {}));
    assertThrows(
        NullPointerException.class, () -> workspace.newOutputStream(file, new OpenOption[] {null}));
    assertThrows(
        FileAlreadyExistsException.class,
        () -> workspace.newOutputStream(file, StandardOpenOption.CREATE_NEW));
    assertEquals("keep", Files.readString(file));
    try (var output =
        workspace.newOutputStream(file, StandardOpenOption.APPEND, StandardOpenOption.SYNC)) {
      output.write('!');
    }
    assertEquals("keep!", Files.readString(file));
    assertThrows(
        NoSuchFileException.class,
        () -> workspace.newOutputStream(tmp.resolve("missing"), StandardOpenOption.WRITE));
    try (var output =
        workspace.newOutputStream(
            tmp.resolve("created"),
            StandardOpenOption.CREATE,
            StandardOpenOption.DSYNC,
            StandardOpenOption.SPARSE)) {
      output.write(new byte[] {1, 2, 3});
    }
    assertEquals(3, Files.size(tmp.resolve("created")));
    assertThrows(IOException.class, () -> workspace.newOutputStream(tmp, StandardOpenOption.WRITE));
  }

  @Test
  void strictMemoryRefusesWorkspaceAliasesEvenWithTrustedWorkspace(@TempDir Path tmp)
      throws Exception {
    var workspace = new WorkspaceRoot(tmp, false);
    var memory = FileSystemMemoryBackend.of(workspace);
    var directory = Files.createDirectories(tmp.resolve(".agent/memory"));
    var project = Files.writeString(tmp.resolve("project"), "sentinel");
    Files.createSymbolicLink(directory.resolve("alias"), project);
    assertThrows(IllegalArgumentException.class, () -> memory.view("/memories/alias"));
    assertThrows(IllegalArgumentException.class, () -> memory.create("/memories/alias/new", "x"));
    assertEquals("sentinel", Files.readString(project));
    assertThrows(IOException.class, () -> memory.create("/memories/new/invalid", "\ud800"));
    assertFalse(Files.exists(directory.resolve("new")));
  }

  @Test
  void walkSupportsPruningTerminationAndClosesDescriptors(@TempDir Path tmp) throws Exception {
    var workspace = WorkspaceRoot.of(tmp);
    Files.createDirectories(tmp.resolve("skip/deep"));
    Files.writeString(tmp.resolve("skip/deep/hidden"), "x");
    Files.createDirectories(tmp.resolve("keep"));
    Files.writeString(tmp.resolve("keep/visible"), "x");
    Files.createSymbolicLink(tmp.resolve("link"), tmp.resolve("keep"));
    var seen = new HashSet<String>();
    workspace.walkFileTree(
        tmp,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
            return directory.getFileName().toString().equals("skip")
                ? FileVisitResult.SKIP_SUBTREE
                : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            seen.add(file.getFileName().toString());
            return FileVisitResult.CONTINUE;
          }
        });
    assertEquals(Set.of("visible", "link"), seen);
    long before = descriptorCount();
    for (int attempt = 0; attempt < 50; attempt++) {
      workspace.walkFileTree(
          tmp,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              return FileVisitResult.TERMINATE;
            }
          });
      assertThrows(
          IOException.class,
          () ->
              workspace.walkFileTree(
                  tmp,
                  new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                      throw new IOException("visitor failed");
                    }
                  }));
    }
    assertTrue(descriptorCount() <= before + 2, "walks must close every descriptor");
    assertThrows(
        NoSuchFileException.class,
        () -> workspace.walkFileTree(tmp.resolve("missing"), new SimpleFileVisitor<>() {}));
  }

  @Test
  void trustedWorkspaceRetainsPortableOperationsAndReadLimits(@TempDir Path tmp) throws Exception {
    var workspace = new WorkspaceRoot(tmp, false);
    var directory = tmp.resolve("created/nested");
    workspace.createDirectories(directory);
    var file = Files.writeString(directory.resolve("file"), "abc");
    try (var output = workspace.newOutputStream(file)) {
      output.write("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    try (var output =
        workspace.newOutputStream(file, new StandardOpenOption[] {StandardOpenOption.APPEND})) {
      output.write('d');
    }
    assertEquals("abcd", Files.readString(file));
    Files.writeString(file, "abc");
    assertThrows(IOException.class, () -> workspace.newInputStream(file, 2));
    assertThrows(IOException.class, () -> workspace.newInputStream(directory));
    assertThrows(IllegalArgumentException.class, () -> workspace.newInputStream(file, -1));
    try (var input = workspace.newInputStream(file, 3)) {
      Files.writeString(file, "abcdef");
      assertEquals(3, input.read(new byte[5]));
      assertThrows(IOException.class, () -> input.read(new byte[1]));
    }
    try (var entries = workspace.newDirectoryStream(directory)) {
      assertEquals(file, entries.iterator().next());
    }
    assertThrows(IOException.class, () -> workspace.deleteFile(directory));
    workspace.deleteFile(file);
    assertFalse(Files.exists(file));
  }

  @Test
  void failedPermissionChecksClosePinnedDescriptors(@TempDir Path tmp) throws Exception {
    var workspace = WorkspaceRoot.of(tmp);
    var directory = Files.createDirectory(tmp.resolve("locked"));
    var file = Files.writeString(directory.resolve("file"), "sentinel");
    var permissions = Files.getPosixFilePermissions(directory);
    Files.setPosixFilePermissions(directory, Set.of());
    try {
      assumeTrue(!Files.isReadable(directory), "requires an unprivileged test process");
      long before = descriptorCount();
      for (int attempt = 0; attempt < 20; attempt++) {
        assertThrows(AccessDeniedException.class, () -> workspace.newInputStream(file));
        assertThrows(AccessDeniedException.class, () -> workspace.newOutputStream(file));
        assertThrows(AccessDeniedException.class, () -> workspace.newDirectoryStream(directory));
      }
      assertTrue(descriptorCount() <= before + 2);
      assertThrows(IllegalArgumentException.class, () -> WorkspaceRoot.of(file));
    } finally {
      Files.setPosixFilePermissions(directory, permissions);
    }
    assertEquals("sentinel", Files.readString(file));
  }

  @Test
  void creationPermissionsAndPinnedParentRemainConfined(@TempDir Path tmp) throws Exception {
    var root = Files.createDirectory(tmp.resolve("workspace"));
    var workspace = WorkspaceRoot.of(root);
    workspace.createDirectories(root);
    var directory = root.resolve("created/nested");
    workspace.createDirectories(directory);
    assertTrue(
        PosixFilePermissions.fromString("rwx------")
            .containsAll(Files.getPosixFilePermissions(directory)));
    var file = directory.resolve("file");
    try (var output =
        workspace.newOutputStream(file, LinkOption.NOFOLLOW_LINKS, StandardOpenOption.CREATE_NEW)) {
      output.write('x');
    }
    assertTrue(
        PosixFilePermissions.fromString("rw-------")
            .containsAll(Files.getPosixFilePermissions(file)));
    try (var input = workspace.newInputStream(file)) {
      assertEquals(1, input.read(new byte[2]));
      assertEquals(-1, input.read(new byte[2]));
    }
    var outside = Files.createDirectory(tmp.resolve("outside"));
    Files.writeString(outside.resolve("file"), "sentinel");
    try (var parent = LinuxFiles.directory(directory)) {
      Files.move(directory, root.resolve("held"));
      Files.createSymbolicLink(directory, outside);
      try (var entry = parent.open("file", 0, 0)) {
        assertEquals(1, entry.regularFile().size());
        assertEquals("x", Files.readString(entry.procPath()));
      }
    }
    assertEquals("sentinel", Files.readString(outside.resolve("file")));
    assertTrue(LinuxFiles.attributes(Path.of("/")).isDirectory());
    assertThrows(IOException.class, () -> WorkspaceRoot.of(Path.of("/")).deleteFile(Path.of("/")));
    assertThrows(
        IllegalArgumentException.class, () -> LinuxFiles.directory(root.resolve("a/../b")));
  }

  @Test
  void walkerRefusesDirectorySubstitutionBetweenMetadataAndEnumeration(@TempDir Path tmp)
      throws Exception {
    var root = Files.createDirectory(tmp.resolve("workspace"));
    var directory = Files.createDirectory(root.resolve("directory"));
    var outside = Files.createDirectory(tmp.resolve("outside"));
    Files.writeString(outside.resolve("sentinel"), "secret");
    var failed = new ArrayList<Path>();
    WorkspaceRoot.of(root)
        .walkFileTree(
            root,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs)
                  throws IOException {
                if (path.equals(directory)) {
                  Files.delete(directory);
                  Files.createSymbolicLink(directory, outside);
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                throw new AssertionError("outside file was visited: " + path);
              }

              @Override
              public FileVisitResult visitFileFailed(Path path, IOException error) {
                failed.add(path);
                return FileVisitResult.TERMINATE;
              }
            });
    assertEquals(java.util.List.of(directory), failed);
  }

  @Test
  void walkerHonoursSiblingPruningAndDirectoryTermination(@TempDir Path tmp) throws Exception {
    var workspace = WorkspaceRoot.of(tmp);
    Files.writeString(tmp.resolve("first"), "x");
    Files.writeString(tmp.resolve("second"), "x");
    var visited = new ArrayList<Path>();
    workspace.walkFileTree(
        tmp,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
            visited.add(path);
            return FileVisitResult.SKIP_SIBLINGS;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path path, IOException error) {
            visited.add(path);
            return FileVisitResult.TERMINATE;
          }
        });
    assertEquals(2, visited.size());
    assertEquals(tmp, visited.getLast());
    visited.clear();
    workspace.walkFileTree(
        tmp,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) {
            return FileVisitResult.TERMINATE;
          }

          @Override
          public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
            visited.add(path);
            return FileVisitResult.CONTINUE;
          }
        });
    assertTrue(visited.isEmpty());
    workspace.walkFileTree(
        tmp.resolve("missing"),
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFileFailed(Path path, IOException error) {
            visited.add(path);
            return FileVisitResult.TERMINATE;
          }
        });
    assertEquals(java.util.List.of(tmp.resolve("missing")), visited);
  }

  @Test
  void directoryAndHandleClosureAreIdempotent(@TempDir Path tmp) throws Exception {
    var handle = LinuxFiles.pin(tmp);
    handle.close();
    handle.close();
    assertThrows(IOException.class, handle::attributes);
    var entries = WorkspaceRoot.of(tmp).newDirectoryStream(tmp);
    entries.close();
    entries.close();
    assertThrows(IllegalArgumentException.class, () -> LinuxFiles.directory(Path.of("relative")));
    assertThrows(FileSystemException.class, () -> WorkspaceRoot.of(tmp).deleteFile(tmp));
  }

  private static long descriptorCount() throws IOException {
    try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
      return descriptors.count();
    }
  }

  private static void runProbe(Path directory, String mode, boolean nativeAccess) throws Exception {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    if (nativeAccess) {
      command.add("--enable-native-access=ALL-UNNAMED");
    }
    command.add("-cp");
    command.add(
        String.join(
            java.io.File.pathSeparator,
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            System.getProperty("jdk.module.path", ""),
            Path.of(WorkspaceRoot.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString()));
    command.add(Probe.class.getName());
    command.add(directory.toString());
    command.add(mode);
    var log = directory.resolve("probe.log");
    var builder =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.environment().remove("JDK_JAVA_OPTIONS");
    builder.environment().remove("_JAVA_OPTIONS");
    var process = builder.start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "native probe must not block");
      assertEquals(0, process.exitValue(), Files.readString(log));
    } finally {
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS));
    }
  }

  public static final class Probe {
    public static void main(String[] args) throws Exception {
      var root = Path.of(args[0]);
      if (args[1].equals("denied")) {
        assertThrows(UnsupportedOperationException.class, () -> WorkspaceRoot.of(root));
        var trusted = new WorkspaceRoot(root, false);
        assertThrows(
            UnsupportedOperationException.class, () -> FileSystemMemoryBackend.of(trusted));
        return;
      }
      var workspace = WorkspaceRoot.of(root);
      var fifo = root.resolve("fifo");
      assertFalse(workspace.attributes(fifo).isRegularFile());
      assertThrows(IOException.class, () -> workspace.newInputStream(fifo));
      assertThrows(IOException.class, () -> workspace.newOutputStream(fifo));
      assertThrows(
          IOException.class, () -> workspace.newOutputStream(fifo, StandardOpenOption.APPEND));
      var device = new WorkspaceRoot(Path.of("/dev"), true);
      assertThrows(IOException.class, () -> device.newInputStream(Path.of("/dev/null")));
      assertThrows(IOException.class, () -> device.newOutputStream(Path.of("/dev/null")));
    }
  }
}
