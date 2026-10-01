/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable snapshot of a file's bytes plus its fingerprint at the moment the snapshot was taken.
 * Used by Phase 3 file-write tools to capture {@code contentBefore} for the checkpoint store.
 *
 * @param path the file path; non-null
 * @param content the file bytes; non-null but may be empty (defensively cloned at construction)
 * @param fingerprint the matching fingerprint; non-null
 */
public record FileSnapshot(Path path, byte[] content, FileFingerprint fingerprint) {

  /**
   * Canonical constructor; defensively clones {@code content}.
   *
   * @throws NullPointerException if any argument is null
   */
  public FileSnapshot {
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(content, "content must not be null");
    Objects.requireNonNull(fingerprint, "fingerprint must not be null");
    content = content.clone();
  }

  /**
   * Returns a fresh copy of {@link #content()} to preserve immutability — callers that mutate the
   * returned array do not affect the snapshot.
   *
   * @return a defensive copy of the file bytes
   */
  @Override
  public byte[] content() {
    return content.clone();
  }
}
