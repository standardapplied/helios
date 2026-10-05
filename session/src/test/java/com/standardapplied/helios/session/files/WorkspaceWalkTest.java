/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.standardapplied.helios.core.runtime.CancellationToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceWalkTest {

  @Test
  void cancellationDuringTheWalkStopsBeforeTheNextFile(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("a.txt"), "a", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("b.txt"), "b", StandardCharsets.UTF_8);
    var workspace = WorkspaceRoot.of(tmp);
    var cancellation = new CancellationToken();
    var visited = new ArrayList<Path>();

    var results =
        WorkspaceWalk.run(
            workspace,
            workspace.root(),
            cancellation,
            10,
            Long.MAX_VALUE,
            (file, attrs, remaining) -> {
              visited.add(file);
              cancellation.cancel("stop");
              return 1;
            });

    assertEquals(1, results);
    assertEquals(1, visited.size());
  }

  @Test
  void eachActionIsOfferedTheResultsLeftAndTheWalkStopsAtTheCap(@TempDir Path tmp)
      throws IOException {
    for (var name : List.of("a.txt", "b.txt", "c.txt")) {
      Files.writeString(tmp.resolve(name), name, StandardCharsets.UTF_8);
    }
    var workspace = WorkspaceRoot.of(tmp);
    var offered = new ArrayList<Integer>();

    var results =
        WorkspaceWalk.run(
            workspace,
            workspace.root(),
            new CancellationToken(),
            3,
            Long.MAX_VALUE,
            (file, attrs, remaining) -> {
              offered.add(remaining);
              return Math.min(2, remaining);
            });

    assertEquals(3, results);
    assertEquals(List.of(3, 1), offered);
  }

  @Test
  void filesOverTheSizeCapAndHiddenDirectoriesAreNeverOffered(@TempDir Path tmp)
      throws IOException {
    Files.writeString(tmp.resolve("small.txt"), "s", StandardCharsets.UTF_8);
    Files.writeString(tmp.resolve("large.txt"), "large", StandardCharsets.UTF_8);
    Files.createDirectory(tmp.resolve(".hidden"));
    Files.writeString(tmp.resolve(".hidden/small.txt"), "h", StandardCharsets.UTF_8);
    var workspace = WorkspaceRoot.of(tmp);
    var visited = new ArrayList<String>();

    WorkspaceWalk.run(
        workspace,
        workspace.root(),
        new CancellationToken(),
        10,
        1,
        (file, attrs, remaining) -> {
          visited.add(workspace.relativize(file));
          return 0;
        });

    assertEquals(List.of("small.txt"), visited);
  }

  @Test
  void aHiddenStartDirectoryIsWalked(@TempDir Path tmp) throws IOException {
    var hidden = Files.createDirectory(tmp.resolve(".config"));
    Files.writeString(hidden.resolve("app.txt"), "x", StandardCharsets.UTF_8);
    var workspace = WorkspaceRoot.of(tmp);
    var visited = new ArrayList<String>();

    WorkspaceWalk.run(
        workspace,
        workspace.resolveSafe(".config"),
        new CancellationToken(),
        10,
        Long.MAX_VALUE,
        (file, attrs, remaining) -> {
          visited.add(workspace.relativize(file));
          return 0;
        });

    assertEquals(List.of(".config/app.txt"), visited);
  }
}
