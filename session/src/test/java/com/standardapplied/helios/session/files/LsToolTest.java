/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.standardapplied.helios.core.model.ToolCall;
import com.standardapplied.helios.core.runtime.CancellationToken;
import com.standardapplied.helios.core.runtime.SessionContext;
import com.standardapplied.helios.core.tool.ToolContext;
import com.standardapplied.helios.core.tool.ToolResult;
import com.standardapplied.helios.session.tools.ToolBinding;
import com.standardapplied.helios.session.tools.ToolCategory;
import com.standardapplied.helios.session.tools.ToolPermissionKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class LsToolTest {

  @Test
  void listsDirectoryContents(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve("alpha"));
    Files.writeString(tmp.resolve("beta.txt"), "x", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("aaa.md"), "y", StandardCharsets.UTF_8);
    Files.createDirectory(tmp.resolve("zeta"));

    var result =
        LsTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "."), ToolContext.noop());

    assertTrue(result.succeeded(), result.output());
    var out = result.output();
    assertTrue(out.contains("alpha/"), out);
    assertTrue(out.contains("zeta/"), out);
    assertTrue(out.contains("aaa.md"), out);
    assertTrue(out.contains("beta.txt"), out);
    assertTrue(out.indexOf("alpha/") < out.indexOf("zeta/"), out);
    assertTrue(out.indexOf("zeta/") < out.indexOf("aaa.md"), out);
    assertTrue(out.indexOf("aaa.md") < out.indexOf("beta.txt"), out);
  }

  @Test
  void categoryIsRead(@TempDir Path tmp) {
    var binding = LsTool.binding(WorkspaceRoot.of(tmp));
    assertEquals(ToolCategory.READ, binding.category());
    assertEquals("LS", binding.name());
    assertEquals("LS", LsTool.NAME);
  }

  @Test
  void defaultPathListsRoot(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("readme"), "", StandardCharsets.UTF_8);
    var result = LsTool.binding(WorkspaceRoot.of(tmp)).tool().execute(Map.of(), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertTrue(result.output().contains("readme"));
  }

  @Test
  void emptyPathFallsBackToDefault(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("readme"), "", StandardCharsets.UTF_8);
    var result =
        LsTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", ""), ToolContext.noop());
    assertTrue(result.succeeded());
    assertTrue(result.output().contains("readme"));
  }

  @Test
  void nonStringPathFallsBackToDefault(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("readme"), "", StandardCharsets.UTF_8);
    var result =
        LsTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", 42), ToolContext.noop());
    assertTrue(result.succeeded());
    assertTrue(result.output().contains("readme"));
  }

  @Test
  void notADirectoryFails(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("file.txt"), "x", StandardCharsets.UTF_8);
    var result =
        LsTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "file.txt"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().contains("not a directory"), result.output());
  }

  @Test
  void escapingWorkspaceFails(@TempDir Path tmp) {
    var result =
        LsTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of("path", "../etc"), ToolContext.noop());
    assertFalse(result.succeeded());
    assertTrue(result.output().startsWith("LS:"), result.output());
  }

  @Test
  void permissionKeyCarriesPath(@TempDir Path tmp) throws IOException {
    Files.createDirectory(tmp.resolve("src"));
    var binding = LsTool.binding(WorkspaceRoot.of(tmp));
    assertEquals("./src", keyOf(binding, Map.of("path", "src")).canonicalArgs());
    assertEquals(".", keyOf(binding, Map.of()).canonicalArgs());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void symlinkRendersWithAt(@TempDir Path tmp) throws IOException {
    var target = tmp.resolve("target.txt");
    Files.writeString(target, "x", StandardCharsets.UTF_8);
    Files.createSymbolicLink(tmp.resolve("link.txt"), target);
    var result =
        LsTool.binding(new WorkspaceRoot(tmp, false))
            .tool()
            .execute(Map.of("path", "."), ToolContext.noop());
    assertTrue(result.succeeded(), result.output());
    assertTrue(result.output().contains("link.txt@"), result.output());
    assertTrue(result.output().contains("target.txt"), result.output());
  }

  @Test
  void rejectsNullWorkspace() {
    assertThrows(NullPointerException.class, () -> LsTool.binding(null));
  }

  private static ToolPermissionKey keyOf(ToolBinding binding, Map<String, Object> args) {
    return binding.canonicalize(new ToolCall("call-1", binding.name(), args)).key();
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aLinkWhoseTargetCannotBeResolvedIsKeyedByItsOwnPath(@TempDir Path tmp) throws IOException {
    Files.createSymbolicLink(tmp.resolve("dangling"), tmp.resolve("gone"));
    Files.writeString(tmp.resolve("kept.txt"), "k", StandardCharsets.UTF_8);
    var result =
        LsTool.binding(WorkspaceRoot.of(tmp))
            .tool()
            .execute(Map.of(), withholding("./dangling"::equals));

    assertEquals("kept.txt\n[withheld by permission policy: 1]\n", result.output());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aLinkLeadingOutsideAStrictWorkspaceIsKeyedByItsOwnPath(@TempDir Path tmp)
      throws IOException {
    var root = Files.createDirectory(tmp.resolve("ws"));
    Files.createSymbolicLink(root.resolve("out"), Files.createDirectory(tmp.resolve("outside")));

    var result =
        LsTool.binding(WorkspaceRoot.of(root))
            .tool()
            .execute(Map.of(), withholding("./out"::equals));

    assertEquals("[withheld by permission policy: 1]\n", result.output());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aTrustedOutsideListingKeysALinkByItsRealTarget(@TempDir Path tmp) throws IOException {
    var root = Files.createDirectory(tmp.resolve("ws"));
    var outside = Files.createDirectory(tmp.resolve("outside")).toRealPath();
    Files.createDirectory(outside.resolve("secrets"));
    Files.createSymbolicLink(outside.resolve("alias"), Path.of("secrets"));
    Files.createSymbolicLink(root.resolve("ext"), outside);
    var denied = outside.resolve("secrets").toString();

    var result =
        LsTool.binding(new WorkspaceRoot(root, false))
            .tool()
            .execute(Map.of("path", outside.toString()), withholding(denied::equals));

    assertEquals("[withheld by permission policy: 2]\n", result.output());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void anUninspectableEntryWhoseOwnPathIsDeniedIsWithheld(@TempDir Path tmp) throws IOException {
    var docs = Files.createDirectory(tmp.resolve("docs"));
    Files.writeString(docs.resolve("private-name.txt"), "p", StandardCharsets.UTF_8);

    var result = listUnsearchable(docs, tmp, "./docs/private-name.txt"::equals);

    assertEquals("[withheld by permission policy: 1]\n", result.output());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void anUninspectableEntryThatIsNotDeniedFailsWithoutBeingNamed(@TempDir Path tmp)
      throws IOException {
    var docs = Files.createDirectory(tmp.resolve("docs"));
    Files.writeString(docs.resolve("private-name.txt"), "p", StandardCharsets.UTF_8);

    var result = listUnsearchable(docs, tmp, key -> false);

    assertEquals(
        "LS: I/O error listing docs: could not inspect a directory entry", result.output());
  }

  private static ToolResult listUnsearchable(Path dir, Path root, Predicate<String> withheld)
      throws IOException {
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r--------"));
    try {
      Assumptions.assumeFalse(Files.isExecutable(dir), "permissions are not enforced (root)");
      return LsTool.binding(WorkspaceRoot.of(root))
          .tool()
          .execute(Map.of("path", "docs"), withholding(withheld));
    } finally {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }
  }

  private static ToolContext withholding(Predicate<String> withheld) {
    return new ToolContext(
        SessionContext.forTesting("ls"), new CancellationToken(), Duration.ofMinutes(1), withheld);
  }
}
