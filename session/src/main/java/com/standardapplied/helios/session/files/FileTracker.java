/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Per-session ledger of file reads and writes. The Phase 3 {@code Edit} tools require that a file
 * was read in the current session and that its fingerprint hasn't drifted since — both checks route
 * through this interface so the storage layer (in-memory today, possibly durable later) stays
 * separable.
 *
 * <p>{@link InMemoryFileTracker} is the production implementation; sessions construct one per run
 * via {@link InMemoryFileTracker#create()}.
 *
 * <h2>Thread-safety</h2>
 *
 * Implementations must be safe for the agent-loop usage pattern: the loop thread records reads and
 * writes; observability readers may query at any time. Phase 3 may add concurrent dispatch within a
 * turn.
 */
public interface FileTracker {

  /**
   * Record that {@code path} was read in this session and capture its fingerprint at read time.
   * Subsequent calls overwrite the prior fingerprint — the latest read wins.
   *
   * @param path absolute, normalised path; non-null
   * @param fingerprint the fingerprint at read time; non-null
   */
  void recordRead(Path path, FileFingerprint fingerprint);

  /**
   * Record that {@code path} was written in this session and capture the post-write fingerprint.
   * Phase 3 file-write tools call this after a successful write.
   *
   * @param path absolute, normalised path; non-null
   * @param fingerprint the fingerprint at write time; non-null
   */
  void recordWrite(Path path, FileFingerprint fingerprint);

  /**
   * Whether {@code path} has been read in this session.
   *
   * @param path absolute, normalised path; non-null
   * @return {@code true} if any prior {@link #recordRead(Path, FileFingerprint)} fired for the path
   */
  boolean hasReadInSession(Path path);

  /**
   * The fingerprint captured at the most recent {@link #recordRead(Path, FileFingerprint)} for
   * {@code path}, if any.
   *
   * @param path absolute, normalised path; non-null
   * @return the last-read fingerprint, or empty if the path was never read this session
   */
  Optional<FileFingerprint> fingerprintAtLastRead(Path path);
}
