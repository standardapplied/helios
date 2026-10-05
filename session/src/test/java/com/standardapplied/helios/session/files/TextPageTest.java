/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package com.standardapplied.helios.session.files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TextPageTest {

  @Test
  void aFileTheConfinedOpenRefusesIsAnIoErrorReadingIt(@TempDir Path tmp) {
    var workspace = WorkspaceRoot.of(tmp);

    var result = TextPage.read(workspace, workspace.resolveSafe("absent.txt"), 1, 10, null);

    assertFalse(result.success());
    assertTrue(result.output().startsWith("Read: I/O error reading file: "), result.output());
  }
}
